package com.lyra.desktop.audio

import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import javax.sound.sampled.AudioFormat
import javax.sound.sampled.AudioSystem
import javax.sound.sampled.SourceDataLine
import kotlin.concurrent.thread
import kotlin.concurrent.withLock
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

private const val RATE = FfmpegDecoder.SAMPLE_RATE

/**
 * Una canción preparada para sonar. Se decodifica en su propio hilo, unos segundos por delante
 * de lo que suena, para que un tirón de la red no se note.
 */
class PlayingTrack(
    val id: Long,
    /** Lo que el reproductor quiera asociar (el elemento de la cola). */
    val tag: Any?,
    knownDurationMs: Long?,
    startMs: Long = 0,
    /** El volumen igualado de esta canción (con lo ya medido de ella, si se sabe). */
    val normalizer: LoudnessNormalizer = LoudnessNormalizer(RATE),
    private val opener: () -> FfmpegDecoder,
) {
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val ring = FloatArray(CAPACITY_FRAMES * 2)
    private var start = 0
    private var size = 0
    private var decoderDone = false
    private var closed = false
    private var pendingSeekMs: Long? = startMs.takeIf { it > 0 }
    private var started = false

    @Volatile var durationMs: Long? = knownDurationMs
        private set

    @Volatile var error: Throwable? = null
        private set

    /** Ya se ha abierto el audio (ha llegado de la red y se entiende). */
    @Volatile var ready = false
        private set

    /** Fotogramas ya entregados a la salida (la posición en la canción). */
    @Volatile var positionFrames: Long = startMs.coerceAtLeast(0) * RATE / 1000
        private set

    fun startDecoding() {
        lock.withLock {
            if (started || closed) return
            started = true
        }
        thread(name = "lyra-pista-$id", isDaemon = true) { decodeLoop() }
    }

    /** Lo que haya ya decodificado, sin esperar. Devuelve cuántos fotogramas ha puesto. */
    fun read(out: FloatArray, offset: Int, frames: Int): Int = lock.withLock {
        if (pendingSeekMs != null) return 0
        val n = minOf(frames, size)
        var copied = 0
        while (copied < n) {
            val from = (start + copied) % CAPACITY_FRAMES
            val run = minOf(n - copied, CAPACITY_FRAMES - from)
            System.arraycopy(ring, from * 2, out, (offset + copied) * 2, run * 2)
            copied += run
        }
        start = (start + n) % CAPACITY_FRAMES
        size -= n
        positionFrames += n
        if (n > 0) changed.signalAll()
        n
    }

    /** Se ha acabado de verdad: no queda nada por decodificar ni por entregar. */
    val isFinished: Boolean get() = lock.withLock { decoderDone && size == 0 && pendingSeekMs == null }

    fun seek(positionMs: Long) = lock.withLock {
        pendingSeekMs = positionMs.coerceAtLeast(0)
        size = 0
        start = 0
        decoderDone = false
        positionFrames = positionMs.coerceAtLeast(0) * RATE / 1000
        changed.signalAll()
    }

    fun close() = lock.withLock {
        closed = true
        changed.signalAll()
    }

    private fun decodeLoop() {
        var decoder: FfmpegDecoder? = null
        val chunk = FloatArray(CHUNK_FRAMES * 2)
        try {
            decoder = opener()
            decoder.durationMs?.let { durationMs = it }
            ready = true
            while (true) {
                val seekTo: Long?
                lock.withLock {
                    while (!closed && pendingSeekMs == null && (decoderDone || CAPACITY_FRAMES - size < CHUNK_FRAMES)) {
                        changed.await(500, TimeUnit.MILLISECONDS)
                    }
                    if (closed) return
                    seekTo = pendingSeekMs
                }
                if (seekTo != null) {
                    decoder.seek(seekTo)
                    lock.withLock {
                        if (pendingSeekMs == seekTo) {
                            pendingSeekMs = null
                            size = 0
                            start = 0
                            positionFrames = seekTo * RATE / 1000
                        }
                    }
                    normalizer.onSeek()
                    continue
                }
                val n = decoder.read(chunk, 0, CHUNK_FRAMES)
                lock.withLock {
                    if (pendingSeekMs == null && !closed) {
                        if (n == 0) {
                            decoderDone = true
                        } else {
                            var written = 0
                            while (written < n) {
                                val to = (start + size + written) % CAPACITY_FRAMES
                                val run = minOf(n - written, CAPACITY_FRAMES - to)
                                System.arraycopy(chunk, written * 2, ring, to * 2, run * 2)
                                written += run
                            }
                            size += n
                        }
                    }
                    changed.signalAll()
                }
            }
        } catch (e: Throwable) {
            error = e
            lock.withLock {
                decoderDone = true
                changed.signalAll()
            }
        } finally {
            runCatching { decoder?.close() }
        }
    }

    private companion object {
        const val CAPACITY_FRAMES = RATE * 8
        const val CHUNK_FRAMES = 4096
    }
}

