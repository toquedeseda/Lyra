package com.lyra.music.playback

import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.exoplayer.ExoPlayer
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
) : Player.Listener {

    /** Duración del crossfade en ms (0 = desactivado). */
    var durationMs: Long = 6_000

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
        val fade = durationMs.coerceAtMost(duration / 4)
        val position = main.currentPosition
        val remaining = duration - position
        val item = main.currentMediaItem ?: return false

        if (remaining <= fade + PREPARE_AHEAD_MS && tail == null) {
            prepareTail(item, duration - fade)
        }
        if (remaining <= fade && remaining > 400) {
            startFade(remaining)
            return false
        }
        return remaining <= fade + 1_500
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
    }
}
