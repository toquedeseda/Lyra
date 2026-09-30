package com.lyra.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
import com.lyra.music.playback.MediaItems.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/**
 * Crossfade: la canción que termina sigue sonando en un reproductor auxiliar
 * mientras baja, y el principal ya ha pasado a la siguiente y sube.
 *
 * El reproductor principal es el único que ve la sesión (notificación, Android
 * Auto, cola…), así que para el resto de la app el cambio de canción es normal.
 */
@UnstableApi
class CrossfadeController(
    private val main: ExoPlayer,
    private val tailFactory: () -> ExoPlayer,
    private val scope: CoroutineScope,
    private val onNewTrack: () -> Unit,
    private val loudness: () -> LyraAudioProcessor.Loudness? = { null },
) : Player.Listener {

    /** Duración del crossfade en ms (0 = desactivado). */
    var durationMs: Long = 6_000

    /**
     * Crossfade inteligente: no mezcla dos pistas seguidas de un mismo álbum (hay
     * discos que van enlazados) y empieza la mezcla cuando la canción acaba de
     * verdad, al detectar su fundido o el silencio final, en vez de a tiempo fijo.
     */
    var smart: Boolean = true

    private var quietSinceNanos = 0L
    private var albumCheckKey: String? = null
    private var albumCheck = false

    private var tail: ExoPlayer? = null
    private var tailItemId: String? = null
    private var tailStartMs = 0L
    private var fadeJob: Job? = null
    private var internalTransition = false

    private val pollJob: Job = scope.launch {
        while (isActive) {
            val nearEnd = tick()
            delay(if (nearEnd) 40 else 250)
        }
    }

    init {
        main.addListener(this)
    }

    val isFading: Boolean get() = fadeJob?.isActive == true

    /** Devuelve true cuando queda poco para el punto de mezcla (para vigilar más a menudo). */
    private fun tick(): Boolean {
        if (durationMs <= 0 || isFading) return false
        if (!main.isPlaying || main.repeatMode == Player.REPEAT_MODE_ONE || !main.hasNextMediaItem()) {
            releaseTail()
            return false
        }
        val duration = main.duration
        if (duration == C.TIME_UNSET || duration < 20_000) return false
        if (smart && isContinuousAlbum()) {
            releaseTail()
            return false
        }
        val fade = durationMs.coerceAtMost(duration / 4)
        val position = main.currentPosition
        val remaining = duration - position
        val item = main.currentMediaItem ?: return false
        // En modo inteligente se vigila un poco antes por si la canción acaba antes de tiempo.
        val window = if (smart) fade + OUTRO_WINDOW_MS else fade

        if (remaining <= window + PREPARE_AHEAD_MS && tail == null) {
            prepareTail(item, duration - window)
        }
        if (smart && remaining in 1_500..window && outroStarted()) {
            startFade(minOf(fade, remaining))
            return false
        }
        if (remaining <= fade && remaining > 400) {
            startFade(remaining)
            return false
        }
        return remaining <= window + 1_500
    }

    /** La canción se ha quedado claramente por debajo de su volumen habitual durante un rato. */
    private fun outroStarted(): Boolean {
        val now = System.nanoTime()
        val level = loudness()
        if (level == null || level.trackDb.isNaN() || now - level.atNanos > 600_000_000L) {
            quietSinceNanos = 0L
            return false
        }
        if (level.blockDb > level.trackDb - QUIET_BELOW_DB) {
            quietSinceNanos = 0L
            return false
        }
        if (quietSinceNanos == 0L) quietSinceNanos = now
        return now - quietSinceNanos >= QUIET_FOR_NANOS
    }

    /** Dos pistas seguidas del mismo álbum y en su orden (sin aleatorio). */
    private fun isContinuousAlbum(): Boolean {
        if (main.shuffleModeEnabled) return false
        val nextIndex = main.nextMediaItemIndex
        if (nextIndex == C.INDEX_UNSET) return false
        val current = main.currentMediaItem ?: return false
        val next = main.getMediaItemAt(nextIndex)
        val key = current.mediaId + ">" + next.mediaId
        if (key != albumCheckKey) {
            albumCheckKey = key
            val a = current.toSong()?.album
            val b = next.toSong()?.album
            albumCheck = when {
                a == null || b == null -> false
                a.id != null && b.id != null -> a.id == b.id
                else -> a.title.isNotBlank() && a.title.equals(b.title, ignoreCase = true)
            }
        }
        return albumCheck
    }

    private fun prepareTail(item: MediaItem, startAtMs: Long) {
        tail = tailFactory().apply {
            setMediaItem(item, startAtMs)
            volume = main.volume
            playWhenReady = false
            prepare()
        }
        tailItemId = item.mediaId
        tailStartMs = startAtMs
    }

    private fun startFade(remainingMs: Long) {
        val player = tail
        if (player == null || tailItemId != main.currentMediaItem?.mediaId) {
            releaseTail()
            return
        }
        // Si el auxiliar quedó desfasado (p. ej. tras un salto), se recoloca.
        if (abs(main.currentPosition - tailStartMs) > 350) player.seekTo(main.currentPosition)
        player.volume = 1f
        player.play()

        main.volume = 0f
        internalTransition = true
        main.seekToNextMediaItem()
        main.play()

        val fadeMs = remainingMs.coerceAtLeast(500)
        fadeJob = scope.launch {
            val start = System.currentTimeMillis()
            while (isActive) {
                val progress = ((System.currentTimeMillis() - start).toFloat() / fadeMs).coerceIn(0f, 1f)
                // Curva de potencia constante: el volumen total se mantiene durante la mezcla.
                player.volume = cos(progress * PI / 2).toFloat()
                main.volume = sin(progress * PI / 2).toFloat()
                if (progress >= 1f) break
                delay(30)
            }
            main.volume = 1f
            releaseTail()
        }
    }

    /** Corta la mezcla en seco (pausa, salto manual, cambio de cola…). */
    fun cancel() {
        fadeJob?.cancel()
        fadeJob = null
        main.volume = 1f
        releaseTail()
    }

    private fun releaseTail() {
        tail?.release()
        tail = null
        tailItemId = null
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        onNewTrack()
        quietSinceNanos = 0L
        if (internalTransition) {
            internalTransition = false
            return
        }
        // Cambio de canción que no ha provocado el crossfade: se descarta la mezcla.
        if (isFading) cancel() else releaseTail()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (!isPlaying && main.playbackState != Player.STATE_BUFFERING && isFading) cancel()
    }

    override fun onPositionDiscontinuity(
        oldPosition: Player.PositionInfo,
        newPosition: Player.PositionInfo,
        reason: Int,
    ) {
        if (reason == Player.DISCONTINUITY_REASON_SEEK && !internalTransition && !isFading) releaseTail()
    }

    fun release() {
        pollJob.cancel()
        cancel()
        main.removeListener(this)
    }

    companion object {
        private const val PREPARE_AHEAD_MS = 4_000L

        /** Cuánto antes del punto normal se vigila el final de la canción. */
        private const val OUTRO_WINDOW_MS = 10_000L

        /** "Acabando" = 18 dB por debajo de la media de la canción durante 1,2 s seguidos. */
        private const val QUIET_BELOW_DB = 18f
        private const val QUIET_FOR_NANOS = 1_200_000_000L
    }
}
