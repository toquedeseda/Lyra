package com.lyra.desktop.player

import com.lyra.desktop.ErrorLog
import com.lyra.desktop.writeTextSafely
import com.lyra.desktop.audio.AudioCache
import com.lyra.desktop.audio.AudioEngine
import com.lyra.desktop.audio.AudioFx
import com.lyra.desktop.audio.AudioInput
import com.lyra.desktop.audio.FfmpegDecoder
import com.lyra.desktop.audio.FileInput
import com.lyra.desktop.audio.LoudnessNormalizer
import com.lyra.desktop.audio.PlayingTrack
import com.lyra.desktop.audio.RemoteStream
import com.lyra.desktop.audio.StreamResolver
import com.lyra.desktop.audio.UnreadableAudioException
import com.lyra.desktop.data.DesktopSettings
import com.lyra.desktop.data.Library
import com.lyra.desktop.data.RepeatMode
import com.lyra.desktop.data.SettingsStore
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.MusicRepository
import com.lyra.music.data.repo.SongMatcher
import com.lyra.music.playback.LoudnessMemory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.atomic.AtomicLong

/** Una canción de la cola. [radio]: la añadió la radio infinita (va detrás de tu lista). */
@Serializable
data class QueueItem(val uid: Long, val song: Song, val radio: Boolean = false)

/** De dónde viene lo que suena («Reproduciendo desde…»). */
@Serializable
data class PlayContext(val label: String, val id: String? = null)

data class PlayerState(
    val queue: List<QueueItem> = emptyList(),
    val index: Int = -1,
    val isPlaying: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    val context: PlayContext? = null,
    /** Cuántas puestas a mano («Añadir a la cola») van justo detrás de la actual. */
    val manualCount: Int = 0,
    val radioLoading: Boolean = false,
) {
    val current: QueueItem? get() = queue.getOrNull(index)
    val upcoming: List<QueueItem> get() = if (index < 0) queue else queue.drop(index + 1)
}

@Serializable
private data class SavedQueue(
    val queue: List<QueueItem>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean,
    val original: List<QueueItem>? = null,
    val context: PlayContext? = null,
    val manualCount: Int = 0,
)

