package com.lyra.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/** Ajustes del procesador. Se cambian en caliente desde otro hilo. */
data class AudioFxConfig(
    val eqEnabled: Boolean = false,
    val bandsDb: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    val normalize: Boolean = true,
)

/**
 * Procesado de audio propio, dentro de ExoPlayer (no depende de los efectos del
 * fabricante, que en algunos móviles fallan):
 *  - Ecualizador de 10 bandas (filtros de pico RBJ).
 *  - Volumen igualado: mide la sonoridad media de la canción (ignorando silencios)
 *    y acerca la ganancia a un objetivo poco a poco, para que no "bombee". Si la
 *    canción ya se midió otra vez ([LoudnessMemory]), acierta desde el principio.
 *  - Limitador suave final para que nunca sature.
 */
@UnstableApi
class LyraAudioProcessor : BaseAudioProcessor() {

    @Volatile var config: AudioFxConfig = AudioFxConfig()
    @Volatile private var newTrackRequested = false
    @Volatile private var pendingKnownDb = Float.NaN

    /**
     * Ganancia fija en dB (NaN: no). La usa el reproductor auxiliar del crossfade: el final de la
     * canción tiene que seguir exactamente con el volumen que llevaba, sin medir ni cambiar nada.
     */
    @Volatile var holdGainDb: Float = Float.NaN

    /** La ganancia que se aplica ahora mismo (dB), para pasársela al auxiliar del crossfade. */
    @Volatile var currentGainDb: Float = 0f
        private set

    /** Lo medido de la canción actual: sonoridad media (dB) y segundos con sonido (para recordarlo). */
    @Volatile var measuredDb: Float = Float.NaN
        private set
    @Volatile var measuredSeconds: Float = 0f
        private set

    /** Solo el reproductor principal publica niveles para las barras de la isla. */
    var publishLevels: Boolean = false

    // Análisis para las barras: graves, medios y agudos del audio que sale.
    private var lowBand: Biquad? = null
    private var midBand: Biquad? = null
    private var highBand: Biquad? = null
    private var lowEnergy = 0.0
    private var midEnergy = 0.0
    private var highEnergy = 0.0
    private var analysisFrames = 0
    private var analysisWindow = 1200

    private var sampleRate = 0
    private var channels = 0
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var appliedConfig: AudioFxConfig? = null
    private var preampLinear = 1f

    // Estado de la normalización
    private var gainDb = 0f
    private var gainLinear = 1f
    private var gainStep = 0f
    private var energySum = 0.0
    private var energyBlocks = 0
    private var blockEnergy = 0.0
    private var blockFrames = 0
    private var blockSize = 4800
    private var trackFrames = 0L

    /** Sonoridad ya conocida de la canción actual (NaN si es la primera vez que suena). */
    private var knownDb = Float.NaN

    /** Sonoridad del último bloque (~100 ms) y la media de la canción hasta ahora, en dB. */
    class Loudness(val blockDb: Float, val trackDb: Float, val atNanos: Long)

    /** Lo último medido (para el crossfade inteligente); null al empezar una canción. */
    @Volatile
    var loudness: Loudness? = null
        private set

    /** Avisa de que empieza otra canción: se vuelve a medir desde cero. [knownDb]: lo que ya se sabe de ella. */
    fun onNewTrack(knownDb: Float = Float.NaN) {
        pendingKnownDb = knownDb
        newTrackRequested = true
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        blockSize = (sampleRate / 10).coerceAtLeast(256)
        analysisWindow = (sampleRate / 40).coerceAtLeast(128)
        lowBand = Biquad.lowPass(sampleRate, 160f)
        midBand = Biquad.bandPass(sampleRate, 1100f, 0.8f)
        highBand = Biquad.highPass(sampleRate, 5000f)
        appliedConfig = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        process(inputBuffer, output)
        output.flip()
    }

