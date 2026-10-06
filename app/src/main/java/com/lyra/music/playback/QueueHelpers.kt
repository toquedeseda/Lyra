package com.lyra.music.playback

import android.os.Handler
import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.ShuffleOrder.DefaultShuffleOrder
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.LibraryRepository
import com.lyra.music.data.repo.MusicRepository
import com.lyra.music.data.settings.SettingsRepository
import com.lyra.music.data.repo.SongMatcher
import com.lyra.music.playback.MediaItems.isRadio
import com.lyra.music.playback.MediaItems.isRecommended
import com.lyra.music.playback.MediaItems.toMediaItem
import com.lyra.music.playback.MediaItems.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Radio infinita: cuando la cola se acaba, añade canciones parecidas a la última.
 *
 * - Las canciones de la radio van siempre DETRÁS de las de tu lista, también en
 *   aleatorio (antes se mezclaban con las de la playlist o "Me gusta").
 * - "Radio sin repetir": evita lo escuchado en las últimas 48 h, duplicados de la
 *   misma canción y dos canciones seguidas del mismo artista.
 */
class RadioController(
    private val player: ExoPlayer,
    private val music: MusicRepository,
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var continuation: String? = null
    private var loading = false

    /**
     * Sube con cada cola nueva. Lo que llegue tarde de una búsqueda de la cola anterior
     * se descarta (antes podía colar canciones de otra radio en la playlist recién puesta).
     */
    private var generation = 0
    private val handler = Handler(player.applicationLooper)

    init {
        player.addListener(this)
    }

    /** Reproduce [seed] y rellena la cola con su radio. */
    fun start(seed: Song) {
        val gen = ++generation
        continuation = null
        loading = true
        player.shuffleModeEnabled = false
        player.setMediaItems(listOf(seed.toMediaItem(downloads.localCover(seed.id))))
        player.prepare()
        player.play()
        scope.launch {
            runCatching { music.radio(seed) }.onSuccess { page ->
                if (gen != generation) return@onSuccess
                continuation = page.continuation
                append(page.songs.filterNot { it.id == seed.id })
            }
            if (gen == generation) loading = false
        }
    }

    /** Reproduce una playlist o mix de YouTube como radio (p. ej. la radio de un artista). */
    fun startFromPlaylist(playlistId: String) {
        val gen = ++generation
        continuation = null
        loading = true
        scope.launch {
            runCatching { music.radioFromPlaylist(playlistId) }.onSuccess { page ->
                if (gen != generation || page.songs.isEmpty()) return@onSuccess
                player.shuffleModeEnabled = false
                player.setMediaItems(page.songs.map { it.toMediaItem(downloads.localCover(it.id)) })
                player.prepare()
                player.play()
                continuation = page.continuation
            }
            if (gen == generation) loading = false
        }
    }

    /** Se ha puesto una cola nueva: la radio anterior ya no vale. */
    fun reset() {
        generation++
        continuation = null
        loading = false
    }

    // Se mira justo después de que el resto ordene la cola nueva (el aleatorio pone la actual
    // la primera): antes se miraba con el orden a medias y la radio arrancaba al empezar la lista.
    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        handler.post(::maybeExtend)
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) handler.post(::maybeExtend)
    }

    private fun maybeExtend() {
        if (!settings.current.infiniteRadio || loading) return
        if (player.mediaItemCount == 0 || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (upcoming() > 2) return
        // La semilla es la última canción que va a sonar (en aleatorio, la última del orden).
        val order = playOrder()
        val lastIndex = order.lastOrNull() ?: (player.mediaItemCount - 1)
        val last = player.getMediaItemAt(lastIndex).toSong() ?: return
        loading = true
        val gen = generation
        scope.launch {
            val token = continuation
            val page = runCatching { if (token != null) music.radioMore(token) else music.radio(last) }.getOrNull()
            if (gen != generation) return@launch
            if (page != null) {
                continuation = page.continuation
                val added = append(page.songs, radio = true)
                if (added == 0) continuation = null
            }
            loading = false
        }
    }

    /** Añade [candidates] al final. Con [radio], quedan marcadas como "de la radio" (no son de tu lista). */
    private suspend fun append(candidates: List<Song>, radio: Boolean = false): Int {
        val existingIds = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val existingKeys = (0 until player.mediaItemCount).mapNotNull { player.getMediaItemAt(it).toSong()?.let(::songKey) }.toSet()
        var fresh = candidates.filter { it.id !in existingIds && songKey(it) !in existingKeys }.distinctBy { songKey(it) }

        if (settings.current.radioNoRepeat) {
            val recent = runCatching { library.recentlyPlayedIds(48) }.getOrDefault(emptySet())
            val notRecent = fresh.filter { it.id !in recent }
            // Si casi todo es reciente, mejor repetir algo que quedarse sin música.
            if (notRecent.size >= 5 || notRecent.size == fresh.size) fresh = notRecent
            val lastArtist = playOrder().lastOrNull()?.let { player.getMediaItemAt(it).toSong()?.artists?.firstOrNull()?.name }
            fresh = spreadArtists(fresh, lastArtist)
        }
        fresh = fresh.take(25)
        if (fresh.isEmpty()) return 0

        // Orden de reproducción antes de añadir (tiene en cuenta el aleatorio).
        val before = playOrder()
        val firstNew = player.mediaItemCount
        player.addMediaItems(fresh.map { it.toMediaItem(downloads.localCover(it.id), radio = radio) })
        if (player.shuffleModeEnabled) {
            // Las nuevas, al final del orden aleatorio y en el orden de la radio.
            val order = before + (firstNew until player.mediaItemCount)
            if (order.size == player.mediaItemCount) {
                player.setShuffleOrder(DefaultShuffleOrder(order.toIntArray(), System.nanoTime()))
            }
        }
        return fresh.size
    }

    /** Orden real en el que van a sonar las canciones (índices), respetando el aleatorio. */
    private fun playOrder(): List<Int> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val shuffle = player.shuffleModeEnabled
        val order = mutableListOf<Int>()
        var index = timeline.getFirstWindowIndex(shuffle)
        while (index != C.INDEX_UNSET && order.size <= timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, shuffle)
        }
        return order
    }

    private fun upcoming(): Int {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return 0
        var index = player.currentMediaItemIndex
        var count = 0
        while (count < 3) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET) break
            count++
        }
        return count
    }

    companion object {
        fun songKey(song: Song): String = SongMatcher.songKey(song)

        fun spreadArtists(songs: List<Song>, previousArtist: String?): List<Song> = SongMatcher.spreadArtists(songs, previousArtist)
    }
}