/**
 * Lo que suena por la tarjeta de sonido de Windows. Lleva la canción actual y la siguiente ya
 * preparada: entre canciones no hay hueco y, con el fundido activado, una se funde con la otra
 * (como en Spotify). Al pausar, saltar o mover la barra, el sonido se apaga y se enciende con un
 * fundido cortito en vez de cortarse en seco. Después de mezclar: ecualizador, limitador y volumen.
 */
class AudioEngine(private val events: Events) {

    interface Events {
        /** [track] pasa a ser la que suena (al acabar la anterior o al ponerla a mano). */
        fun started(track: PlayingTrack)

        /** [track] ha sonado hasta el final. */
        fun ended(track: PlayingTrack)

        /** [track] no ha podido sonar. */
        fun failed(track: PlayingTrack, error: Throwable)
    }

    @Volatile var fx = AudioFx()
    @Volatile var volume = 0.8f
    @Volatile var muted = false

    /** Duración del fundido entre canciones (0 = sin fundido: sin hueco pero sin mezclar). */
    @Volatile var crossfadeMs = 0

    /** Esperando a que llegue el audio (la red va lenta o se está abriendo la canción). */
    @Volatile var buffering = false
        private set

    /** No hay salida de sonido (sin altavoces, o Windows no deja abrirla). */
    @Volatile var outputError: String? = null
        private set

    val levels = AudioLevels(RATE)

    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private var current: PlayingTrack? = null
    private var next: PlayingTrack? = null

    /** La que sonaba antes de saltar: se apaga poco a poco por debajo de la nueva. */
    private var tail: PlayingTrack? = null
    private var tailDone = 0
    private var paused = true

    /** Se ha pedido pausa: primero se apaga en un fundido corto y luego se para. */
    private var pausing = false
    private var flushPending = false

    /** Fotogramas que quedan del fundido de entrada (al reanudar, saltar o mover la barra). */
    private var fadeInLeft = 0
    @Volatile private var reopenLine = false
    private var line: SourceDataLine? = null
    private val equalizer = Equalizer(RATE)
    private val notifier = Executors.newSingleThreadExecutor { Thread(it, "lyra-eventos-audio").apply { isDaemon = true } }
    private var lastDevices: List<String> = emptyList()

    init {
        thread(name = "lyra-salida-audio", isDaemon = true, priority = Thread.MAX_PRIORITY) { outputLoop() }
        thread(name = "lyra-dispositivos", isDaemon = true) { watchDevices() }
    }

    val currentTrack: PlayingTrack? get() = lock.withLock { current }
    val nextTrack: PlayingTrack? get() = lock.withLock { next }
    val isPaused: Boolean get() = lock.withLock { paused || pausing }

    /**
     * Pone [track] a sonar ya. Si sonaba otra, no se corta en seco: se apaga en un momento por
     * debajo de la nueva (como al saltar de canción en Spotify).
     */
    fun play(track: PlayingTrack, startPaused: Boolean = false) {
        val toClose = mutableListOf<PlayingTrack>()
        lock.withLock {
            val previous = current
            val audible = previous != null && !paused && !pausing && previous !== track
            tail?.let { if (it !== track) toClose += it }
            tail = null
            if (audible && !startPaused) {
                tail = previous
                tailDone = 0
            } else if (previous != null && previous !== track) {
                toClose += previous
            }
            current = track
            if (next === track) next = null
            paused = startPaused
            pausing = false
            flushPending = true
            fadeInLeft = FADE_IN_FRAMES
            changed.signalAll()
        }
        toClose.forEach { it.close() }
        track.startDecoding()
    }

    /** La que sonará después (o null). Se empieza a preparar ya. */
    fun prepareNext(track: PlayingTrack?) {
        val old = lock.withLock {
            val previous = next
            if (previous === track) return
            next = track
            previous
        }
        old?.close()
        track?.startDecoding()
    }

    /** Pausa con un fundido corto (lo que esperaba en la tarjeta se descarta para que sea al momento). */
    fun pause() = lock.withLock {
        if (paused || pausing) return@withLock
        pausing = true
        flushPending = true
        changed.signalAll()
    }

    fun resume() = lock.withLock {
        if (!paused && !pausing) return@withLock
        paused = false
        pausing = false
        fadeInLeft = FADE_IN_FRAMES
        changed.signalAll()
    }

    fun stop() {
        val closing = lock.withLock {
            val list = listOfNotNull(current, next, tail)
            current = null
            next = null
            tail = null
            paused = true
            pausing = false
            flushPending = true
            changed.signalAll()
            list
        }
        closing.forEach { it.close() }
    }

