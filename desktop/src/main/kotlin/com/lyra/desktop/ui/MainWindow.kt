package com.lyra.desktop.ui

import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.automirrored.rounded.ArrowForward
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Settings
import androidx.compose.material.icons.rounded.SystemUpdateAlt
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveableStateHolder
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerButton
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyra.desktop.data.RightPanel
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.FilledPill
import com.lyra.desktop.ui.components.HoverBox
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LocalNowPlaying
import com.lyra.desktop.ui.components.LyraTextField
import com.lyra.desktop.ui.components.Mosaic
import com.lyra.desktop.ui.components.NowPlaying
import com.lyra.desktop.ui.components.SearchBox
import com.lyra.desktop.ui.screens.AlbumScreen
import com.lyra.desktop.ui.screens.AllOfSectionScreen
import com.lyra.desktop.ui.screens.ArtistScreen
import com.lyra.desktop.ui.screens.BrowseScreen
import com.lyra.desktop.ui.screens.DownloadsScreen
import com.lyra.desktop.ui.screens.FolderScreen
import com.lyra.desktop.ui.screens.HistoryScreen
import com.lyra.desktop.ui.screens.HomeScreen
import com.lyra.desktop.ui.screens.LikedScreen
import com.lyra.desktop.ui.screens.LocalPlaylistScreen
import com.lyra.desktop.ui.screens.RemotePlaylistScreen
import com.lyra.desktop.ui.screens.SearchScreen
import com.lyra.desktop.ui.screens.SettingsScreen
import com.lyra.desktop.ui.screens.SharedPlaylistScreen
import com.lyra.desktop.ui.screens.SpotifyImportDialog
import com.lyra.desktop.update.UpdateState
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.merge
import kotlinx.coroutines.flow.map

/** Todo el contenido de la ventana principal. */
@Composable
fun MainContent(actions: LyraActions, onToggleMini: () -> Unit) {
    val app = actions.app
    val settings by app.settings.flow.collectAsState()
    LaunchedEffect(settings.accent) { LyraColors.setAccent(Color(settings.accent.argb)) }
    val playerState by app.player.state.collectAsState()
    CompositionLocalProvider(
        LocalActions provides actions,
        LocalNowPlaying provides NowPlaying(playerState.current?.song?.id, playerState.isPlaying),
    ) {
        LyraTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LyraColors.Background)
                    // Botones laterales del ratón: atrás y adelante.
                    .onPointerEvent(PointerEventType.Press) { event ->
                        when (event.button) {
                            PointerButton.Back -> actions.nav.goBack()
                            PointerButton.Forward -> actions.nav.goForward()
                            else -> Unit
                        }
                    },
            ) {
                BoxWithConstraints(Modifier.fillMaxSize()) {
                    val showRight = settings.rightPanel != RightPanel.NONE && maxWidth >= 1040.dp
                    val sidebarWidth = if (maxWidth < 960.dp) 250.dp else 300.dp
                    Column(Modifier.fillMaxSize().padding(start = 8.dp, end = 8.dp, top = 8.dp)) {
                        Row(Modifier.weight(1f).fillMaxWidth()) {
                            Sidebar(Modifier.width(sidebarWidth).fillMaxHeight())
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(LyraColors.Surface)) {
                                TopBar()
                                Box(Modifier.weight(1f).fillMaxWidth()) { ScreenHost() }
                            }
                            if (showRight) {
                                Spacer(Modifier.width(8.dp))
                                RightPanelView(settings.rightPanel, Modifier.width(340.dp).fillMaxHeight().clip(RoundedCornerShape(10.dp)).background(LyraColors.Surface))
                            }
                        }
                        PlayerBar(onToggleMini, Modifier.fillMaxWidth().height(88.dp))
                    }
                }
                MessagesHost(Modifier.align(Alignment.BottomCenter).padding(bottom = 104.dp))
                Dialogs()
            }
        }
    }
}

