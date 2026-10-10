package com.lyra.music.playback

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.ParserException
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSourceException
import androidx.media3.datasource.HttpDataSource
import androidx.media3.exoplayer.upstream.DefaultLoadErrorHandlingPolicy
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.UnknownHostException
import javax.net.ssl.SSLException

/**
 * Sin cobertura (un túnel, una zona sin señal en el coche…) no se da ninguna canción por perdida:
 * suena lo que ya está guardado y se reintenta cada pocos segundos hasta que vuelve la red, y
 * entonces sigue sola. Antes, a los pocos intentos daba error y saltaba a la siguiente, que
 * tampoco cargaba, y así toda la lista. Los fallos que no son de la red (la dirección caducó, un
 * audio que no se puede leer…) siguen como siempre: unos intentos y al aviso de error.
 */
@UnstableApi
class WaitForNetworkPolicy(
    private val isOnline: () -> Boolean,
    private val now: () -> Long = SystemClock::elapsedRealtime,
) : DefaultLoadErrorHandlingPolicy() {

    @Volatile private var lastNetworkErrorAt = NEVER

    /** La red ha fallado hace nada: lo que esté cargando está esperando cobertura. */
    val failing: Boolean get() = lastNetworkErrorAt != NEVER && now() - lastNetworkErrorAt < FAILING_WINDOW_MS

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long {
        if (isNetworkProblem(loadErrorInfo.exception)) {
            lastNetworkErrorAt = now()
            return minOf(loadErrorInfo.errorCount * 1_000L, MAX_RETRY_DELAY_MS)
        }
        if (loadErrorInfo.errorCount > OTHER_ERROR_RETRIES) return C.TIME_UNSET
        return super.getRetryDelayMsFor(loadErrorInfo)
    }

    /** Lo de la red no se da nunca por perdido; lo demás se corta en [getRetryDelayMsFor]. */
    override fun getMinimumLoadableRetryCount(dataType: Int): Int = Int.MAX_VALUE

    fun isNetworkProblem(error: Throwable): Boolean {
        val chain = generateSequence(error) { it.cause }.take(12).toList()
        // El audio en sí está mal o el servidor contestó (p. ej. la dirección caducó): no es la cobertura.
        if (chain.any { it is ParserException || it is HttpDataSource.InvalidResponseCodeException || it is HttpDataSource.CleartextNotPermittedException }) {
            return false
        }
        return chain.any { it.isConnectionFailure() } || !isOnline()
    }

    private fun Throwable.isConnectionFailure(): Boolean = when (this) {
        // SocketTimeoutException y el «timeout» de OkHttp son InterruptedIOException.
        is UnknownHostException, is ConnectException, is NoRouteToHostException, is SocketException, is SSLException, is InterruptedIOException -> true
        is DataSourceException ->
            reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED || reason == PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT
        else -> false
    }

    private companion object {
        const val NEVER = Long.MIN_VALUE
        const val MAX_RETRY_DELAY_MS = 3_000L
        /** Mientras falla, se reintenta como mucho cada 3 s: si no ha fallado en 8, ya carga. */
        const val FAILING_WINDOW_MS = 8_000L
        /** Los demás fallos, como hacía Media3: unos pocos intentos y al aviso de error. */
        const val OTHER_ERROR_RETRIES = 3
    }
}

/**
 * Mientras se espera cobertura: lo cuenta (la app pone «Sin cobertura» y el coche lo avisa) y, si
 * la espera se alarga y más adelante en la cola hay canciones que suenan sin internet
 * (descargadas o ya enteras en la caché), sigue con ellas para que no haya silencio.
 */
@UnstableApi
class CoverageWatcher(
    private val player: Player,
    private val policy: WaitForNetworkPolicy,
    private val playableOffline: (MediaItem) -> Boolean,
    private val scope: CoroutineScope,
    private val onWaitingChanged: (Boolean) -> Unit,
) : Player.Listener {

    private var job: Job? = null
    private var waiting = false

    init {
        player.addListener(this)
    }

    override fun onPlaybackStateChanged(playbackState: Int) = check()

    override fun onPlayWhenReadyChanged(playWhenReady: Boolean, reason: Int) = check()

    private fun check() {
        if (!player.playWhenReady || player.playbackState != Player.STATE_BUFFERING) {
            job?.cancel()
            job = null
            setWaiting(false)
            return
        }
        if (job?.isActive == true) return
        job = scope.launch {
            val silentSince = SystemClock.elapsedRealtime()
            var jumped = false
            while (isActive) {
                delay(1_000)
                // Cargando sin más (red lenta pero que llega): no es falta de cobertura.
                if (!policy.failing) {
                    setWaiting(false)
                    continue
                }
                setWaiting(true)
                if (!jumped && SystemClock.elapsedRealtime() - silentSince >= JUMP_AFTER_MS) {
                    jumped = true
                    nextPlayableOffline()?.let { player.seekTo(it, 0) }
                }
            }
        }
    }

    /** La siguiente de la cola (en el orden de verdad, con aleatorio) que suena sin internet. */
    fun nextPlayableOffline(): Int? {
        val timeline = player.currentTimeline
        if (timeline.isEmpty) return null
        val repeatMode = if (player.repeatMode == Player.REPEAT_MODE_ONE) Player.REPEAT_MODE_OFF else player.repeatMode
        val current = player.currentMediaItemIndex
        var index = current
        repeat(timeline.windowCount) {
            index = timeline.getNextWindowIndex(index, repeatMode, player.shuffleModeEnabled)
            if (index == C.INDEX_UNSET || index == current) return null
            if (playableOffline(player.getMediaItemAt(index))) return index
        }
        return null
    }

    private fun setWaiting(value: Boolean) {
        if (waiting == value) return
        waiting = value
        onWaitingChanged(value)
    }

    fun release() {
        job?.cancel()
        player.removeListener(this)
    }

    private companion object {
        /** Tras este rato en silencio, si hay algo que suene sin internet, se sigue con eso. */
        const val JUMP_AFTER_MS = 8_000L
    }
}
