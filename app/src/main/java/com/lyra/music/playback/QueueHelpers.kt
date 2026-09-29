package com.lyra.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.LibraryRepository
import com.lyra.music.data.repo.MusicRepository
import com.lyra.music.data.settings.SettingsRepository
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

/** Radio infinita: cuando la cola se acaba, añade canciones parecidas a la última. */
class RadioController(
    private val player: Player,
    private val music: MusicRepository,
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var continuation: String? = null
    private var loading = false

    init {
        player.addListener(this)
    }

    /** Reproduce [seed] y rellena la cola con su radio. */
    fun start(seed: Song) {
        player.setMediaItems(listOf(seed.toMediaItem(downloads.localCover(seed.id))))
        player.prepare()
        player.play()
        continuation = null
        loading = true
        scope.launch {
            runCatching { music.radio(seed) }.onSuccess { page ->
                continuation = page.continuation
                append(page.songs.filterNot { it.id == seed.id })
            }
            loading = false
        }
    }

    /** Reproduce una playlist o mix de YouTube como radio (p. ej. la radio de un artista). */
    fun startFromPlaylist(playlistId: String) {
        loading = true
        scope.launch {
            runCatching { music.radioFromPlaylist(playlistId) }.onSuccess { page ->
                if (page.songs.isNotEmpty()) {
                    player.setMediaItems(page.songs.map { it.toMediaItem(downloads.localCover(it.id)) })
                    player.prepare()
                    player.play()
                    continuation = page.continuation
                }
            }
            loading = false
        }
    }

    /** Se ha puesto una cola nueva: la radio anterior ya no vale. */
    fun reset() {
        continuation = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) = maybeExtend()

    override fun onTimelineChanged(timeline: Timeline, reason: Int) {
        if (reason == Player.TIMELINE_CHANGE_REASON_PLAYLIST_CHANGED) maybeExtend()
    }

    private fun maybeExtend() {
        if (!settings.current.infiniteRadio || loading) return
        if (player.mediaItemCount == 0 || player.repeatMode != Player.REPEAT_MODE_OFF) return
        if (upcoming() > 2) return
        val last = player.getMediaItemAt(player.mediaItemCount - 1).toSong() ?: return
        loading = true
        scope.launch {
            val token = continuation
            val page = runCatching { if (token != null) music.radioMore(token) else music.radio(last) }.getOrNull()
            if (page != null) {
                continuation = page.continuation
                val added = append(page.songs)
                if (added == 0) continuation = null
            }
            loading = false
        }
    }

    private fun append(songs: List<Song>): Int {
        val existing = (0 until player.mediaItemCount).map { player.getMediaItemAt(it).mediaId }.toSet()
        val fresh = songs.filter { it.id !in existing }.distinctBy { it.id }.take(25)
        if (fresh.isNotEmpty()) player.addMediaItems(fresh.map { it.toMediaItem(downloads.localCover(it.id)) })
        return fresh.size
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
)

/** Guarda la cola en disco para retomarla al volver a abrir la app. */
class QueueStore(private val file: File, private val scope: CoroutineScope) {

    private val json = Json { ignoreUnknownKeys = true }
    private var pending: Job? = null

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
        return SavedQueue(
            songs = songs,
            index = (player.currentMediaItemIndex - start).coerceIn(0, (songs.size - 1).coerceAtLeast(0)),
            positionMs = player.currentPosition.coerceAtLeast(0),
            shuffle = player.shuffleModeEnabled,
            repeatMode = player.repeatMode,
        )
    }
}