/** Apunta en el historial lo que escuchas (a partir de 30 s o de la mitad de la canción). */
class PlaybackTracker(
    private val player: Player,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var current: Song? = player.currentMediaItem?.toSong()
    private var accumulatedMs = 0L
    private var playingSince: Long? = if (player.isPlaying) SystemClock.elapsedRealtime() else null

    init {
        player.addListener(this)
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying) {
            playingSince = SystemClock.elapsedRealtime()
        } else {
            accumulate()
        }
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        commit()
        current = mediaItem?.toSong()
        playingSince = if (player.isPlaying) SystemClock.elapsedRealtime() else null
    }

    private fun accumulate() {
        playingSince?.let { accumulatedMs += SystemClock.elapsedRealtime() - it }
        playingSince = if (player.isPlaying) SystemClock.elapsedRealtime() else null
    }

    fun commit() {
        accumulate()
        val song = current
        val played = accumulatedMs
        accumulatedMs = 0
        if (song == null) return
        val duration = song.durationMs ?: 0L
        if (played >= 30_000 || (duration > 0 && played >= duration / 2)) {
            scope.launch { runCatching { library.recordPlay(song, played) } }
        }
    }
}

@Serializable
data class SavedQueue(
    val songs: List<Song>,
    val index: Int,
    val positionMs: Long,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    /** Ids de las que metió el aleatorio inteligente. */
    val recommended: List<String> = emptyList(),
    /** Ids de las que añadió la radio al acabarse la lista. */
    val radio: List<String> = emptyList(),
    /** De dónde salía ("Me gusta", una playlist…) y su id. */
    val contextLabel: String? = null,
    val contextId: String? = null,
)

/** Guarda la cola en disco para retomarla al volver a abrir la app. */
class QueueStore(private val file: File, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private var pending: Job? = null

    /** Lo pone el servicio cada vez que cambia el origen de la cola. */
    @Volatile var contextLabel: String? = null
    @Volatile var contextId: String? = null

    fun load(): SavedQueue? = runCatching { json.decodeFromString(SavedQueue.serializer(), file.readText()) }.getOrNull()

    /** Guarda con un pequeño retraso para no escribir en cada cambio. */
    fun scheduleSave(player: Player, delayMs: Long = 1_500) {
        pending?.cancel()
        pending = scope.launch {
            delay(delayMs)
            save(player)
        }
    }

    suspend fun save(player: Player) {
        val snapshot = snapshot(player) ?: return
        withContext(Dispatchers.IO) {
            runCatching { file.writeText(json.encodeToString(SavedQueue.serializer(), snapshot)) }
        }
    }

    fun saveBlocking(player: Player) {
        val snapshot = snapshot(player) ?: return
        runCatching { file.writeText(json.encodeToString(SavedQueue.serializer(), snapshot)) }
    }

    private fun snapshot(player: Player): SavedQueue? {
        if (player.mediaItemCount == 0) return null
        // Como mucho 500 canciones alrededor de la actual.
        val start = (player.currentMediaItemIndex - 100).coerceAtLeast(0)
        val end = (start + 500).coerceAtMost(player.mediaItemCount)
        val songs = (start until end).mapNotNull { player.getMediaItemAt(it).toSong() }
        val items = (start until end).map { player.getMediaItemAt(it) }
        val recommended = items.filter { it.isRecommended() }.map { it.mediaId }
        val radio = items.filter { it.isRadio() }.map { it.mediaId }
        return SavedQueue(
            songs = songs,
            index = (player.currentMediaItemIndex - start).coerceIn(0, (songs.size - 1).coerceAtLeast(0)),
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
            recommended = recommended,
            radio = radio,
            contextLabel = contextLabel,
            contextId = contextId,
        )
    }
}
