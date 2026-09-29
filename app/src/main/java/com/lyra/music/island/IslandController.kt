package com.lyra.music.island

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings
import android.view.WindowManager
import androidx.media3.common.MediaItem
import androidx.media3.common.Player
import com.lyra.music.MainActivity
import com.lyra.music.data.model.Song
import com.lyra.music.data.settings.AppSettings
import com.lyra.music.data.settings.IslandMode
import com.lyra.music.data.settings.SettingsRepository
import com.lyra.music.playback.MediaItems.toSong
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

data class IslandState(
    val song: Song? = null,
    val isPlaying: Boolean = false,
    val positionMs: Long = 0,
    val durationMs: Long = 0,
    val pausedAt: Long = 0,
)

/**
 * La isla: una píldora negra junto a la cámara que muestra lo que suena y se
 * despliega al tocarla. Se dibuja con el servicio de accesibilidad (encima de la
 * barra de estado) o, si no está activado, con el permiso de "mostrar sobre otras apps".
 * No aparece mientras Lyra está abierta.
 */
class IslandController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val _state = MutableStateFlow(IslandState())
    val state: StateFlow<IslandState> = _state.asStateFlow()

    private val _expanded = MutableStateFlow(false)
    val expanded: StateFlow<Boolean> = _expanded.asStateFlow()

    private val accessibilityHost = MutableStateFlow<AccessibilityService?>(null)
    private val appVisible = MutableStateFlow(true)
    private val tick = MutableStateFlow(0L)

    private var player: Player? = null
    private var window: IslandWindow? = null
    private var windowType = 0
    private var observer: Job? = null
    private var progressJob: Job? = null
    private var collapseJob: Job? = null

    val accessibilityConnected: StateFlow<AccessibilityService?> = accessibilityHost.asStateFlow()

    fun attach(player: Player) {
        this.player = player
        player.addListener(listener)
        readPlayer(player)
        observer?.cancel()
        observer = scope.launch {
            combine(settings.flow, _state, accessibilityHost, appVisible, tick) { s, st, host, visible, _ ->
                Decision(s, st, host, visible)
            }.collect(::apply)
        }
        // Vuelve a evaluar cada minuto (para ocultarla tras un rato en pausa).
        scope.launch {
            while (isActive && this@IslandController.player != null) {
                delay(30_000)
                tick.value = System.currentTimeMillis()
            }
        }
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
        observer?.cancel()
        hide()
    }

    fun onAccessibilityConnected(service: AccessibilityService) {
        accessibilityHost.value = service
    }

    fun onAccessibilityDisconnected() {
        if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) hide()
        accessibilityHost.value = null
    }

    fun setAppVisible(visible: Boolean) {
        appVisible.value = visible
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    // ------------------------------------------------------------- acciones

    fun expand() {
        _expanded.value = true
        scheduleCollapse()
    }

    fun collapse() {
        _expanded.value = false
        collapseJob?.cancel()
    }

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
        scheduleCollapse()
    }

    fun next() {
        player?.seekToNext()
        scheduleCollapse()
    }

    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3_000) p.seekTo(0) else p.seekToPrevious()
        scheduleCollapse()
    }

    fun openApp() {
        collapse()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    private fun scheduleCollapse() {
        collapseJob?.cancel()
        collapseJob = scope.launch {
            delay(5_000)
            _expanded.value = false
        }
    }

    // ------------------------------------------------------------- decisión

    private data class Decision(
        val settings: AppSettings,
        val state: IslandState,
        val host: AccessibilityService?,
        val appVisible: Boolean,
    )

    private fun apply(d: Decision) {
        val recentlyPaused = !d.state.isPlaying && System.currentTimeMillis() - d.state.pausedAt < 90_000
        val wanted = d.settings.islandEnabled && d.state.song != null && !d.appVisible &&
            (d.state.isPlaying || recentlyPaused)
        if (!wanted) {
            hide()
            return
        }
        val useAccessibility = d.host != null && d.settings.islandMode != IslandMode.OVERLAY
        val type = when {
            useAccessibility -> WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY
            d.settings.islandMode != IslandMode.ACCESSIBILITY && canDrawOverlays() ->
                WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
            else -> {
                hide()
                return
            }
        }
        val hostContext: Context = if (useAccessibility) d.host!! else context
        val geometry = IslandGeometry.from(hostContext, d.settings, overStatusBar = useAccessibility)
        val current = window
        if (current != null && windowType == type) {
            current.update(geometry)
        } else {
            hide()
            window = IslandWindow(hostContext, type, geometry, this).also { it.show() }
            windowType = type
        }
        if (d.state.isPlaying) startProgress() else progressJob?.cancel()
    }

    private fun hide() {
        progressJob?.cancel()
        window?.remove()
        window = null
        windowType = 0
        _expanded.value = false
    }

    private fun startProgress() {
        if (progressJob?.isActive == true) return
        progressJob = scope.launch {
            while (isActive) {
                player?.let { p -> _state.update { it.copy(positionMs = p.currentPosition, durationMs = p.duration.coerceAtLeast(0)) } }
                delay(500)
            }
        }
    }

    private fun readPlayer(p: Player) {
        _state.update {
            it.copy(
                song = p.currentMediaItem?.toSong(),
                isPlaying = p.isPlaying,
                positionMs = p.currentPosition,
                durationMs = p.duration.coerceAtLeast(0),
                pausedAt = if (!p.isPlaying && it.isPlaying) System.currentTimeMillis() else it.pausedAt,
            )
        }
    }

    private val listener = object : Player.Listener {
        override fun onMediaItemTransition(mediaItem: MediaItem?, reason: Int) {
            player?.let(::readPlayer)
        }

        override fun onIsPlayingChanged(isPlaying: Boolean) {
            player?.let(::readPlayer)
        }
    }

    companion object {
        fun accessibilityEnabled(context: Context): Boolean {
            val enabled = Settings.Secure.getString(context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
                ?: return false
            return enabled.split(':').any { it.startsWith(context.packageName + "/") }
        }

        val supportsCutoutQuery: Boolean get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }
}
