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
import com.lyra.music.data.db.DownloadEntity
import com.lyra.music.data.db.DownloadState
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

/** Aviso temporal (como las "actividades" del iPhone). */
data class IslandNotice(
    val kind: Kind,
    val label: String,
    val title: String,
    val artwork: String? = null,
    val id: Long = System.nanoTime(),
) {
    enum class Kind { NOW_PLAYING, DOWNLOAD, LIKED }
}

/** Forma de la isla: píldora, aviso o tarjeta desplegada. */
sealed interface IslandShape {
    data object Pill : IslandShape
    data class Notice(val notice: IslandNotice) : IslandShape
    data object Expanded : IslandShape
}

/**
 * La isla: una píldora negra junto a la cámara que muestra lo que suena, se
 * estira para avisar de cosas y se despliega al tocarla. Se dibuja con el
 * servicio de accesibilidad (encima de la barra de estado) o, si no está
 * activado, con el permiso de "mostrar sobre otras apps". No aparece con Lyra abierta.
 */
class IslandController(
    private val context: Context,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
    private val downloadStates: StateFlow<Map<String, DownloadEntity>>,
    private val likedIds: StateFlow<Set<String>>,
    private val songLookup: suspend (String) -> Song?,
) {
    private val _state = MutableStateFlow(IslandState())
    val state: StateFlow<IslandState> = _state.asStateFlow()

    private val _shape = MutableStateFlow<IslandShape>(IslandShape.Pill)
    val shape: StateFlow<IslandShape> = _shape.asStateFlow()

    private val accessibilityHost = MutableStateFlow<AccessibilityService?>(null)
    private val appVisible = MutableStateFlow(true)
    private val tick = MutableStateFlow(0L)

    private var player: Player? = null
    private var window: IslandWindow? = null
    private var windowType = 0
    private var jobs = mutableListOf<Job>()
    private var progressJob: Job? = null
    private var shapeJob: Job? = null

    val accessibilityConnected: StateFlow<AccessibilityService?> = accessibilityHost.asStateFlow()

    private val visible: Boolean get() = window != null

    fun attach(player: Player) {
        this.player = player
        player.addListener(listener)
        readPlayer(player)
        jobs.forEach { it.cancel() }
        jobs = mutableListOf(
            scope.launch {
                combine(settings.flow, _state, accessibilityHost, appVisible, tick) { s, st, host, isVisible, _ ->
                    Decision(s, st, host, isVisible)
                }.collect(::apply)
            },
            // Vuelve a evaluar de vez en cuando (para ocultarla tras un rato en pausa).
            scope.launch {
                while (isActive) {
                    delay(30_000)
                    tick.value = System.currentTimeMillis()
                }
            },
            scope.launch { watchDownloads() },
            scope.launch { watchLikes() },
        )
    }

    fun detach() {
        player?.removeListener(listener)
        player = null
        jobs.forEach { it.cancel() }
        jobs.clear()
        hide()
    }

    fun onAccessibilityConnected(service: AccessibilityService) {
        accessibilityHost.value = service
    }

    fun onAccessibilityDisconnected() {
        if (windowType == WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY) hide()
        accessibilityHost.value = null
    }

    fun setAppVisible(isVisible: Boolean) {
        appVisible.value = isVisible
    }

    fun canDrawOverlays(): Boolean = Settings.canDrawOverlays(context)

    // ------------------------------------------------------------- formas

    fun expand() = setShape(IslandShape.Expanded, 6_000)

    fun collapse() = setShape(IslandShape.Pill, 0)

    /** Muestra un aviso unos segundos (si no está desplegada del todo). */
    fun showNotice(notice: IslandNotice) {
        if (!visible || _shape.value == IslandShape.Expanded) return
        setShape(IslandShape.Notice(notice), 3_400)
    }

    private fun setShape(shape: IslandShape, autoCollapseMs: Long) {
        _shape.value = shape
        shapeJob?.cancel()
        if (autoCollapseMs > 0) {
            shapeJob = scope.launch {
                delay(autoCollapseMs)
                _shape.value = IslandShape.Pill
            }
        }
    }

    /** Cualquier toque en la isla desplegada reinicia la cuenta atrás para recogerse. */
    fun keepExpanded() {
        if (_shape.value == IslandShape.Expanded) setShape(IslandShape.Expanded, 6_000)
    }

    // ------------------------------------------------------------- acciones

    fun togglePlay() {
        val p = player ?: return
        if (p.isPlaying) p.pause() else {
            if (p.playbackState == Player.STATE_IDLE) p.prepare()
            p.play()
        }
        keepExpanded()
    }

    fun next() {
        player?.seekToNext()
        keepExpanded()
    }

    fun previous() {
        val p = player ?: return
        if (p.currentPosition > 3_000) p.seekTo(0) else p.seekToPrevious()
        keepExpanded()
    }

    fun openApp() {
        collapse()
        context.startActivity(
            Intent(context, MainActivity::class.java)
                .setAction(MainActivity.ACTION_OPEN_PLAYER)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),
        )
    }

    // ------------------------------------------------------------- avisos

    private suspend fun watchDownloads() {
        var known: Set<String>? = null
        var pending = mutableListOf<String>()
        var flushJob: Job? = null
        downloadStates.collect { map ->
            val completed = map.values.filter { it.state == DownloadState.COMPLETED }.map { it.songId }.toSet()
            val previous = known
            known = completed
            if (previous == null) return@collect
            val fresh = completed - previous
            if (fresh.isEmpty()) return@collect
            pending += fresh
            // Si terminan varias seguidas (un álbum), se agrupan en un solo aviso.
            flushJob?.cancel()
            flushJob = scope.launch {
                delay(1_500)
                val ids = pending.toList()
                pending = mutableListOf()
                if (ids.size == 1) {
                    val song = songLookup(ids.first())
                    showNotice(IslandNotice(IslandNotice.Kind.DOWNLOAD, "Descarga completada", song?.title ?: "Canción descargada", song?.thumbnailUrl))
                } else {
                    showNotice(IslandNotice(IslandNotice.Kind.DOWNLOAD, "Descargas completadas", "${ids.size} canciones listas sin conexión"))
                }
            }
        }
    }

    private suspend fun watchLikes() {
        var known: Set<String>? = null
        likedIds.collect { ids ->
            val previous = known
            known = ids
            val current = _state.value.song ?: return@collect
            if (previous != null && current.id in ids && current.id !in previous) {
                showNotice(IslandNotice(IslandNotice.Kind.LIKED, "Añadida a Me gusta", current.title, current.thumbnailUrl))
            }
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
            _shape.value = IslandShape.Pill
            window = IslandWindow(hostContext, type, geometry, this).also { it.show() }
            windowType = type
        }
        if (d.state.isPlaying) startProgress() else progressJob?.cancel()
    }

    private fun hide() {
        progressJob?.cancel()
        shapeJob?.cancel()
        window?.remove()
        window = null
        windowType = 0
        _shape.value = IslandShape.Pill
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
            val before = _state.value.song?.id
            player?.let(::readPlayer)
            val song = _state.value.song
            if (song != null && song.id != before && before != null) {
                showNotice(IslandNotice(IslandNotice.Kind.NOW_PLAYING, "Ahora suena", "${song.title} · ${song.artistsText}", song.thumbnailUrl))
            }
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
