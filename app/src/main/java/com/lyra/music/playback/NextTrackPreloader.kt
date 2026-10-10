package com.lyra.music.playback

import android.net.Uri
import android.os.SystemClock
import android.util.Log
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.Timeline
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.cache.CacheWriter
import androidx.media3.exoplayer.ExoPlayer
import com.lyra.music.BuildConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

/**
 * «Siguiente» al instante: en cuanto empieza una canción, las dos que van después se dejan
 * preparadas (su dirección de audio buscada y los primeros segundos en la caché). Si luego pasas
 * a ellas, suenan al momento en vez de tardar uno o dos segundos.
 *
 * Escuchando seguido no gasta datos de más (eso se iba a bajar igual); solo lo de las que te
 * saltas sin oír, y es poco (medio mega por canción).
 */
@UnstableApi
class NextTrackPreloader(
    private val player: ExoPlayer,
    private val dataSources: LyraDataSourceFactory,
    private val isOnline: () -> Boolean,
    private val scope: CoroutineScope,
) : Player.Listener {

    private var job: Job? = null
    @Volatile private var writer: CacheWriter? = null
    private var plannedIds: List<String> = emptyList()

    /** Para medir en pruebas lo que tarda en sonar tras cambiar de canción. */
    private var changedAt = 0L

    /** La canción actual ya suena: se puede empezar a preparar las siguientes sin quitarle red. */
    @Volatile private var playing = false

    init {
        player.addListener(this)
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        changedAt = SystemClock.uptimeMillis()
        playing = player.isPlaying
        plan()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        playing = isPlaying
        if (isPlaying && changedAt > 0) {
            debug("suena ${player.currentMediaItem?.mediaId} tras ${SystemClock.uptimeMillis() - changedAt} ms")
            changedAt = 0
        }
    }

    override fun onTimelineChanged(timeline: Timeline, reason: Int) = plan()

    override fun onShuffleModeEnabledChanged(shuffleModeEnabled: Boolean) = plan()

    override fun onRepeatModeChanged(repeatMode: Int) = plan()

    /** Las que vienen después (con aleatorio y repetir, en el orden de verdad). */
    private fun upcoming(): List<MediaItem> {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return emptyList()
        val items = mutableListOf<MediaItem>()
        var index = player.currentMediaItemIndex
        repeat(AHEAD) {
            index = timeline.getNextWindowIndex(index, player.repeatMode.takeIf { it != Player.REPEAT_MODE_ONE } ?: Player.REPEAT_MODE_OFF, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET || index == player.currentMediaItemIndex) return items
            items += player.getMediaItemAt(index)
        }
        return items
    }

    private fun plan() {
        val next = upcoming()
        val ids = next.map { it.mediaId }
        if (ids == plannedIds) return
        cancel()
        plannedIds = ids
        val uris = next.mapNotNull { it.localConfiguration?.uri }
        if (uris.isEmpty()) return
        job = scope.launch(Dispatchers.IO) {
            // Primero, que la canción que empieza suene (sin quitarle red); luego, enseguida.
            var waited = 0L
            while (!playing && waited < MAX_WAIT_MS) {
                delay(100)
                waited += 100
            }
            delay(START_DELAY_MS)
            if (!isOnline()) return@launch
            // Primero las direcciones de audio de las dos a la vez (es lo que más tarda)…
            val start = SystemClock.uptimeMillis()
            uris.map { uri -> async { runCatching { dataSources.resolveAhead(uri) } } }.awaitAll()
            debug("direcciones listas (${SystemClock.uptimeMillis() - start} ms)")
            // …y luego el principio de cada una, por orden.
            for (uri in uris) {
                if (!isActive || !isOnline()) return@launch
                prepare(uri)
            }
        }
    }

    private fun prepare(uri: Uri) {
        val cacheWriter = dataSources.precacher(uri, PRELOAD_BYTES) ?: return
        writer = cacheWriter
        val start = SystemClock.uptimeMillis()
        runCatching { cacheWriter.cache() }
            .onSuccess { debug("lista ${MediaItems.songIdFrom(uri)} (${SystemClock.uptimeMillis() - start} ms)") }
            .onFailure { debug("no se pudo preparar ${MediaItems.songIdFrom(uri)}: ${it.message}") }
        writer = null
    }

    private fun cancel() {
        writer?.cancel()
        writer = null
        job?.cancel()
        job = null
    }

    fun release() {
        cancel()
        player.removeListener(this)
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d("LyraPrecarga", message)
    }

    private companion object {
        /** Cuántas canciones por delante se preparan. */
        const val AHEAD = 2

        /** Unos 15 segundos de audio: de sobra para empezar a sonar mientras llega el resto. */
        const val PRELOAD_BYTES = 256L * 1024

        const val START_DELAY_MS = 300L
        /** Si la actual tarda en sonar (red lenta, en pausa), no se le quita red: se espera bastante. */
        const val MAX_WAIT_MS = 10_000L
    }
}
