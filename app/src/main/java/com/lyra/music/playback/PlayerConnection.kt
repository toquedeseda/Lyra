package com.lyra.music.playback

import android.content.ComponentName
import android.content.Context
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.session.MediaController
import androidx.media3.session.SessionToken
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.model.Song
import com.lyra.music.playback.MediaItems.isRecommended
import com.lyra.music.playback.MediaItems.toMediaItem
import com.lyra.music.playback.MediaItems.toSong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json

data class PlayerUiState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val isBuffering: Boolean = false,
    val shuffle: Boolean = false,
    val repeatMode: Int = Player.REPEAT_MODE_OFF,
    val queue: List<Song> = emptyList(),
    val currentIndex: Int = -1,
    val hasNext: Boolean = false,
    val hasPrevious: Boolean = false,
    val error: String? = null,
    val playingFrom: String? = null,
    /** Canciones de la cola que ha metido el aleatorio inteligente. */
    val recommendedIds: Set<String> = emptySet(),
)

data class Progress(val positionMs: Long = 0, val durationMs: Long = 0, val bufferedMs: Long = 0)

/**
 * La interfaz habla con el servicio a través de un MediaController.
 * Todas las órdenes esperan a que la conexión esté lista, así que se pueden
 * llamar desde el primer instante.
 */