    private fun process(input: ByteBuffer, output: ByteBuffer) {
        val cfg = config
        if (cfg !== appliedConfig) rebuild(cfg)
        if (newTrackRequested) {
            newTrackRequested = false
            resetMeasurement()
            knownDb = pendingKnownDb
        }
        val hold = holdGainDb
        if (!hold.isNaN()) {
            gainLinear = dbToLinear(hold)
            gainStep = 0f
        }
        input.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val frames = input.remaining() / (2 * channels)
        val activeFilters = if (cfg.eqEnabled) filters else emptyArray()
        var framesUntilUpdate = blockSize - blockFrames

        val analyse = publishLevels
        for (frame in 0 until frames) {
            var frameEnergy = 0.0
            var mono = 0f
            for (ch in 0 until channels) {
                var x = input.short / 32768f
                for (band in activeFilters) x = band[ch].process(x)
                x *= preampLinear
                frameEnergy += (x * x).toDouble()
                var y = x * gainLinear
                y = softLimit(y)
                mono += y
                output.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            if (analyse) analyse(mono / channels)
            blockEnergy += frameEnergy / channels
            blockFrames++
            trackFrames++
            gainLinear += gainStep
            framesUntilUpdate--
            if (framesUntilUpdate <= 0) {
                gainDb = linearToDb(gainLinear)
                val measured = endBlock(cfg.normalize)
                val target = if (hold.isNaN()) measured else hold
                // La nueva ganancia se reparte a lo largo del bloque siguiente (sin saltos audibles).
                gainStep = (dbToLinear(target) - gainLinear) / blockSize
                framesUntilUpdate = blockSize
            }
        }
        gainDb = linearToDb(gainLinear)
        currentGainDb = gainDb
    }

    private fun analyse(sample: Float) {
        val low = lowBand?.process(sample) ?: return
        val mid = midBand?.process(sample) ?: return
        val high = highBand?.process(sample) ?: return
        lowEnergy += (low * low).toDouble()
        midEnergy += (mid * mid).toDouble()
        highEnergy += (high * high).toDouble()
        if (++analysisFrames >= analysisWindow) {
            val n = analysisFrames.toDouble()
            AudioLevels.push(
                System.nanoTime(),
                kotlin.math.sqrt(lowEnergy / n).toFloat(),
                kotlin.math.sqrt(midEnergy / n).toFloat(),
                kotlin.math.sqrt(highEnergy / n).toFloat(),
            )
            lowEnergy = 0.0
            midEnergy = 0.0
            highEnergy = 0.0
            analysisFrames = 0
        }
    }

    /** Cierra un bloque de ~100 ms: actualiza la sonoridad medida y decide la ganancia. */
    private fun endBlock(normalize: Boolean): Float {
        val meanSquare = blockEnergy / blockFrames.coerceAtLeast(1)
        blockEnergy = 0.0
        blockFrames = 0
        val blockDb = 10 * log10(meanSquare + 1e-12)
        if (blockDb > GATE_DB) {
            energySum += meanSquare
            energyBlocks++
        }
        val integratedDb = if (energyBlocks > 0) (10 * log10(energySum / energyBlocks + 1e-12)).toFloat() else Float.NaN
        // Media de la canción cuando ya hay unos segundos medidos (si no, NaN).
        val trackDb = if (energyBlocks >= 20) integratedDb else Float.NaN
        loudness = Loudness(blockDb.toFloat(), trackDb, System.nanoTime())
        measuredDb = integratedDb
        measuredSeconds = energyBlocks.toFloat() * blockSize / sampleRate.coerceAtLeast(1)

        val seconds = trackFrames.toFloat() / sampleRate.coerceAtLeast(1)
        // Lo ya conocido manda, salvo que lo medido ahora se aleje claramente (otra versión de la canción).
        val known = knownDb.takeIf { !it.isNaN() && (energyBlocks < 300 || abs(it - integratedDb) < 3f) }
        val (target, slewDb) = when {
            !normalize -> 0f to FAST_SLEW_DB
            known != null -> (TARGET_DB - known).toFloat().coerceIn(MAX_CUT_DB, MAX_BOOST_DB) to KNOWN_SLEW_DB
            energyBlocks >= 5 -> (TARGET_DB - integratedDb).toFloat().coerceIn(MAX_CUT_DB, MAX_BOOST_DB) to
                (if (seconds < 8f) FAST_SLEW_DB else SLOW_SLEW_DB)
            else -> gainDb to FAST_SLEW_DB
        }
        val maxStepDb = slewDb * blockSize / sampleRate.coerceAtLeast(1)
        return gainDb + (target - gainDb).coerceIn(-maxStepDb, maxStepDb)
    }

    private fun rebuild(cfg: AudioFxConfig) {
        appliedConfig = cfg
        val maxBoost = if (cfg.eqEnabled) cfg.bandsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f else 0f
        // Deja margen cuando se suben bandas para que el limitador trabaje poco.
        preampLinear = if (cfg.eqEnabled) dbToLinear(cfg.preampDb - maxBoost * 0.5f) else 1f
        if (channels == 0 || sampleRate == 0) {
            filters = emptyArray()
            return
        }
        filters = BAND_FREQUENCIES.indices
            .filter { index -> abs(cfg.bandsDb.getOrElse(index) { 0f }) > 0.05f && BAND_FREQUENCIES[index] < sampleRate * 0.45f }
            .map { index ->
                Array(channels) { Biquad.peaking(sampleRate, BAND_FREQUENCIES[index], BAND_Q, cfg.bandsDb[index]) }
            }
            .toTypedArray()
    }

    private fun resetMeasurement() {
        loudness = null
        energySum = 0.0
        energyBlocks = 0
        blockEnergy = 0.0
        blockFrames = 0
        trackFrames = 0
        knownDb = Float.NaN
        measuredDb = Float.NaN
        measuredSeconds = 0f
    }

    override fun onFlush() {
        filters.forEach { band -> band.forEach { it.clear() } }
        lowBand?.clear()
        midBand?.clear()
        highBand?.clear()
        // Al saltar dentro de la misma canción se sigue con lo medido: si se empezara de cero, la
        // parte a la que se salta (un puente flojito, un final...) cambiaría el volumen de golpe.
        // Las canciones nuevas avisan con onNewTrack.
        blockEnergy = 0.0
        blockFrames = 0
    }

    override fun onReset() {
        filters = emptyArray()
        appliedConfig = null
        // La ganancia se queda como estaba: la siguiente canción empieza cerca de la anterior,
        // no al volumen sin igualar.
        gainStep = 0f
        resetMeasurement()
    }

    class Biquad(
        private val b0: Float, private val b1: Float, private val b2: Float,
        private val a1: Float, private val a2: Float,
    ) {
        private var x1 = 0f
        private var x2 = 0f
        private var y1 = 0f
        private var y2 = 0f

        fun process(x: Float): Float {
            val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
            x2 = x1; x1 = x
            y2 = y1; y1 = y
            return y
        }

        fun clear() {
            x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
        }

        companion object {
            private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
                Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

            fun lowPass(sampleRate: Int, frequency: Float, q: Float = 0.707f): Biquad {
                val w0 = 2 * PI * frequency / sampleRate
                val alpha = sin(w0) / (2 * q)
                val c = cos(w0)
                return normalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun highPass(sampleRate: Int, frequency: Float, q: Float = 0.707f): Biquad {
                val w0 = 2 * PI * frequency / sampleRate
                val alpha = sin(w0) / (2 * q)
                val c = cos(w0)
                return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
            }

            fun bandPass(sampleRate: Int, frequency: Float, q: Float): Biquad {
                val w0 = 2 * PI * frequency / sampleRate
                val alpha = sin(w0) / (2 * q)
                val c = cos(w0)
                return normalized(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
            }

            /** Filtro de pico (Audio EQ Cookbook de R. Bristow-Johnson). */
            fun peaking(sampleRate: Int, frequency: Float, q: Float, gainDb: Float): Biquad {
                val a = 10.0.pow(gainDb / 40.0)
                val w0 = 2 * PI * frequency / sampleRate
                val alpha = sin(w0) / (2 * q)
                val a0 = 1 + alpha / a
                return Biquad(
                    b0 = ((1 + alpha * a) / a0).toFloat(),
                    b1 = ((-2 * cos(w0)) / a0).toFloat(),
                    b2 = ((1 - alpha * a) / a0).toFloat(),
                    a1 = ((-2 * cos(w0)) / a0).toFloat(),
                    a2 = ((1 - alpha / a) / a0).toFloat(),
                )
            }
        }
    }

    companion object {
        val BAND_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private const val BAND_Q = 1.1f
        private const val TARGET_DB = -17.0
        private const val GATE_DB = -50.0
        private const val MAX_BOOST_DB = 8f
        private const val MAX_CUT_DB = -12f
        private const val FAST_SLEW_DB = 4f
        private const val SLOW_SLEW_DB = 0.6f

        /** Con la sonoridad ya conocida se llega enseguida (en unas décimas) y luego no se mueve. */
        private const val KNOWN_SLEW_DB = 30f
        private const val LIMIT_THRESHOLD = 0.891f // -1 dBFS

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
        fun linearToDb(linear: Float): Float = 20f * log10(linear.coerceAtLeast(1e-6f))

        fun softLimit(x: Float): Float {
            val magnitude = abs(x)
            if (magnitude <= LIMIT_THRESHOLD) return x
            val headroom = 1f - LIMIT_THRESHOLD
            return sign(x) * (LIMIT_THRESHOLD + headroom * tanh((magnitude - LIMIT_THRESHOLD) / headroom))
        }
    }
}

/**
 * Niveles recientes de graves, medios y agudos del audio que sale por el
 * reproductor principal. Se guardan con su marca de tiempo para que la isla
 * pueda leerlos con el retraso con el que suenan de verdad por el altavoz.
 */
object AudioLevels {
    /** Lo que tarda el audio procesado en salir por el altavoz (aprox.). */
    const val LATENCY_NANOS = 180_000_000L
    private const val SIZE = 128
    private val times = LongArray(SIZE)
    private val values = FloatArray(SIZE * 3)
    private var head = 0
    private var count = 0

    // Pico y suelo recientes de cada banda: se guardan niveles ya normalizados (0..1)
    // para que las barras tengan movimiento desde el primer instante.
    private val peak = FloatArray(3) { 1e-3f }
    private val floor = FloatArray(3) { 1e-3f }

    @Synchronized
    fun push(timeNanos: Long, low: Float, mid: Float, high: Float) {
        times[head] = timeNanos
        values[head * 3] = normalize(0, low)
        values[head * 3 + 1] = normalize(1, mid)
        values[head * 3 + 2] = normalize(2, high)
        head = (head + 1) % SIZE
        if (count < SIZE) count++
    }

    private fun normalize(band: Int, value: Float): Float {
        // El pico baja un ~25 % por segundo; el suelo sube despacio hacia el nivel actual.
        peak[band] = maxOf(peak[band] * 0.993f, value, 1e-4f)
        floor[band] = if (value < floor[band]) value else floor[band] + (value - floor[band]) * 0.02f
        if (peak[band] < 0.002f) return 0f // casi silencio
        val range = maxOf(peak[band] - floor[band], peak[band] * 0.3f)
        return ((value - floor[band]) / range).coerceIn(0f, 1f)
    }

    /** Copia en [out] el nivel (0..1) más reciente anterior a [atNanos]. False si no hay datos frescos. */
    @Synchronized
    fun sample(atNanos: Long, out: FloatArray): Boolean {
        for (i in 0 until count) {
            val index = (head - 1 - i + SIZE) % SIZE
            if (times[index] <= atNanos) {
                if (atNanos - times[index] > 350_000_000L) return false
                out[0] = values[index * 3]
                out[1] = values[index * 3 + 1]
                out[2] = values[index * 3 + 2]
                return true
            }
        }
        return false
    }
}