/**
 * El reproductor de Lyra en el PC: la cola (con aleatorio, repetir y "añadir a la cola"), la
 * radio infinita, el paso sin cortes a la siguiente y el historial. Todo lo de la cola se hace
 * en un único hilo para que nunca se pisen los cambios.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PlayerController(
    private val scope: CoroutineScope,
    private val settings: SettingsStore,
    private val library: Library,
    private val music: MusicRepository,
    private val resolver: StreamResolver,
    private val cache: AudioCache,
    private val localFile: (String) -> File?,
    private val queueFile: File,
    /** Lo fuerte que suena cada canción ya escuchada (para igualar el volumen desde el principio). */
    private val loudness: LoudnessMemory? = null,
) : AudioEngine.Events {

    private val confined = Dispatchers.Default.limitedParallelism(1)
    private val json = Json { ignoreUnknownKeys = true }
    private val uids = AtomicLong(1)
    private val trackIds = AtomicLong(1)
    val engine = AudioEngine(this)

    private val _state = MutableStateFlow(PlayerState())
    val state: StateFlow<PlayerState> = _state.asStateFlow()

    private val _messages = MutableSharedFlow<String>(extraBufferCapacity = 8)

    /** Avisos para enseñar abajo («No se pudo reproducir…»). */
    val messages: SharedFlow<String> = _messages

    /** Orden original de la cola antes de pulsar aleatorio (para deshacerlo). */
    private var original: List<QueueItem>? = null
    private var radioContinuation: String? = null
    private var radioGeneration = 0
    private var consecutiveFailures = 0
    private var listenedMs = 0L
    private var listenedUid = -1L
    private var saveJob: Job? = null

    /** La cola se acabó esperando a la radio: en cuanto lleguen canciones, sigue sola. */
    private var advanceWhenMore = false

    /** La ganancia del volumen igualado con la que acabó la última canción (la siguiente parte de ahí). */
    @Volatile private var lastGainDb = 0f

    init {
        scope.launch(confined) {
            settings.flow.collect(::applySettings)
        }
        // Cuenta lo escuchado (para el historial) y guarda por dónde vas.
        scope.launch(confined) {
            var ticks = 0
            while (isActive) {
                delay(1_000)
                val s = _state.value
                val item = s.current ?: continue
                if (s.isPlaying && !engine.buffering) {
                    if (listenedUid != item.uid) {
                        listenedUid = item.uid
                        listenedMs = 0
                    }
                    listenedMs += 1_000
                }
                if (s.isPlaying && ++ticks % 10 == 0) saveQueueNow()
            }
        }
    }

    private fun applySettings(s: DesktopSettings) {
        engine.fx = AudioFx(s.eqEnabled, s.eqBands, s.eqPreamp, s.normalizeVolume)
        engine.volume = s.volume
        engine.muted = s.muted
        updateCrossfade()
    }

    val positionMs: Long get() = if (engine.currentTrack != null) engine.positionMs else _lastPosition

    /** Duración de lo que suena (la del archivo si ya se sabe; si no, la de la ficha). */
    val durationMs: Long
        get() = engine.currentTrack?.durationMs ?: _state.value.current?.song?.durationMs ?: 0L

    val buffering: Boolean get() = engine.buffering

    private fun newItems(songs: List<Song>, radio: Boolean = false) = songs.map { QueueItem(uids.getAndIncrement(), it, radio) }

    // ------------------------------------------------------------------ poner música

    /** Pone [songs] empezando por [startIndex]. Con [shuffle], en orden aleatorio. */
    fun play(songs: List<Song>, startIndex: Int = 0, context: PlayContext? = null, shuffle: Boolean? = null, from: MusicItem? = null) {
        if (songs.isEmpty()) return
        from?.let(library::touchContext)
        scope.launch(confined) {
            radioGeneration++
            radioContinuation = null
            advanceWhenMore = false
            library.remember(songs)
            val items = newItems(songs)
            val useShuffle = shuffle ?: _state.value.shuffle
            val start = startIndex.coerceIn(0, items.size - 1)
            val queue: List<QueueItem>
            if (useShuffle) {
                original = items
                // La elegida primero (o una al azar si se pulsó "aleatorio") y el resto mezclado.
                val first = if (shuffle == true && startIndex == 0) items.random() else items[start]
                queue = listOf(first) + (items - first).shuffled()
            } else {
                original = null
                queue = items
            }
            _state.update {
                it.copy(queue = queue, index = if (useShuffle) 0 else start, shuffle = useShuffle, context = context, manualCount = 0, radioLoading = false)
            }
            settings.update { it.copy(shuffle = useShuffle) }
            startCurrent(0, autoplay = true)
        }
    }

    /** Radio de una canción: suena ella y detrás canciones parecidas. */
    fun startRadio(seed: Song, label: String = "Radio de ${seed.title}") {
        scope.launch(confined) {
            val gen = ++radioGeneration
            radioContinuation = null
            advanceWhenMore = false
            original = null
            library.remember(listOf(seed))
            _state.update {
                it.copy(queue = newItems(listOf(seed)), index = 0, shuffle = false, context = PlayContext(label), manualCount = 0, radioLoading = true)
            }
            startCurrent(0, autoplay = true)
            val page = runCatching { music.radio(seed) }.getOrNull()
            if (gen != radioGeneration) return@launch
            if (page != null) {
                radioContinuation = page.continuation
                append(page.songs.filterNot { it.id == seed.id }, radio = false)
            }
            _state.update { it.copy(radioLoading = false) }
        }
    }

    /** Una playlist o mix de YouTube como radio (p. ej. la radio de un artista). */
    fun startPlaylistRadio(playlistId: String, label: String) {
        scope.launch(confined) {
            val gen = ++radioGeneration
            _state.update { it.copy(radioLoading = true) }
            val page = runCatching { music.radioFromPlaylist(playlistId) }.getOrNull()
            if (gen != radioGeneration) return@launch
            if (page == null || page.songs.isEmpty()) {
                _state.update { it.copy(radioLoading = false) }
                _messages.tryEmit("No se pudo poner esa radio")
                return@launch
            }
            radioContinuation = page.continuation
            original = null
            library.remember(page.songs)
            _state.update {
                it.copy(queue = newItems(page.songs), index = 0, shuffle = false, context = PlayContext(label), manualCount = 0, radioLoading = false)
            }
            startCurrent(0, autoplay = true)
        }
    }

    /** Justo después de la que suena. */
    fun playNext(songs: List<Song>) = scope.launch(confined) {
        if (songs.isEmpty()) return@launch
        library.remember(songs)
        val s = _state.value
        if (s.current == null) {
            play(songs)
            return@launch
        }
        val items = newItems(songs)
        val at = s.index + 1
        _state.update { it.copy(queue = it.queue.take(at) + items + it.queue.drop(at), manualCount = it.manualCount + items.size) }
        original = original?.let { it + items }
        afterQueueChange()
        _messages.tryEmit(if (songs.size == 1) "«${songs.first().title}» sonará a continuación" else "${songs.size} canciones sonarán a continuación")
    }

    /** Al final de lo añadido a mano (antes que el resto de la lista), como en Spotify. */
    fun addToQueue(songs: List<Song>) = scope.launch(confined) {
        if (songs.isEmpty()) return@launch
        library.remember(songs)
        val s = _state.value
        if (s.current == null) {
            play(songs)
            return@launch
        }
        val items = newItems(songs)
        val at = s.index + 1 + s.manualCount
        _state.update { it.copy(queue = it.queue.take(at) + items + it.queue.drop(at), manualCount = it.manualCount + items.size) }
        original = original?.let { it + items }
        afterQueueChange()
        _messages.tryEmit(if (songs.size == 1) "Añadida a la cola" else "${songs.size} canciones añadidas a la cola")
    }

    fun removeFromQueue(uid: Long) = scope.launch(confined) {
        val s = _state.value
        val position = s.queue.indexOfFirst { it.uid == uid }
        if (position < 0 || position == s.index) return@launch
        val manual = if (position in (s.index + 1)..(s.index + s.manualCount)) s.manualCount - 1 else s.manualCount
        _state.update { it.copy(queue = it.queue.filterNot { q -> q.uid == uid }, index = if (position < it.index) it.index - 1 else it.index, manualCount = manual) }
        original = original?.filterNot { it.uid == uid }
        afterQueueChange()
    }

    /** Mueve una de las que vienen después (posiciones dentro de la cola completa). */
    fun moveInQueue(from: Int, to: Int) = scope.launch(confined) {
        val s = _state.value
        if (from == to || from <= s.index || to <= s.index || from !in s.queue.indices || to !in s.queue.indices) return@launch
        val list = s.queue.toMutableList()
        list.add(to, list.removeAt(from))
        _state.update { it.copy(queue = list) }
        afterQueueChange()
    }

    fun clearUpcoming() = scope.launch(confined) {
        val s = _state.value
        if (s.current == null) return@launch
        _state.update { it.copy(queue = it.queue.take(it.index + 1), manualCount = 0) }
        original = null
        afterQueueChange()
    }

    /** Salta a una canción de la cola. */
    fun playAt(uid: Long) = scope.launch(confined) {
        val s = _state.value
        val position = s.queue.indexOfFirst { it.uid == uid }
        if (position < 0) return@launch
        val manual = (s.manualCount - (position - s.index).coerceAtLeast(0)).coerceAtLeast(0)
        advanceWhenMore = false
        _state.update { it.copy(index = position, manualCount = if (position > s.index) manual else it.manualCount) }
        startCurrent(0, autoplay = true)
    }

    // ------------------------------------------------------------------ controles

    fun togglePlay() = scope.launch(confined) {
        val s = _state.value
        when {
            s.current == null -> Unit
            s.isPlaying -> pauseNow()
            engine.currentTrack == null -> startCurrent(_lastPosition, autoplay = true)
            else -> resumeNow()
        }
    }

    fun pause() = scope.launch(confined) { pauseNow() }

    fun resume() = scope.launch(confined) {
        if (engine.currentTrack == null) startCurrent(_lastPosition, autoplay = true) else resumeNow()
    }

    private fun pauseNow() {
        _lastPosition = engine.positionMs
        engine.pause()
        _state.update { it.copy(isPlaying = false) }
        saveQueueSoon()
    }

    private fun resumeNow() {
        if (_state.value.current == null) return
        engine.resume()
        _state.update { it.copy(isPlaying = true) }
    }

    private var _lastPosition = 0L

    fun next() = scope.launch(confined) {
        val s = _state.value
        if (s.queue.isEmpty()) return@launch
        commitListening()
        val target = s.index + 1
        if (target <= s.queue.lastIndex) {
            _state.update { it.copy(index = target, manualCount = (it.manualCount - 1).coerceAtLeast(0)) }
            startCurrent(0, autoplay = true)
        } else if (s.repeat == RepeatMode.ALL) {
            _state.update { it.copy(index = 0, manualCount = 0) }
            startCurrent(0, autoplay = true)
        } else if (settings.current.infiniteRadio) {
            // Fin de la cola: en cuanto la radio traiga más, sigue.
            advanceWhenMore = true
            maybeExtendRadio()
        }
    }

    /** Si va por más de 3 s, vuelve al principio; si no, a la anterior. */
    fun previous() = scope.launch(confined) {
        val s = _state.value
        if (s.current == null) return@launch
        if (engine.positionMs > 3_000 || s.index == 0) {
            seekNow(0)
            return@launch
        }
        commitListening()
        _state.update { it.copy(index = it.index - 1) }
        startCurrent(0, autoplay = true)
    }

    fun seek(positionMs: Long) = scope.launch(confined) { seekNow(positionMs) }

    private fun seekNow(positionMs: Long) {
        if (engine.currentTrack == null) {
            _lastPosition = positionMs
            if (_state.value.current != null) startCurrent(positionMs, autoplay = _state.value.isPlaying)
        } else {
            engine.seek(positionMs)
        }
    }

    fun setShuffle(enabled: Boolean) = scope.launch(confined) {
        val s = _state.value
        if (s.shuffle == enabled) return@launch
        val current = s.current
        if (current == null) {
            _state.update { it.copy(shuffle = enabled) }
            settings.update { it.copy(shuffle = enabled) }
            return@launch
        }
        if (enabled) {
            original = s.queue
            // Las de la radio siguen al final; lo puesto a mano, justo detrás.
            val manual = s.queue.drop(s.index + 1).take(s.manualCount)
            val rest = s.queue.drop(s.index + 1 + s.manualCount)
            val upcoming = rest.filterNot { it.radio }.shuffled() + rest.filter { it.radio }
            val played = s.queue.take(s.index)
            _state.update { it.copy(queue = played + current + manual + upcoming, shuffle = true) }
        } else {
            val base = original ?: s.queue
            val added = s.queue.filterNot { q -> base.any { it.uid == q.uid } }
            val restored = base + added
            _state.update { it.copy(queue = restored, index = restored.indexOfFirst { q -> q.uid == current.uid }.coerceAtLeast(0), shuffle = false, manualCount = 0) }
            original = null
        }
        settings.update { it.copy(shuffle = enabled) }
        afterQueueChange()
    }

    fun cycleRepeat() = scope.launch(confined) {
        val next = when (_state.value.repeat) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
        _state.update { it.copy(repeat = next) }
        settings.update { it.copy(repeat = next) }
        afterQueueChange()
    }

    // ------------------------------------------------------------------ pistas y motor de audio

    /** Pone a sonar la canción de la posición actual. */
    private fun startCurrent(startMs: Long, autoplay: Boolean) {
        val item = _state.value.current ?: return
        advanceWhenMore = false
        rememberLoudness(engine.currentTrack)
        commitListening()
        listenedUid = item.uid
        listenedMs = 0
        val track = trackFor(item, startMs)
        engine.play(track, startPaused = !autoplay)
        _lastPosition = startMs
        _state.update { it.copy(isPlaying = autoplay) }
        afterQueueChange()
    }

    private fun trackFor(item: QueueItem, startMs: Long = 0): PlayingTrack {
        // Si ya se escuchó, se sabe lo fuerte que suena; si no, se empieza con la ganancia de la anterior.
        val normalizer = LoudnessNormalizer(
            FfmpegDecoder.SAMPLE_RATE,
            knownDb = loudness?.get(item.song.id) ?: Float.NaN,
            startGainDb = engine.currentTrack?.normalizer?.gainDb ?: lastGainDb,
        )
        return PlayingTrack(trackIds.getAndIncrement(), item, item.song.durationMs, startMs, normalizer) { openDecoder(item.song) }
    }

    /** Apunta lo fuerte que sonó [track] (si se escuchó lo bastante) para la próxima vez. */
    private fun rememberLoudness(track: PlayingTrack?) {
        val item = track?.tag as? QueueItem ?: return
        val normalizer = track.normalizer
        lastGainDb = normalizer.gainDb
        val memory = loudness ?: return
        val seconds = item.song.durationMs?.let { it / 1000f }
        if (memory.remember(item.song.id, normalizer.measuredDb, normalizer.measuredSeconds, seconds)) {
            scope.launch(Dispatchers.IO) { memory.save() }
        }
    }

    /** Descargada → caché → YouTube/SoundCloud (bajándose a la caché mientras suena). */
    private fun openDecoder(song: Song): FfmpegDecoder {
        val input: AudioInput = localFile(song.id)?.let(::FileInput)
            ?: cache.completeFile(song.id)?.let(::FileInput)
            ?: run {
                val first = resolver.resolveBlocking(song.id, song)
                if (first.file != null) {
                    // SoundCloud en trozos: ya está entera en la caché; que no pase del límite.
                    cache.trim()
                    FileInput(first.file)
                } else {
                    cache.open(song.id) { refresh ->
                        if (refresh) resolver.invalidate(song.id)
                        val resolved = resolver.resolveBlocking(song.id, song)
                        RemoteStream(resolved.url ?: throw java.io.IOException("Sin dirección de audio"), resolved.contentLength)
                    }
                }
            }
        return try {
            FfmpegDecoder(input)
        } catch (e: Throwable) {
            runCatching { input.close() }
            throw e
        }
    }

    /** La siguiente que va a sonar, ya preparada (sin hueco y con crossfade si toca). */
    private fun afterQueueChange() {
        val s = _state.value
        val wanted: QueueItem? = when {
            s.current == null -> null
            s.repeat == RepeatMode.ONE -> s.current
            s.index + 1 <= s.queue.lastIndex -> s.queue[s.index + 1]
            s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> s.queue[0]
            else -> null
        }
        val prepared = engine.nextTrack
        val preparedItem = prepared?.tag as? QueueItem
        if (wanted == null) {
            if (prepared != null) engine.prepareNext(null)
        } else if (preparedItem?.uid != wanted.uid || (s.repeat == RepeatMode.ONE && prepared === engine.currentTrack)) {
            engine.prepareNext(trackFor(wanted))
        }
        updateCrossfade()
        maybeExtendRadio()
        saveQueueSoon()
    }

    /** Fundido entre canciones como en Spotify: los últimos segundos de cada una con la siguiente. */
    private fun updateCrossfade() {
        val seconds = settings.current.crossfadeSeconds
        engine.crossfadeMs = if (seconds <= 0 || _state.value.repeat == RepeatMode.ONE) 0 else seconds * 1_000
    }

    override fun started(track: PlayingTrack) {
        scope.launch(confined) {
            consecutiveFailures = 0
            val item = track.tag as? QueueItem ?: return@launch
            val s = _state.value
            val position = s.queue.indexOfFirst { it.uid == item.uid }
            commitListening()
            listenedUid = item.uid
            listenedMs = 0
            if (position >= 0) {
                val manual = if (position == s.index + 1) (s.manualCount - 1).coerceAtLeast(0) else s.manualCount
                _state.update { it.copy(index = position, manualCount = manual) }
            }
            afterQueueChange()
        }
    }

    override fun ended(track: PlayingTrack) {
        scope.launch(confined) {
            rememberLoudness(track)
            if (engine.currentTrack != null) return@launch // ya suena la siguiente
            commitListening()
            val s = _state.value
            when {
                s.repeat == RepeatMode.ONE -> startCurrent(0, autoplay = true)
                s.index + 1 <= s.queue.lastIndex -> {
                    _state.update { it.copy(index = it.index + 1, manualCount = (it.manualCount - 1).coerceAtLeast(0)) }
                    startCurrent(0, autoplay = true)
                }
                s.repeat == RepeatMode.ALL && s.queue.isNotEmpty() -> {
                    _state.update { it.copy(index = 0, manualCount = 0) }
                    startCurrent(0, autoplay = true)
                }
                else -> {
                    // Se acabó la cola: se queda la última, parada y al principio…
                    _lastPosition = 0
                    _state.update { it.copy(isPlaying = false) }
                    // …salvo que la radio infinita traiga más.
                    if (settings.current.infiniteRadio) {
                        advanceWhenMore = true
                        maybeExtendRadio()
                    }
                }
            }
        }
    }

    override fun failed(track: PlayingTrack, error: Throwable) {
        scope.launch(confined) {
            val item = track.tag as? QueueItem ?: return@launch
            if (_state.value.current?.uid != item.uid) return@launch
            val song = item.song
            // Audio en un formato que no se lee bien: se pide otra vez en M4A desde donde iba.
            if (error is UnreadableAudioException && localFile(song.id) == null && resolver.preferPortable(song.id)) {
                cache.remove(song.id)
                if (_state.value.current?.uid != item.uid) return@launch // mientras, se cambió de canción
                startCurrent(track.positionFrames * 1000 / FfmpegDecoder.SAMPLE_RATE, autoplay = true)
                return@launch
            }
            ErrorLog.record("Reproducir", "«${song.title}»: ${error.message ?: "error"}", error, extra = song.id)
            _messages.tryEmit("No se pudo reproducir «${song.title}». ${error.message ?: ""}".trim())
            consecutiveFailures++
            if (consecutiveFailures >= 5) {
                // Si fallan varias seguidas (sin red, YouTube frenando…), mejor parar.
                consecutiveFailures = 0
                _state.update { it.copy(isPlaying = false) }
                engine.stop()
                return@launch
            }
            delay(800)
            val s = _state.value
            if (s.current?.uid != item.uid) return@launch
            if (s.index + 1 <= s.queue.lastIndex) {
                _state.update { it.copy(index = it.index + 1, manualCount = (it.manualCount - 1).coerceAtLeast(0)) }
                startCurrent(0, autoplay = true)
            } else if (s.repeat == RepeatMode.ALL && s.queue.size > 1) {
                _state.update { it.copy(index = 0, manualCount = 0) }
                startCurrent(0, autoplay = true)
            } else {
                _state.update { it.copy(isPlaying = false) }
                engine.stop()
                // Era la última: con la radio infinita, sigue con lo que traiga.
                if (settings.current.infiniteRadio && s.repeat == RepeatMode.OFF) {
                    advanceWhenMore = true
                    maybeExtendRadio()
                }
            }
        }
    }

    /** Apunta en el historial lo escuchado de la anterior (a partir de 30 s o la mitad). */
    private fun commitListening() {
        val uid = listenedUid
        val played = listenedMs
        listenedUid = -1
        listenedMs = 0
        if (uid < 0 || played <= 0) return
        val item = _state.value.queue.firstOrNull { it.uid == uid } ?: return
        val duration = item.song.durationMs ?: 0L
        if (played >= 30_000 || (duration > 0 && played >= duration / 2)) library.recordPlay(item.song, played)
    }

    // ------------------------------------------------------------------ radio infinita

    private fun maybeExtendRadio() {
        val s = _state.value
        if (!settings.current.infiniteRadio || s.radioLoading || s.current == null || s.repeat != RepeatMode.OFF) return
        if (s.queue.size - 1 - s.index > 2) return
        val seed = s.queue.last().song
        val gen = radioGeneration
        _state.update { it.copy(radioLoading = true) }
        scope.launch(confined) {
            val token = radioContinuation
            val page = runCatching { if (token != null) music.radioMore(token) else music.radio(seed) }.getOrNull()
            if (gen == radioGeneration) {
                if (page != null) {
                    radioContinuation = page.continuation
                    if (append(page.songs, radio = true) == 0) radioContinuation = null
                }
                _state.update { it.copy(radioLoading = false) }
            }
        }
    }

    /** Añade al final lo nuevo (sin repetidas; con "radio sin repetir", sin lo de las últimas 48 h). */
    private suspend fun append(candidates: List<Song>, radio: Boolean): Int = withContext(confined) {
        val s = _state.value
        val existingIds = s.queue.map { it.song.id }.toSet()
        val existingKeys = s.queue.map { SongMatcher.songKey(it.song) }.toSet()
        var fresh = candidates.filter { it.id !in existingIds && SongMatcher.songKey(it) !in existingKeys }.distinctBy { SongMatcher.songKey(it) }
        if (settings.current.radioNoRepeat) {
            val recent = library.recentlyPlayedIds(48)
            val notRecent = fresh.filter { it.id !in recent }
            if (notRecent.size >= 5 || notRecent.size == fresh.size) fresh = notRecent
            fresh = SongMatcher.spreadArtists(fresh, s.queue.lastOrNull()?.song?.artists?.firstOrNull()?.name)
        }
        fresh = fresh.take(25)
        if (fresh.isEmpty()) return@withContext 0
        library.remember(fresh)
        val items = newItems(fresh, radio)
        _state.update { it.copy(queue = it.queue + items) }
        original = original?.let { it + items }
        // Se acabó la cola (o se pulsó «siguiente» en la última) esperando a la radio: sigue ya.
        if (advanceWhenMore) {
            advanceWhenMore = false
            if (_state.value.index + 1 <= _state.value.queue.lastIndex) {
                _state.update { it.copy(index = it.index + 1) }
                startCurrent(0, autoplay = true)
                return@withContext fresh.size
            }
        }
        afterQueueChange()
        fresh.size
    }

    // ------------------------------------------------------------------ guardar y recuperar la cola

    private fun saveQueueSoon() {
        saveJob?.cancel()
        saveJob = scope.launch(confined) {
            delay(1_500)
            saveQueueNow()
        }
    }

    private fun saveQueueNow() {
        val saved = snapshot() ?: return
        scope.launch(Dispatchers.IO) { writeQueue(saved) }
    }

    /**
     * Lo que se guarda de la cola. Con colas enormes (p. ej. 3000 Me gusta), hasta 1000 canciones
     * alrededor de la que suena, para que al volver a abrir siga siendo la misma.
     */
    private fun snapshot(): SavedQueue? {
        val s = _state.value
        if (s.queue.isEmpty()) return null
        val range = savedRange(s.queue.size, s.index)
        val window = s.queue.slice(range)
        val kept = if (window.size == s.queue.size) null else window.mapTo(HashSet()) { it.uid }
        return SavedQueue(
            queue = window,
            index = (s.index - range.first).coerceIn(0, window.lastIndex),
            positionMs = positionMs,
            shuffle = s.shuffle,
            original = original?.let { o -> if (kept == null) o else o.filter { it.uid in kept } },
            context = s.context,
            manualCount = s.manualCount,
        )
    }

    private fun writeQueue(saved: SavedQueue) = synchronized(queueFile) {
        runCatching { queueFile.writeTextSafely(json.encodeToString(SavedQueue.serializer(), saved)) }
    }

    /** Al abrir Lyra: la cola de la última vez, parada donde se quedó. */
    fun restore() = scope.launch(confined) {
        val saved = withContext(Dispatchers.IO) {
            runCatching { json.decodeFromString(SavedQueue.serializer(), queueFile.readText()) }.getOrNull()
        }
        if (saved == null || saved.queue.isEmpty()) {
            _state.update { it.copy(shuffle = settings.current.shuffle, repeat = settings.current.repeat) }
            return@launch
        }
        val maxUid = saved.queue.maxOf { it.uid }
        uids.set(maxUid + 1)
        original = saved.original
        _lastPosition = saved.positionMs
        _state.update {
            it.copy(
                queue = saved.queue,
                index = saved.index.coerceIn(0, saved.queue.lastIndex),
                shuffle = saved.shuffle,
                repeat = settings.current.repeat,
                context = saved.context,
                manualCount = saved.manualCount,
                isPlaying = false,
            )
        }
    }

    fun shutdown() {
        commitListening()
        rememberLoudness(engine.currentTrack)
        loudness?.save()
        saveQueueNowBlocking()
        engine.stop()
    }

    private fun saveQueueNowBlocking() {
        snapshot()?.let(::writeQueue)
    }
}

/** Qué parte de una cola de [size] canciones se guarda: hasta [max] alrededor de [index] (unas pocas de antes). */
internal fun savedRange(size: Int, index: Int, max: Int = 1_000, before: Int = 100): IntRange {
    val from = (index - before).coerceIn(0, (size - max).coerceAtLeast(0))
    return from until minOf(size, from + max)
}