    fun seek(positionMs: Long) {
        val oldTail = lock.withLock {
            current?.seek(positionMs)
            flushPending = true
            fadeInLeft = FADE_IN_FRAMES
            val t = tail
            tail = null
            changed.signalAll()
            t
        }
        oldTail?.close()
    }

    /** Posición de lo que se oye ahora mismo (descontando lo que espera en la tarjeta). */
    val positionMs: Long
        get() {
            val track = lock.withLock { current } ?: return 0
            val queued = queuedFrames()
            return ((track.positionFrames - queued).coerceAtLeast(0)) * 1000 / RATE
        }

    private fun queuedFrames(): Long {
        val out = line ?: return 0
        return runCatching { ((out.bufferSize - out.available()) / 4).toLong() }.getOrDefault(0)
    }

    private fun outputLoop() {
        val frames = 960 // 20 ms
        val mix = FloatArray(frames * 2)
        val incoming = FloatArray(frames * 2)
        val tailBuffer = FloatArray(frames * 2)
        val bytes = ByteArray(frames * 4)
        var gain = 0f
        var pauseGain = 1f
        while (true) {
            var cur: PlayingTrack?
            var nxt: PlayingTrack?
            var fading: PlayingTrack?
            var isPausing: Boolean
            lock.withLock {
                while ((current == null && tail == null) || paused) {
                    line?.let { out ->
                        if (out.isRunning) out.stop()
                        if (current == null || flushPending) {
                            out.flush()
                            flushPending = false
                        }
                    }
                    buffering = false
                    changed.await(1, TimeUnit.SECONDS)
                }
                cur = current
                nxt = next
                fading = tail
                isPausing = pausing
                if (flushPending) {
                    line?.flush()
                    flushPending = false
                    if (fading == null) equalizer.clear()
                }
            }
            val out = openLine()
            if (out == null) {
                Thread.sleep(1_000)
                continue
            }

            java.util.Arrays.fill(mix, 0f)
            var got = 0
            val track = cur
            if (track != null) {
                got = track.read(mix, 0, frames)
                if (got > 0) {
                    track.normalizer.process(mix, 0, got, fx.normalize)
                    // Entrada suave (al empezar, reanudar o mover la barra): sin chasquidos.
                    var left = lock.withLock { fadeInLeft }
                    if (left > 0) {
                        for (i in 0 until got) {
                            if (left <= 0) break
                            val g = 1f - left.toFloat() / FADE_IN_FRAMES
                            mix[i * 2] *= g
                            mix[i * 2 + 1] *= g
                            left--
                        }
                        lock.withLock { fadeInLeft = left }
                    }
                }

                // Fundido entre canciones: los últimos segundos de esta con el principio de la siguiente.
                val fadeMs = crossfadeMs
                val following = nxt
                if (following != null && fadeMs > 0 && got > 0 && following.ready) {
                    val duration = track.durationMs
                    if (duration != null && duration > fadeMs * 2) {
                        val fadeFrames = fadeMs.toLong() * RATE / 1000
                        val remainingAtStart = duration * RATE / 1000 - (track.positionFrames - got)
                        val fadeFrom = (remainingAtStart - fadeFrames).coerceAtLeast(0)
                        if (fadeFrom < got) {
                            val from = fadeFrom.toInt()
                            val k = following.read(incoming, 0, got - from)
                            if (k > 0) following.normalizer.process(incoming, 0, k, fx.normalize)
                            for (i in 0 until got - from) {
                                val index = from + i
                                val t = ((fadeFrames - (remainingAtStart - index)).toFloat() / fadeFrames).coerceIn(0f, 1f)
                                val a = cos(t * PI / 2).toFloat()
                                val b = sin(t * PI / 2).toFloat()
                                val inL = if (i < k) incoming[i * 2] else 0f
                                val inR = if (i < k) incoming[i * 2 + 1] else 0f
                                mix[index * 2] = mix[index * 2] * a + inL * b
                                mix[index * 2 + 1] = mix[index * 2 + 1] * a + inR * b
                            }
                        }
                    }
                }

                // Se acabó: sigue la preparada, sin hueco.
                if (got < frames && track.isFinished) {
                    val successor = finishCurrent(track)
                    if (successor != null) {
                        val more = successor.read(mix, got, frames - got)
                        if (more > 0) successor.normalizer.process(mix, got, more, fx.normalize)
                        got += more
                    }
                }
            }

            // La anterior, apagándose por debajo tras un salto.
            var produced = got
            val old = fading
            if (old != null) {
                val k = old.read(tailBuffer, 0, frames)
                if (k > 0) old.normalizer.process(tailBuffer, 0, k, fx.normalize)
                for (i in 0 until k) {
                    val g = (1f - (tailDone + i).toFloat() / TAIL_FADE_FRAMES).coerceIn(0f, 1f)
                    val curve = g * g
                    mix[i * 2] += tailBuffer[i * 2] * curve
                    mix[i * 2 + 1] += tailBuffer[i * 2 + 1] * curve
                }
                tailDone += k
                produced = maxOf(produced, k)
                if (tailDone >= TAIL_FADE_FRAMES || (k == 0 && old.isFinished) || old.error != null) {
                    lock.withLock { if (tail === old) tail = null }
                    old.close()
                }
            }

            if (produced == 0) {
                val error = track?.error
                if (track != null && error != null) {
                    lock.withLock { if (current === track) current = null }
                    buffering = false
                    notifier.execute { events.failed(track, error) }
                    continue
                }
                if (track != null && track.isFinished) continue
                if (isPausing) {
                    // Pausa pedida mientras llegaba el audio: no suena nada que apagar, se pausa ya.
                    lock.withLock {
                        if (pausing) {
                            pausing = false
                            paused = true
                        }
                    }
                    continue
                }
                buffering = track != null
                Thread.sleep(10)
                continue
            }
            buffering = false

            equalizer.process(mix, produced, fx)
            val target = if (muted) 0f else volume.coerceIn(0f, 1f).let { it * it }
            val step = (target - gain) / produced
            var pauseDone = false
            for (i in 0 until produced) {
                gain += step
                if (isPausing) {
                    pauseGain = (pauseGain - 1f / PAUSE_FADE_FRAMES).coerceAtLeast(0f)
                    if (pauseGain == 0f) pauseDone = true
                }
                val g = gain * (if (isPausing) pauseGain * pauseGain else 1f)
                mix[i * 2] = Dsp.softLimit(mix[i * 2]) * g
                mix[i * 2 + 1] = Dsp.softLimit(mix[i * 2 + 1]) * g
            }
            gain = target
            levels.analyse(mix, produced, System.nanoTime() + queuedFrames() * 1_000_000_000L / RATE)

            for (i in 0 until produced * 2) {
                val v = (mix[i] * 32767f).toInt().coerceIn(-32768, 32767)
                bytes[i * 2] = v.toByte()
                bytes[i * 2 + 1] = (v shr 8).toByte()
            }
            if (!out.isRunning) out.start()
            try {
                out.write(bytes, 0, produced * 4)
            } catch (e: Exception) {
                closeLine()
            }
            if (pauseDone) {
                // Apagada del todo: ahora sí, pausa (lo que queda en la tarjeta es ya silencio).
                pauseGain = 1f
                val oldTail = lock.withLock {
                    if (pausing) {
                        pausing = false
                        paused = true
                    }
                    val t = tail
                    tail = null
                    t
                }
                oldTail?.close()
            } else if (!isPausing) {
                pauseGain = 1f
            }
        }
    }

