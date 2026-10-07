package com.lyra.desktop

import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.graphics.painter.BitmapPainter
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.isAltPressed
import androidx.compose.ui.input.key.isCtrlPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Notification
import androidx.compose.ui.window.Tray
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPlacement
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberTrayState
import androidx.compose.ui.window.rememberWindowState
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.lyra.desktop.audio.Natives
import com.lyra.desktop.data.WindowBounds
import com.lyra.desktop.system.Integrations
import com.lyra.desktop.system.MediaControls
import com.lyra.desktop.system.WindowsSystem
import com.lyra.desktop.ui.LyraActions
import com.lyra.desktop.ui.MainContent
import com.lyra.desktop.ui.MiniPlayerContent
import com.lyra.desktop.ui.Navigator
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.ui.components.Typing
import com.lyra.music.data.share.PlaylistSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import okio.Path.Companion.toOkioPath
import org.jetbrains.skia.Image
import java.awt.Dimension
import javax.swing.UIManager
import kotlin.system.exitProcess

/** Lo que hace falta desde fuera de la interfaz (diálogos de archivos…). */
object Lyra {
    lateinit var app: AppContainer
    lateinit var actions: LyraActions
}

private fun iconPainter(size: Int): BitmapPainter? = runCatching {
    val bytes = Thread.currentThread().contextClassLoader.getResourceAsStream("icons/lyra-$size.png")!!.readBytes()
    BitmapPainter(Image.makeFromEncoded(bytes).toComposeImageBitmap())
}.getOrNull()

@OptIn(FlowPreview::class)
fun main(args: Array<String>) {
    Paths.data // crea las carpetas y prepara FFmpeg
    ErrorLog.install()
    runCatching { UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName()) }

    // Si ya hay una Lyra abierta, se le pasa el aviso (y el enlace, si lo hay) y esta se cierra.
    val showRequests = MutableSharedFlow<List<String>>(extraBufferCapacity = 4)
    if (!WindowsSystem.acquireSingleInstance(args.toList()) { showRequests.tryEmit(it) }) exitProcess(0)

    val app = AppContainer()
    Lyra.app = app
    SingletonImageLoader.setSafe { context ->
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { app.http })) }
            .memoryCache { MemoryCache.Builder().maxSizePercent(context, 0.2).build() }
            .diskCache { DiskCache.Builder().directory(Paths.imageCache.toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .crossfade(true)
            .build()
    }
    // FFmpeg se prepara ya, para que la primera canción no tarde.
    app.scope.launch(Dispatchers.IO) { runCatching { Natives.ensureLoaded() } }
    app.player.restore()
    app.updater.start()
    app.sync.start()
    Integrations.start(app)

    val nav = Navigator()
    val actions = LyraActions(app, nav)
    Lyra.actions = actions
    val startHidden = "--minimized" in args
    args.firstOrNull { it.startsWith("http") }?.let { openLink(actions, it) }

    application {
        val settings by app.settings.flow.collectAsState()
        var visible by remember { mutableStateOf(!startHidden) }
        var mini by remember { mutableStateOf(false) }
        val saved = remember { app.settings.current.window }
        val windowState = rememberWindowState(
            placement = if (saved?.maximized == true) WindowPlacement.Maximized else WindowPlacement.Floating,
            position = if (saved != null) WindowPosition(saved.x.dp, saved.y.dp) else WindowPosition.PlatformDefault,
            size = if (saved != null) DpSize(saved.width.dp, saved.height.dp) else DpSize(1280.dp, 820.dp),
        )
        val icon = remember { iconPainter(256) }
        val trayState = rememberTrayState()
        // Como Spotify: lo que suena en el título de la ventana (y al pasar por la barra de tareas).
        val playing by remember {
            app.player.state.map { s -> s.current?.song?.takeIf { s.isPlaying } }.distinctUntilChanged()
        }.collectAsState(null)
        val title = playing?.let { song -> listOf(song.artistsText, song.title).filter { it.isNotBlank() }.joinToString(" - ") } ?: "Lyra"
        fun quit() {
            app.shutdown()
            exitApplication()
        }

        fun hideToTray() {
            visible = false
            // La primera vez, que se sepa que sigue ahí (y cómo cerrarla del todo).
            if (!app.settings.current.trayHintShown) {
                app.settings.update { it.copy(trayHintShown = true) }
                trayState.sendNotification(
                    Notification("Lyra sigue abierta", "La música sigue sonando. Para cerrarla del todo: clic derecho en este icono → Salir.", Notification.Type.None),
                )
            }
        }

        if (settings.closeToTray || !visible) {
            Tray(
                icon = remember { iconPainter(32) } ?: icon!!,
                state = trayState,
                tooltip = if (playing != null) "Lyra · $title" else "Lyra",
                onAction = { visible = true },
                menu = {
                    Item("Abrir Lyra", onClick = { visible = true })
                    Item("Reproducir / Pausa", onClick = { app.player.togglePlay() })
                    Item("Siguiente", onClick = { app.player.next() })
                    Item("Anterior", onClick = { app.player.previous() })
                    Separator()
                    Item("Salir", onClick = ::quit)
                },
            )
        }

        Window(
            onCloseRequest = { if (app.settings.current.closeToTray) hideToTray() else quit() },
            visible = visible,
            title = title,
            icon = icon,
            state = windowState,
            onKeyEvent = { event -> shortcuts(event, actions) },
        ) {
            LaunchedEffect(Unit) {
                window.minimumSize = Dimension(940, 620)
                WindowsSystem.styleTitleBar(window, 0xFF0A0A0B)
                // Teclas multimedia y tarjeta de Windows (al cambiar el volumen, pantalla de bloqueo…).
                runCatching { com.sun.jna.Native.getWindowPointer(window) }.getOrNull()?.let { hwnd ->
                    MediaControls.start(hwnd) { button ->
                        when (button) {
                            MediaControls.Button.PLAY -> app.player.resume()
                            MediaControls.Button.PAUSE, MediaControls.Button.STOP -> app.player.pause()
                            MediaControls.Button.NEXT -> app.player.next()
                            MediaControls.Button.PREVIOUS -> app.player.previous()
                        }
                    }
                }
            }
            LaunchedEffect(Unit) {
                showRequests.collect { extra ->
                    visible = true
                    if (windowState.isMinimized) windowState.isMinimized = false
                    window.toFront()
                    window.requestFocus()
                    extra.firstOrNull { it.startsWith("http") }?.let { openLink(actions, it) }
                }
            }
            // Se recuerda el tamaño y el sitio de la ventana.
            LaunchedEffect(windowState) {
                snapshotFlow { Triple(windowState.size, windowState.position, windowState.placement) }
                    .debounce(600)
                    .collect { (size, position, placement) ->
                        val maximized = placement == WindowPlacement.Maximized
                        val current = app.settings.current.window
                        val bounds = if (maximized && current != null) current.copy(maximized = true)
                        else WindowBounds(position.x.value.toInt(), position.y.value.toInt(), size.width.value.toInt(), size.height.value.toInt(), maximized)
                        if (position.isSpecified) app.settings.update { it.copy(window = bounds) }
                    }
            }
            MainContent(actions, onToggleMini = { mini = !mini })
        }

        if (mini) {
            val miniState = rememberWindowState(
                size = DpSize(380.dp, 104.dp),
                position = if (settings.miniX != null && settings.miniY != null) WindowPosition(settings.miniX!!.dp, settings.miniY!!.dp) else WindowPosition.Aligned(androidx.compose.ui.Alignment.BottomEnd),
            )
            Window(
                onCloseRequest = { mini = false },
                state = miniState,
                title = "Lyra",
                icon = icon,
                undecorated = true,
                transparent = true,
                resizable = false,
                alwaysOnTop = true,
                onKeyEvent = { event -> shortcuts(event, actions) },
            ) {
                LaunchedEffect(miniState) {
                    snapshotFlow { miniState.position }.debounce(500).collect { p ->
                        if (p.isSpecified) app.settings.update { it.copy(miniX = p.x.value.toInt(), miniY = p.y.value.toInt()) }
                    }
                }
                MiniPlayerContent(actions, onClose = { mini = false }, onExpand = {
                    mini = false
                    visible = true
                })
            }
        }
    }
}