/** Barra de arriba: atrás, adelante, el buscador, las actualizaciones y los ajustes. */
@Composable
private fun TopBar() {
    val actions = LocalActions.current
    val nav = actions.nav
    val app = actions.app
    val screen = nav.current.screen
    val searchFocus = remember { FocusRequester() }
    var query by remember { mutableStateOf("") }
    // El buscador muestra lo que se está buscando en la pantalla de búsqueda.
    LaunchedEffect(screen) { query = (screen as? Screen.Search)?.query ?: "" }
    LaunchedEffect(actions.focusSearch) {
        if (actions.focusSearch > 0) runCatching { searchFocus.requestFocus() }
    }
    val update by app.updater.state.collectAsState()
    BoxWithConstraints(Modifier.fillMaxWidth()) {
        // Con poco sitio (ventana estrecha y la cola abierta), el aviso de actualizar se acorta.
        val roomy = maxWidth >= 700.dp
        val tiny = maxWidth < 520.dp
        Row(Modifier.fillMaxWidth().height(64.dp).padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
            // El buscador ocupa lo que haya (hasta 420) antes que el hueco: no se encoge por el botón de actualizar.
            Row(Modifier.weight(1f), verticalAlignment = Alignment.CenterVertically) {
                RoundNav(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", nav.canGoBack) { nav.goBack() }
                Spacer(Modifier.width(8.dp))
                RoundNav(Icons.AutoMirrored.Rounded.ArrowForward, "Adelante", nav.canGoForward) { nav.goForward() }
                Spacer(Modifier.width(16.dp))
                SearchBox(
                    value = query,
                    onValueChange = { text ->
                        query = text
                        val current = nav.current.screen
                        if (current is Screen.Search) nav.replace(current.copy(query = text)) else nav.navigate(Screen.Search(text))
                    },
                    placeholder = "¿Qué quieres escuchar?",
                    modifier = Modifier.widthIn(max = 420.dp).fillMaxWidth(),
                    focusRequester = searchFocus,
                    onSubmit = { if (query.isNotBlank()) app.library.addSearch(query) },
                )
                Spacer(Modifier.width(12.dp))
            }
            if (update is UpdateState.Available || update is UpdateState.Ready) {
                val label = if (update is UpdateState.Ready) "Reiniciar para actualizar" else "Actualizar Lyra"
                val install = { app.updater.install(beforeExit = { app.shutdown() }) }
                when {
                    tiny -> IconBtn(Icons.Rounded.SystemUpdateAlt, label, onClick = install, tint = LyraColors.Accent, hoverTint = LyraColors.Accent)
                    roomy -> FilledPill(label, onClick = install, icon = Icons.Rounded.SystemUpdateAlt)
                    else -> FilledPill("Actualizar", onClick = install, icon = Icons.Rounded.SystemUpdateAlt)
                }
                Spacer(Modifier.width(8.dp))
            }
            IconBtn(Icons.Rounded.Settings, "Ajustes", onClick = { nav.navigate(Screen.Settings) }, active = screen == Screen.Settings)
        }
    }
}

@Composable
private fun RoundNav(icon: androidx.compose.ui.graphics.vector.ImageVector, description: String, enabled: Boolean, onClick: () -> Unit) {
    Box(Modifier.size(34.dp).clip(CircleShape).background(LyraColors.Background.copy(alpha = 0.7f)), contentAlignment = Alignment.Center) {
        IconBtn(icon, description, onClick, size = 34.dp, iconSize = 20.dp, enabled = enabled, tint = LyraColors.TextPrimary)
    }
}

/** La pantalla del centro (recuerda el desplazamiento de cada una al volver atrás). */
@Composable
private fun ScreenHost() {
    val nav = LocalActions.current.nav
    val holder = rememberSaveableStateHolder()
    val entry = nav.current
    LaunchedEffect(nav.dropped) { nav.consumeDropped().forEach { holder.removeState(it) } }
    holder.SaveableStateProvider(entry.id) {
        when (val screen = entry.screen) {
            Screen.Home -> HomeScreen()
            is Screen.Search -> SearchScreen(screen.query, screen.tab)
            is Screen.Album -> AlbumScreen(screen.id, screen.preview)
            is Screen.Artist -> ArtistScreen(screen.id, screen.preview)
            is Screen.RemotePlaylist -> RemotePlaylistScreen(screen.id, screen.preview)
            is Screen.LocalPlaylist -> LocalPlaylistScreen(screen.id)
            is Screen.Folder -> FolderScreen(screen.id)
            is Screen.SharedPlaylist -> SharedPlaylistScreen(screen.url)
            is Screen.Browse -> BrowseScreen(screen.endpoint, screen.title)
            is Screen.AllOfSection -> AllOfSectionScreen(screen.section)
            Screen.Liked -> LikedScreen()
            Screen.Downloads -> DownloadsScreen()
            Screen.History -> HistoryScreen()
            Screen.Settings -> SettingsScreen()
        }
    }
}

/** Avisos de abajo (uno a la vez, unos segundos). */
@Composable
private fun MessagesHost(modifier: Modifier) {
    val actions = LocalActions.current
    var current by remember { mutableStateOf<UiMessage?>(null) }
    LaunchedEffect(Unit) {
        merge(actions.messages, actions.app.player.messages.map { UiMessage(it) }).collect { message ->
            current = message
            delay(if (message.action != null) 5_000 else 3_200)
            if (current === message) current = null
        }
    }
    AnimatedVisibility(current != null, modifier = modifier, enter = fadeIn() + slideInVertically { it / 2 }, exit = fadeOut() + slideOutVertically { it / 2 }) {
        val message = current ?: return@AnimatedVisibility
        Row(
            Modifier
                .shadow(16.dp, RoundedCornerShape(10.dp))
                .clip(RoundedCornerShape(10.dp))
                .background(LyraColors.Accent)
                .padding(start = 18.dp, end = if (message.action != null) 6.dp else 18.dp, top = 10.dp, bottom = 10.dp)
                .widthIn(max = 640.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text(message.text, style = MaterialTheme.typography.bodyMedium, color = LyraColors.OnAccent, maxLines = 2, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false))
            if (message.action != null) {
                Spacer(Modifier.width(8.dp))
                TextButton(onClick = {
                    message.onAction?.invoke()
                    current = null
                }) { Text(message.action, style = MaterialTheme.typography.labelLarge, color = LyraColors.OnAccent) }
            }
        }
    }
}

// ---------------------------------------------------------------------- cuadros de diálogo

/** Cuadro centrado sobre la ventana, con el fondo oscurecido (clic fuera o Esc para cerrar). */
@Composable
fun OverlayDialog(onDismiss: () -> Unit, width: androidx.compose.ui.unit.Dp = 440.dp, content: @Composable () -> Unit) {
    Box(
        Modifier
            .fillMaxSize()
            .background(Color(0xB3000000))
            .clickable(remember { MutableInteractionSource() }, indication = null, onClick = onDismiss),
        contentAlignment = Alignment.Center,
    ) {
        Box(
            Modifier
                .width(width)
                .shadow(24.dp, RoundedCornerShape(14.dp))
                .clip(RoundedCornerShape(14.dp))
                .background(LyraColors.SurfaceHigh)
                .border(1.dp, LyraColors.Border, RoundedCornerShape(14.dp))
                .clickable(remember { MutableInteractionSource() }, indication = null) {}
                .padding(24.dp),
        ) {
            content()
        }
    }
}

@Composable
private fun Dialogs() {
    val actions = LocalActions.current
    actions.textRequest?.let { request ->
        var first by remember(request) { mutableStateOf(request.initial) }
        var second by remember(request) { mutableStateOf(request.secondInitial ?: "") }
        val focus = remember(request) { FocusRequester() }
        val confirm = {
            if (first.isNotBlank()) {
                actions.textRequest = null
                request.onConfirm(first.trim(), if (request.secondPlaceholder != null) second else null)
            }
        }
        OverlayDialog(onDismiss = { actions.textRequest = null }) {
            Column {
                Text(request.title, style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary)
                Spacer(Modifier.height(18.dp))
                LyraTextField(first, { first = it }, request.placeholder, Modifier.fillMaxWidth(), focus, onSubmit = confirm)
                if (request.secondPlaceholder != null) {
                    Spacer(Modifier.height(10.dp))
                    LyraTextField(second, { second = it }, request.secondPlaceholder, Modifier.fillMaxWidth().heightIn(min = 80.dp), singleLine = false)
                }
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.textRequest = null }) { Text("Cancelar", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
                    Spacer(Modifier.width(8.dp))
                    FilledPill(request.confirm, onClick = confirm, enabled = first.isNotBlank())
                }
            }
        }
        LaunchedEffect(request) { runCatching { focus.requestFocus() } }
    }
    actions.confirmRequest?.let { request ->
        OverlayDialog(onDismiss = { actions.confirmRequest = null }) {
            Column {
                Text(request.title, style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary)
                Spacer(Modifier.height(10.dp))
                Text(request.text, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
                Spacer(Modifier.height(22.dp))
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = { actions.confirmRequest = null }) { Text("Cancelar", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
                    Spacer(Modifier.width(8.dp))
                    FilledPill(request.confirm, onClick = {
                        actions.confirmRequest = null
                        request.onConfirm()
                    })
                }
            }
        }
    }
    actions.addToPlaylist?.let { songs -> AddToPlaylistDialog(songs) }
    actions.spotifyImport?.let { initial -> SpotifyImportDialog(initial) }
    WhatsNew()
}

/** Novedades: la primera vez que se abre una versión nueva, lo que trae (como en el móvil). */
@Composable
private fun WhatsNew() {
    val app = LocalActions.current.app
    var notes by remember { mutableStateOf<String?>(null) }
    LaunchedEffect(Unit) {
        val seen = app.settings.current.lastSeenVersion
        val version = com.lyra.desktop.BuildInfo.VERSION
        if (seen == version) return@LaunchedEffect
        app.settings.update { it.copy(lastSeenVersion = version) }
        // Recién instalada no hay «novedades»: solo después de actualizar.
        if (seen.isNotEmpty()) notes = app.updater.notesFor(version)
    }
    val text = notes ?: return
    OverlayDialog(onDismiss = { notes = null }, width = 520.dp) {
        Column {
            Text("Novedades de la ${com.lyra.desktop.BuildInfo.VERSION}", style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary)
            Spacer(Modifier.height(14.dp))
            Column(Modifier.heightIn(max = 420.dp).verticalScroll(rememberScrollState())) {
                text.lines().filter { it.isNotBlank() }.forEach { line ->
                    val bullet = line.trimStart().startsWith("- ")
                    Text(
                        if (bullet) "•  " + line.trimStart().removePrefix("- ") else line,
                        style = if (bullet) MaterialTheme.typography.bodyMedium else MaterialTheme.typography.titleSmall,
                        color = if (bullet) LyraColors.TextSecondary else LyraColors.TextPrimary,
                        modifier = Modifier.padding(bottom = 10.dp),
                    )
                }
            }
            Spacer(Modifier.height(10.dp))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                FilledPill("Genial", onClick = { notes = null })
            }
        }
    }
}