    /** La actual ha terminado: pasa la siguiente (si la hay) y se avisa. */
    private fun finishCurrent(finished: PlayingTrack): PlayingTrack? {
        val successor = lock.withLock {
            if (current !== finished) return current
            current = next
            next = null
            current
        }
        notifier.execute {
            events.ended(finished)
            if (successor != null) events.started(successor)
        }
        finished.close()
        return successor
    }

    private fun openLine(): SourceDataLine? {
        if (reopenLine) {
            reopenLine = false
            closeLine()
        }
        line?.let { if (it.isOpen) return it }
        return try {
            val format = AudioFormat(RATE.toFloat(), 16, 2, true, false)
            val opened = AudioSystem.getSourceDataLine(format)
            opened.open(format, BUFFER_FRAMES * 4)
            line = opened
            outputError = null
            opened
        } catch (e: Exception) {
            outputError = "No se puede usar la salida de sonido (${e.message ?: e.javaClass.simpleName})"
            null
        }
    }

    private fun closeLine() {
        line?.let { runCatching { it.stop(); it.flush(); it.close() } }
        line = null
    }

    /** Si cambian los altavoces (cascos, Bluetooth…), se vuelve a abrir la salida en el de Windows. */
    private fun watchDevices() {
        while (true) {
            Thread.sleep(2_000)
            val devices = runCatching { AudioSystem.getMixerInfo().map { it.name } }.getOrDefault(lastDevices)
            if (lastDevices.isNotEmpty() && devices != lastDevices) reopenLine = true
            lastDevices = devices
        }
    }

    private companion object {
        // 200 ms: aunque Java pare un momento a recoger memoria, la tarjeta de sonido tiene de sobra.
        const val BUFFER_FRAMES = RATE / 5
        const val FADE_IN_FRAMES = RATE / 25 // 40 ms
        const val PAUSE_FADE_FRAMES = RATE * 3 / 20 // 150 ms
        const val TAIL_FADE_FRAMES = RATE * 2 / 5 // 400 ms
    }
}