class PlayerConnection(
    private val context: Context,
    private val scope: CoroutineScope,
    private val downloads: DownloadRepository,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow(PlayerUiState())
    val state: StateFlow<PlayerUiState> = _state.asStateFlow()

    private val _progress = MutableStateFlow(Progress())
    val progress: StateFlow<Progress> = _progress.asStateFlow()

    private var controller: MediaController? = null
    private var ready = CompletableDeferred<MediaController>()
    private var ticker: Job? = null
    private var connecting = false

    fun connect() {
        if (controller != null || connecting) return
        connecting = true
        scope.launch {
            val token = SessionToken(context, ComponentName(context, PlaybackService::class.java))
            val built = runCatching { MediaController.Builder(context, token).buildAsync().await() }.getOrNull()
            connecting = false
            if (built == null) return@launch
            controller = built
            built.addListener(listener)
            refresh(built)
            refreshQueue(built)
            ready.complete(built)
        }
    }

    fun disconnect() {
        controller?.removeListener(listener)
        controller?.release()
        controller = null
        ticker?.cancel()
        ready = CompletableDeferred()
    }

    private suspend fun controller(): MediaController {
        connect()
        return ready.await()
    }

    private fun command(block: (MediaController) -> Unit) {
        scope.launch { block(controller()) }
    }

    // ------------------------------------------------------------- órdenes

    /** Reproduce una lista empezando por [startIndex]. [from] es el texto "Reproduciendo desde…". */
    fun play(songs: List<Song>, startIndex: Int = 0, shuffle: Boolean = false, from: String? = null) {
        if (songs.isEmpty()) return
        _state.update { it.copy(playingFrom = from) }
        command { c ->
            c.sendCustomCommand(LyraCommands.NEW_QUEUE, Bundle().apply { putBoolean(LyraCommands.ARG_SHUFFLE, shuffle) })
            val index = if (shuffle) songs.indices.random() else startIndex.coerceIn(0, songs.size - 1)
            c.shuffleModeEnabled = shuffle
            c.setMediaItems(songs.map { it.toMediaItem(downloads.localCover(it.id)) }, index, 0)
            c.prepare()
            c.play()
        }
    }

    fun playNext(songs: List<Song>) = command { c ->
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            val at = (c.currentMediaItemIndex + 1).coerceAtMost(c.mediaItemCount)
            c.addMediaItems(at, songs.map { it.toMediaItem(downloads.localCover(it.id)) })
        }
    }

    fun addToQueue(songs: List<Song>) = command { c ->
        if (c.mediaItemCount == 0) {
            play(songs)
        } else {
            c.addMediaItems(songs.map { it.toMediaItem(downloads.localCover(it.id)) })
        }
    }

    fun startRadio(song: Song) {
        _state.update { it.copy(playingFrom = "Radio de ${song.title}") }
        command { c ->
            val args = Bundle().apply { putString(LyraCommands.ARG_SONG, json.encodeToString(Song.serializer(), song)) }
            c.sendCustomCommand(LyraCommands.START_RADIO, args)
        }
    }

    fun startPlaylistRadio(playlistId: String, from: String) {
        _state.update { it.copy(playingFrom = from) }
        command { c ->
            c.sendCustomCommand(LyraCommands.PLAYLIST_RADIO, Bundle().apply { putString(LyraCommands.ARG_PLAYLIST, playlistId) })
        }
    }

    fun togglePlay() = command { c ->
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        if (c.playbackState == Player.STATE_ENDED) c.seekToDefaultPosition()
        if (c.isPlaying) c.pause() else c.play()
    }

    /**
     * Ejecuta [block] con el controlador ya conectado (widget, receptores…).
     * El MediaController solo admite llamadas desde el hilo principal.
     */
    suspend fun <T> withController(block: (MediaController) -> T): T =
        withContext(Dispatchers.Main.immediate) { block(controller()) }

    /** Sigue donde se quedó (al volver a conectar los auriculares con la app cerrada). */
    suspend fun resumeAfterReconnect() = withController { c ->
        if (c.mediaItemCount == 0 || c.playWhenReady) return@withController
        if (c.playbackState == Player.STATE_IDLE) c.prepare()
        c.play()
    }

    fun seekTo(positionMs: Long) = command { c ->
        c.seekTo(positionMs)
        _progress.update { it.copy(positionMs = positionMs) }
    }

    fun next() = command { it.seekToNext() }

    /** Como en Spotify: si han pasado más de 3 s, vuelve al principio de la canción. */
    fun previous() = command { c ->
        if (c.currentPosition > 3_000 || !c.hasPreviousMediaItem()) c.seekTo(0) else c.seekToPreviousMediaItem()
    }

    fun skipTo(index: Int) = command { c ->
        if (index in 0 until c.mediaItemCount) {
            c.seekToDefaultPosition(index)
            c.play()
        }
    }

    fun move(from: Int, to: Int) = command { c ->
        if (from in 0 until c.mediaItemCount && to in 0 until c.mediaItemCount) c.moveMediaItem(from, to)
    }

    fun remove(index: Int) = command { c ->
        if (index in 0 until c.mediaItemCount && index != c.currentMediaItemIndex) c.removeMediaItem(index)
    }

    fun clearUpcoming() = command { c ->
        val from = c.currentMediaItemIndex + 1
        if (from < c.mediaItemCount) c.removeMediaItems(from, c.mediaItemCount)
    }

    fun toggleShuffle() = command { it.shuffleModeEnabled = !it.shuffleModeEnabled }

    fun setShuffle(enabled: Boolean) = command { it.shuffleModeEnabled = enabled }

    fun cycleRepeat() = command { c ->
        c.repeatMode = when (c.repeatMode) {
            Player.REPEAT_MODE_OFF -> Player.REPEAT_MODE_ALL
            Player.REPEAT_MODE_ALL -> Player.REPEAT_MODE_ONE
            else -> Player.REPEAT_MODE_OFF
        }
    }

    fun toggleLikeCurrent() = command { it.sendCustomCommand(LyraCommands.LIKE, Bundle.EMPTY) }

    fun clearError() = _state.update { it.copy(error = null) }

    // ------------------------------------------------------------- estado

    private val listener = object : Player.Listener {
        override fun onEvents(player: Player, events: Player.Events) {
            val c = controller ?: return
            refresh(c)
            if (events.contains(Player.EVENT_TIMELINE_CHANGED) || events.contains(Player.EVENT_MEDIA_ITEM_TRANSITION) ||
                events.contains(Player.EVENT_SHUFFLE_MODE_ENABLED_CHANGED)
            ) {
                refreshQueue(c)
            }
        }

        override fun onPlayerError(error: PlaybackException) {
            _state.update { it.copy(error = friendlyError(error)) }
        }
    }

    private fun refresh(c: MediaController) {
        _state.update {
            it.copy(
                song = c.currentMediaItem?.toSong(),
                isPlaying = c.isPlaying,
                isBuffering = c.playbackState == Player.STATE_BUFFERING,
                shuffle = c.shuffleModeEnabled,
                repeatMode = c.repeatMode,
                currentIndex = c.currentMediaItemIndex,
                hasNext = c.hasNextMediaItem(),
                hasPrevious = c.hasPreviousMediaItem(),
                error = if (c.playerError == null) null else it.error,
            )
        }
        updateProgress(c)
        if (c.isPlaying) startTicker() else ticker?.cancel()
    }

    private fun refreshQueue(c: MediaController) {
        val items = (0 until c.mediaItemCount).map { index -> c.getMediaItemAt(index) }
        val songs = items.mapNotNull { it.toSong() }
        val recommended = items.filter { it.isRecommended() }.mapTo(HashSet()) { it.mediaId }
        _state.update { it.copy(queue = songs, currentIndex = c.currentMediaItemIndex, recommendedIds = recommended) }
    }

    /** Orden real de reproducción (tiene en cuenta el modo aleatorio). */
    fun upcomingIndices(): List<Int> {
        val c = controller ?: return emptyList()
        val timeline: Timeline = c.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val result = mutableListOf<Int>()
        var index = c.currentMediaItemIndex
        while (result.size < 500) {
            index = timeline.getNextWindowIndex(index, Player.REPEAT_MODE_OFF, c.shuffleModeEnabled)
            if (index == androidx.media3.common.C.INDEX_UNSET) break
            result += index
        }
        return result
    }

    private fun updateProgress(c: MediaController) {
        val duration = c.duration.takeIf { it > 0 } ?: c.currentMediaItem?.mediaMetadata?.durationMs ?: 0L
        _progress.value = Progress(c.currentPosition.coerceAtLeast(0), duration, c.bufferedPosition)
    }

    private fun startTicker() {
        if (ticker?.isActive == true) return
        ticker = scope.launch {
            while (isActive) {
                controller?.let(::updateProgress)
                delay(250)
            }
        }
    }

    private fun friendlyError(error: PlaybackException): String = when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT -> "Sin conexión. Las canciones descargadas sí funcionan."
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "La fuente rechazó la canción. Probando otra vez…"
        PlaybackException.ERROR_CODE_DECODING_FAILED,
        PlaybackException.ERROR_CODE_PARSING_CONTAINER_MALFORMED -> "El audio de esta canción está dañado."
        else -> error.cause?.message ?: "No se pudo reproducir esta canción"
    }

    val currentMediaItem: MediaItem? get() = controller?.currentMediaItem
}