/** «Añadir a una playlist»: tus playlists (y crear una nueva con esas canciones). */
@Composable
private fun AddToPlaylistDialog(songs: List<com.lyra.music.data.model.Song>) {
    val actions = LocalActions.current
    val data by actions.app.library.data.collectAsState()
    var filter by remember { mutableStateOf("") }
    OverlayDialog(onDismiss = { actions.addToPlaylist = null }, width = 420.dp) {
        Column {
            Text("Añadir a una playlist", style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary)
            Text(
                if (songs.size == 1) songs.first().title else "${songs.size} canciones",
                style = MaterialTheme.typography.bodySmall,
                color = LyraColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(14.dp))
            SearchBox(filter, { filter = it }, "Buscar una playlist", Modifier.fillMaxWidth(), height = 38.dp)
            Spacer(Modifier.height(8.dp))
            LazyColumn(Modifier.heightIn(max = 380.dp)) {
                item {
                    HoverBox(onClick = {
                        actions.addToPlaylist = null
                        actions.createPlaylist(songs, openAfter = false)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            Box(Modifier.size(44.dp).clip(RoundedCornerShape(6.dp)).background(LyraColors.SurfaceHigher), contentAlignment = Alignment.Center) {
                                Icon(Icons.Rounded.Add, null, tint = LyraColors.TextPrimary)
                            }
                            Spacer(Modifier.width(12.dp))
                            Text("Nueva playlist", style = MaterialTheme.typography.bodyLarge, color = LyraColors.TextPrimary)
                        }
                    }
                }
                val playlists = data.playlists.sortedByDescending { it.updatedAt }
                    .filter { filter.isBlank() || it.name.contains(filter.trim(), ignoreCase = true) }
                items(playlists, key = { it.id }) { playlist ->
                    val has = songs.all { it.id in playlist.songIds }
                    HoverBox(onClick = {
                        actions.addToPlaylist = null
                        actions.addTo(playlist.id, songs)
                    }, modifier = Modifier.fillMaxWidth()) {
                        Row(Modifier.padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                            val custom = playlist.customCover ?: playlist.coverUrl
                            if (custom != null) Cover(custom, Modifier.size(44.dp)) else Mosaic(playlist.songIds.take(8).map { data.songs[it]?.thumbnailUrl }, Modifier.size(44.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(playlist.name, style = MaterialTheme.typography.bodyLarge, color = LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(
                                    if (has) "Ya está" else com.lyra.desktop.ui.screens.songCount(playlist.songIds.size),
                                    style = MaterialTheme.typography.bodySmall,
                                    color = if (has) LyraColors.Accent else LyraColors.TextSecondary,
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}