/** Enlaces de Lyra, YouTube, SoundCloud o Spotify abiertos desde fuera. */
private fun openLink(actions: LyraActions, url: String) {
    if (PlaylistSharing.isPlaylistLink(url)) actions.nav.navigate(Screen.SharedPlaylist(url))
    else actions.nav.navigate(Screen.Search(url))
}

/** Atajos de teclado (los de Spotify, más o menos). */
private fun shortcuts(event: KeyEvent, actions: LyraActions): Boolean {
    if (event.type != KeyEventType.KeyDown) return false
    val player = actions.app.player
    val ctrl = event.isCtrlPressed
    return when {
        // Escribiendo en un buscador, el espacio es un espacio (el cuadro de texto no se lo queda).
        event.key == Key.Spacebar && !ctrl && !Typing.active -> { player.togglePlay(); true }
        ctrl && event.key == Key.DirectionRight -> { player.next(); true }
        ctrl && event.key == Key.DirectionLeft -> { player.previous(); true }
        ctrl && event.key == Key.DirectionUp -> { changeVolume(actions, +0.05f); true }
        ctrl && event.key == Key.DirectionDown -> { changeVolume(actions, -0.05f); true }
        ctrl && (event.key == Key.F || event.key == Key.L || event.key == Key.K) -> {
            if (actions.nav.current.screen !is Screen.Search) actions.nav.navigate(Screen.Search())
            actions.focusSearch++
            true
        }
        ctrl && event.key == Key.S -> { player.setShuffle(!player.state.value.shuffle); true }
        ctrl && event.key == Key.R -> { player.cycleRepeat(); true }
        event.isAltPressed && event.key == Key.DirectionLeft -> actions.nav.goBack()
        event.isAltPressed && event.key == Key.DirectionRight -> actions.nav.goForward()
        // Si Windows ya nos manda las teclas multimedia, no se repiten aquí.
        event.key == Key.MediaPlayPause && !MediaControls.active -> { player.togglePlay(); true }
        event.key == Key.MediaNext && !MediaControls.active -> { player.next(); true }
        event.key == Key.MediaPrevious && !MediaControls.active -> { player.previous(); true }
        else -> false
    }
}

private fun changeVolume(actions: LyraActions, delta: Float) {
    actions.app.settings.update { it.copy(volume = (it.volume + delta).coerceIn(0f, 1f), muted = false) }
}
