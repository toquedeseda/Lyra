package com.lyra.music.playback

import android.os.SystemClock
import android.util.Log
import com.lyra.music.BuildConfig
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
 *
 * Para que el relevo no se note, el auxiliar espera preparado justo en el punto
 * donde va a entrar y arranca un momento antes, en silencio y a la par que el
 * principal (sin saltos: cada salto le haría volver a cargar y desfasarse). Luego
 * toma el sonido en unas centésimas y el principal salta a la siguiente. Lo que
 * tarda en arrancar se aprende de una canción a otra. Además sigue con el mismo
 * volumen igualado que llevaba la canción ([onTailStart]): si empezara de cero,
 * el final sonaría de golpe más fuerte.
 */
@UnstableApi
class CrossfadeController(
    private val main: ExoPlayer,
    private val tailFactory: () -> ExoPlayer,
    private val scope: CoroutineScope,
    private val onNewTrack: (MediaItem?) -> Unit,
    private val loudness: () -> LyraAudioProcessor.Loudness? = { null },
    /** Justo antes de que suene el auxiliar: que use la misma ganancia que el principal. */
    private val onTailStart: () -> Unit = {},
    /** Lo aprendido otras veces sobre lo que tarda el auxiliar en sonar (ms; null: aún nada). */
    learnedStartLatencyMs: Long? = null,
    /** Para guardar lo aprendido y no empezar de cero al abrir otra vez la app. */
    private val onLatencyLearned: (Long) -> Unit = {},
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

    // El ensayo: el auxiliar ya suena, en silencio, a la par que el principal.
    private var prerollJob: Job? = null
    private var prerolling = false

    /** Lo que tarda el auxiliar en sonar de verdad tras pedírselo (se aprende con lo medido). */
    private var startLatencyMs = learnedStartLatencyMs ?: 60L
    private var learned = learnedStartLatencyMs != null

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

        if (remaining <= window + PREROLL_MS + PREPARE_AHEAD_MS && tail == null) {
            // Donde entrará el auxiliar: un poco antes de la mezcla (o en cuanto dé tiempo a prepararlo).
            val startAt = maxOf(duration - window - PREROLL_MS, position + MIN_PREPARE_MS)
            prepareTail(item, startAt.coerceAtMost(duration - fade - 200).coerceAtLeast(0))
        }
        if (tail != null && !prerolling && prerollJob == null) schedulePreroll()
        if (smart && remaining in 1_500..window && outroStarted()) {
            startFade(minOf(fade, remaining))
            return false
        }
        if (remaining <= fade && remaining > 400) {
            startFade(remaining)
            return false
        }
        return remaining <= window + PREROLL_MS + 1_500
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
            volume = 0f
            playWhenReady = false
            prepare()
        }
        tailItemId = item.mediaId
        tailStartMs = startAtMs
    }

    /**
     * El auxiliar ya espera parado justo donde tiene que entrar: se le da al play cuando al
     * principal le falta para llegar ahí lo que tarda el auxiliar en sonar.
     */
    private fun schedulePreroll() {
        val player = tail ?: return
        if (player.playbackState != Player.STATE_READY) return // aún cargando: en el siguiente vistazo
        val wait = tailStartMs - main.currentPosition - startLatencyMs
        if (wait > 400) return // todavía no (se vuelve a mirar en 40 ms)
        if (wait < -150) return // ya se pasó (p. ej. tras saltar casi al final): mezcla sin ensayo
        prerollJob = scope.launch {
            if (wait > 0) delay(wait)
            if (tail !== player || !main.isPlaying || tailItemId != main.currentMediaItem?.mediaId) return@launch
            onTailStart()
            player.volume = 0f
            player.play()
            prerolling = true
            debug("ensayo: empieza (margen $startLatencyMs ms)")
        }
    }

    /** El desfase justo en el relevo dice si el auxiliar arrancó pronto o tarde: la próxima vez, más afinado. */
    private fun learnFrom(drift: Long) {
        // La primera vez se corrige entero; luego, a medias (para no dar bandazos por una medida rara).
        startLatencyMs = (startLatencyMs - if (learned) drift / 2 else drift).coerceIn(0, 400)
        learned = true
        onLatencyLearned(startLatencyMs)
    }

    private fun startFade(remainingMs: Long) {
        val player = tail
        if (player == null || tailItemId != main.currentMediaItem?.mediaId) {
            releaseTail()
            return
        }
        onTailStart()
        val rehearsed = prerolling && player.isPlaying
        if (rehearsed) {
            val drift = player.currentPosition - main.currentPosition
            learnFrom(drift)
            debug("mezcla: empieza con ensayo (desfase $drift ms; la próxima vez, margen $startLatencyMs ms)")
        } else {
            debug("mezcla: empieza sin ensayo")
        }
        if (!rehearsed) {
            // Sin ensayo (p. ej. tras saltar casi al final): arranca ya, desde donde va el principal.
            if (abs(main.currentPosition - player.currentPosition) > 350) player.seekTo(main.currentPosition)
            player.play()
        }
        prerolling = false

        fadeJob = scope.launch {
            if (rehearsed) {
                // Relevo: los dos suenan igual; el sonido pasa al auxiliar en unas centésimas.
                for (step in 1..HANDOFF_STEPS) {
                    val share = step.toFloat() / HANDOFF_STEPS
                    player.volume = share
                    main.volume = 1f - share
                    delay(HANDOFF_STEP_MS)
                }
            } else {
                player.volume = 1f
            }
            main.volume = 0f
            internalTransition = true
            main.seekToNextMediaItem()
            main.play()

            val fadeMs = (remainingMs - if (rehearsed) HANDOFF_STEPS * HANDOFF_STEP_MS else 0L).coerceAtLeast(500)
            val start = SystemClock.uptimeMillis()
            while (isActive) {
                val progress = ((SystemClock.uptimeMillis() - start).toFloat() / fadeMs).coerceIn(0f, 1f)
                // Curva de potencia constante: el volumen total se mantiene durante la mezcla.
                player.volume = cos(progress * PI / 2).toFloat()
                main.volume = sin(progress * PI / 2).toFloat()
                if (progress >= 1f) break
                delay(30)
            }
            main.volume = 1f
            releaseTail()
            debug("mezcla: terminada")
        }
    }

    private fun debug(message: String) {
        if (BuildConfig.DEBUG) Log.d("LyraCrossfade", message)
    }

    /** Corta la mezcla en seco (pausa, salto manual, cambio de cola…). */
    fun cancel() {
        fadeJob?.cancel()
        fadeJob = null
        main.volume = 1f
        releaseTail()
    }

    private fun releaseTail() {
        prerollJob?.cancel()
        prerollJob = null
        tail?.release()
        tail = null
        tailItemId = null
        prerolling = false
    }

    override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
        onNewTrack(mediaItem)
        quietSinceNanos = 0L
        if (internalTransition) {
            internalTransition = false
            return
        }
        // Cambio de canción que no ha provocado el crossfade: se descarta la mezcla.
        if (isFading) cancel() else releaseTail()
    }

    override fun onIsPlayingChanged(isPlaying: Boolean) {
        if (isPlaying || main.playbackState == Player.STATE_BUFFERING) return
        if (isFading) {
            cancel()
        } else if (prerolling || prerollJob != null) {
            // En pausa se deja el ensayo: al seguir se prepara otra vez a la par (sin saltos).
            releaseTail()
        }
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

        /** Cuánto antes de la mezcla empieza a sonar (en silencio) el auxiliar. */
        private const val PREROLL_MS = 1_500L

        /** Margen mínimo para preparar el auxiliar (tras saltar cerca del final, p. ej.). */
        private const val MIN_PREPARE_MS = 1_200L
        private const val HANDOFF_STEPS = 4
        private const val HANDOFF_STEP_MS = 15L

        /** Cuánto antes del punto normal se vigila el final de la canción. */
        private const val OUTRO_WINDOW_MS = 10_000L

        /** "Acabando" = 18 dB por debajo de la media de la canción durante 1,2 s seguidos. */
        private const val QUIET_BELOW_DB = 18f
        private const val QUIET_FOR_NANOS = 1_200_000_000L
    }
}
