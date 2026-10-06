package com.lyra.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionLayout
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.LibraryBooks
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import com.lyra.music.ui.components.bounceOnChange
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.Row
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.border
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.expandVertically
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavDestination.Companion.hasRoute
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.lyra.music.AppContainer
import com.lyra.music.AppEvent
import com.lyra.music.ui.album.AlbumScreen
import com.lyra.music.ui.artist.ArtistScreen
import com.lyra.music.ui.browse.BrowseScreen
import com.lyra.music.ui.components.AddToPlaylistSheet
import com.lyra.music.ui.components.SongMenuSheet
import com.lyra.music.ui.home.HomeScreen
import com.lyra.music.ui.library.DownloadsScreen
import com.lyra.music.ui.library.HistoryScreen
import com.lyra.music.ui.library.LibraryScreen
import com.lyra.music.ui.library.LikedScreen
import com.lyra.music.ui.library.LocalPlaylistScreen
import com.lyra.music.ui.navigation.AlbumRoute
import com.lyra.music.ui.navigation.ArtistRoute
import com.lyra.music.ui.navigation.BrowseRoute
import com.lyra.music.ui.navigation.DownloadsRoute
import com.lyra.music.ui.navigation.EqualizerRoute
import com.lyra.music.ui.navigation.HistoryRoute
import com.lyra.music.ui.navigation.HomeRoute
import com.lyra.music.ui.navigation.IslandRoute
import com.lyra.music.ui.navigation.LibraryRoute
import com.lyra.music.ui.navigation.LikedRoute
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.navigation.PlaylistRoute
import com.lyra.music.ui.navigation.SearchRoute
import com.lyra.music.ui.navigation.SettingsRoute
import com.lyra.music.ui.player.MiniPlayer
import com.lyra.music.ui.player.NowPlayingScreen
import com.lyra.music.ui.playlist.RemotePlaylistScreen
import com.lyra.music.ui.search.SearchScreen
import com.lyra.music.ui.settings.EqualizerScreen
import com.lyra.music.ui.settings.IslandScreen
import com.lyra.music.ui.settings.SettingsScreen
import com.lyra.music.ui.theme.LyraColors
import com.lyra.music.ui.update.UpdateDialog
import com.lyra.music.ui.update.WhatsNewDialog
import com.lyra.music.update.UpdateState
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import androidx.navigation.NavBackStackEntry
import androidx.navigation.NavGraphBuilder
import kotlinx.coroutines.delay
import com.lyra.music.ui.components.LocalNavScope
import com.lyra.music.ui.components.LocalScreenTransition
import com.lyra.music.ui.components.LocalSharedScope
import com.lyra.music.ui.components.enterFor
import com.lyra.music.ui.components.exitFor
import com.lyra.music.ui.components.popEnterFor
import com.lyra.music.ui.components.popExitFor

private data class Tab(val route: Any, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(HomeRoute, "Inicio", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(SearchRoute, "Buscar", Icons.Outlined.Search, Icons.Rounded.Search),
    Tab(LibraryRoute, "Biblioteca", Icons.AutoMirrored.Outlined.LibraryBooks, Icons.AutoMirrored.Rounded.LibraryBooks),
)

/** Curva suave de salida, parecida a la de iOS. */
private val SmoothOut = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

@OptIn(ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
fun LyraRoot(container: AppContainer) {
    val nav = rememberNavController()
    val snackbar = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    val actions = remember { LyraActions(container, nav, snackbar, scope, context) }

    val playerState by container.player.state.collectAsState()
    val liked by container.library.likedIds.collectAsState()
    val downloads by container.downloads.states.collectAsState()
    val settings by container.settings.flow.collectAsState()
    val updateState by container.updates.state.collectAsState()
    val online by container.network.online.collectAsState()
    val libraryState = LibraryUiState(liked, downloads, playerState.song?.id, playerState.isPlaying, online)

    // Eventos de fuera (notificación, isla, compartir enlaces…).
    LaunchedEffect(Unit) {
        container.events.collect { event ->
            when (event) {
                AppEvent.OpenPlayer -> actions.nowPlayingOpen = true
                AppEvent.OpenDownloads -> nav.navigate(DownloadsRoute)
                is AppEvent.OpenLink -> actions.openLink(event.url)
                AppEvent.OpenUpdate -> container.updates.check(force = true)
                AppEvent.UpdateNow -> container.updates.checkAndInstall()
                is AppEvent.OpenAlbum -> nav.navigate(AlbumRoute(event.id))
                is AppEvent.OpenLocalPlaylist -> nav.navigate(LocalPlaylistRoute(event.id))
            }
        }
    }

    // Si la importación de Spotify termina con el diálogo cerrado, se avisa abajo.
    val importState by container.spotifyImport.state.collectAsState()
    LaunchedEffect(importState, actions.importDialog) {
        if (actions.importDialog != null) return@LaunchedEffect
        when (val s = importState) {
            is com.lyra.music.data.repo.ImportState.Done -> {
                container.spotifyImport.dismiss()
                actions.message("«${s.name}» importada · ${s.found} canciones", "Abrir") { nav.navigate(LocalPlaylistRoute(s.playlistId)) }
            }
            is com.lyra.music.data.repo.ImportState.Failed -> {
                container.spotifyImport.dismiss()
                actions.message("No se pudo importar: ${s.reason}")
            }
            else -> Unit
        }
    }

    // Si la última vez Lyra se cerró de golpe, se ofrece ver el informe.
    var crash by remember { mutableStateOf<com.lyra.music.core.ErrorEntry?>(null) }
    LaunchedEffect(Unit) { crash = com.lyra.music.core.ErrorLog.takePendingCrash() }

    // Actualizaciones al abrir y "Novedades" tras actualizar.
    var whatsNew by remember { mutableStateOf<Pair<String, String>?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(settings.loaded, settings.checkUpdates) {
        if (!settings.loaded || !settings.checkUpdates) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            while (true) {
                container.updates.check()
                delay(5 * 60_000L)
            }
        }
    }
    LaunchedEffect(settings.loaded) {
        if (!settings.loaded) return@LaunchedEffect
        val current = container.updates.currentVersion
        if (settings.lastSeenVersion != current) {
            if (settings.lastSeenVersion.isNotEmpty()) {
                container.updates.notesFor(current)?.let { whatsNew = current to it }
            }
            container.settings.update { it.copy(lastSeenVersion = current) }
        }
    }

    CompositionLocalProvider(
        LocalActions provides actions,
        LocalLibraryState provides libraryState,
        // Todo lo que no está dentro del Scaffold (reproductor, menús…) hereda el blanco hueso.
        LocalContentColor provides LyraColors.TextPrimary,
    ) {
        SharedTransitionLayout(
            Modifier
                .fillMaxSize()
                .background(LyraColors.Background),
        ) {
            val shared = this
            CompositionLocalProvider(
                LocalSharedScope provides shared,
                LocalScreenTransition provides settings.screenTransition,
            ) {
                Box(Modifier.fillMaxSize()) {
                    Scaffold(
                        containerColor = LyraColors.Background,
                        snackbarHost = {
                            SnackbarHost(snackbar) { data ->
                                Snackbar(
                                    data,
                                    shape = RoundedCornerShape(14.dp),
                                    containerColor = LyraColors.SurfaceHigher,
                                    contentColor = LyraColors.TextPrimary,
                                    actionColor = LyraColors.Accent,
                                )
                            }
                        },
                        bottomBar = { BottomBar(actions, shared) },
                    ) { padding ->
                        NavHost(
                            navController = nav,
                            startDestination = HomeRoute,
                            enterTransition = { enterFor(settings.screenTransition) },
                            exitTransition = { exitFor(settings.screenTransition) },
                            popEnterTransition = { popEnterFor(settings.screenTransition) },
                            popExitTransition = { popExitFor(settings.screenTransition) },
                        ) {
                            screen<HomeRoute> { HomeScreen(padding) }
                            screen<SearchRoute> { SearchScreen(padding) }
                            screen<LibraryRoute> { LibraryScreen(padding) }
                            screen<AlbumRoute> { AlbumScreen(it.toRoute<AlbumRoute>().id, padding) }
                            screen<ArtistRoute> { ArtistScreen(it.toRoute<ArtistRoute>().id, padding) }
                            screen<PlaylistRoute> { RemotePlaylistScreen(it.toRoute<PlaylistRoute>().id, padding) }
                            screen<LocalPlaylistRoute> { LocalPlaylistScreen(it.toRoute<LocalPlaylistRoute>().id, padding) }
                            screen<com.lyra.music.ui.navigation.ErrorsRoute> { com.lyra.music.ui.settings.ErrorsScreen(padding) }
                            screen<com.lyra.music.ui.navigation.FolderRoute> {
                                com.lyra.music.ui.library.FolderScreen(it.toRoute<com.lyra.music.ui.navigation.FolderRoute>().id, padding)
                            }
                            screen<BrowseRoute> {
                                val route = it.toRoute<BrowseRoute>()
                                BrowseScreen(route.browseId, route.params, route.title, padding)
                            }
                            screen<LikedRoute> { LikedScreen(padding) }
                            screen<DownloadsRoute> { DownloadsScreen(padding) }
                            screen<HistoryRoute> { HistoryScreen(padding) }
                            screen<SettingsRoute> { SettingsScreen(padding) }
                            screen<EqualizerRoute> { EqualizerScreen(padding) }
                            screen<IslandRoute> { IslandScreen(padding) }
                        }
                    }

                    // Franja oscura tras la barra de estado para que el contenido no se mezcle con la hora.
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .windowInsetsTopHeight(WindowInsets.statusBars)
                            .background(LyraColors.Background.copy(alpha = 0.75f)),
                    )

                    AnimatedVisibility(
                        visible = actions.nowPlayingOpen,
                        enter = slideInVertically(tween(420, easing = SmoothOut)) { it / 3 } + fadeIn(tween(260)),
                        exit = slideOutVertically(tween(320)) { it / 3 } + fadeOut(tween(220)),
                    ) {
                        NowPlayingScreen(
                            onClose = { actions.nowPlayingOpen = false },
                            sharedScope = shared,
                            visibilityScope = this,
                        )
                    }

                    actions.songMenu?.let { request -> SongMenuSheet(request, onDismiss = { actions.songMenu = null }) }
                    actions.addToPlaylist?.let { songs -> AddToPlaylistSheet(songs, onDismiss = { actions.addToPlaylist = null }) }
                    actions.shareCard?.let { song ->
                        com.lyra.music.ui.share.ShareCardDialog(song, onDismiss = { actions.shareCard = null })
                    }
                    actions.importDialog?.let { url ->
                        com.lyra.music.ui.components.SpotifyImportDialog(url, onDismiss = { actions.importDialog = null })
                    }

                    when (val state = updateState) {
                        is UpdateState.Available, is UpdateState.Downloading, is UpdateState.Installing,
                        is UpdateState.NeedsPermission, is UpdateState.Failed -> {
                            if (state is UpdateState.Failed && state.info == null) {
                                // Fallo al comprobar: solo se avisa si lo pidió el usuario.
                                LaunchedEffect(state) {
                                    if (state.manual) actions.message("No se pudo comprobar: ${state.reason}")
                                    container.updates.dismiss()
                                }
                            } else {
                                UpdateDialog(
                                    state = state,
                                    currentVersion = container.updates.currentVersion,
                                    onUpdate = container.updates::downloadAndInstall,
                                    onOpenPermission = { context.startActivity(container.updates.permissionIntent()) },
                                    onDismiss = container.updates::dismiss,
                                )
                            }
                        }
                        is UpdateState.UpToDate -> LaunchedEffect(state) {
                            if (state.manual) actions.message("Tienes la última versión")
                            container.updates.dismiss()
                        }
                        else -> Unit
                    }
                    whatsNew?.let { (version, notes) -> WhatsNewDialog(version, notes, onDismiss = { whatsNew = null }) }
                    crash?.let {
                        androidx.compose.material3.AlertDialog(
                            onDismissRequest = { crash = null },
                            containerColor = LyraColors.Surface,
                            title = { Text("Lyra se cerró de golpe", style = MaterialTheme.typography.headlineMedium) },
                            text = {
                                Text(
                                    "Ha quedado apuntado en el informe de errores. Si lo copias y lo pasas, se puede arreglar.",
                                    color = LyraColors.TextSecondary,
                                )
                            },
                            confirmButton = {
                                androidx.compose.material3.TextButton(onClick = {
                                    crash = null
                                    nav.navigate(com.lyra.music.ui.navigation.ErrorsRoute)
                                }) { Text("Ver informe", color = LyraColors.Accent) }
                            },
                            dismissButton = {
                                androidx.compose.material3.TextButton(onClick = { crash = null }) { Text("Ahora no", color = LyraColors.TextSecondary) }
                            },
                        )
                    }
                }
            }
        }
    }
}

@OptIn(ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
private fun BottomBar(actions: LyraActions, shared: SharedTransitionScope) {
    val backStack by actions.nav.currentBackStackEntryAsState()
    val destination = backStack?.destination
    Column(
        Modifier.background(
            Brush.verticalGradient(
                0f to Color.Transparent,
                0.4f to LyraColors.Background.copy(alpha = 0.92f),
                1f to LyraColors.Background,
            ),
        ),
    ) {
        val online by actions.container.network.online.collectAsState()
        AnimatedVisibility(
            visible = !online,
            enter = expandVertically() + fadeIn(),
            exit = shrinkVertically() + fadeOut(),
        ) {
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                horizontalArrangement = Arrangement.Center,
            ) {
                Row(
                    Modifier
                        .clip(RoundedCornerShape(50))
                        .background(LyraColors.SurfaceHigh)
                        .border(1.dp, LyraColors.Border, RoundedCornerShape(50))
                        .padding(horizontal = 14.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.CloudOff, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(15.dp))
                    Spacer(Modifier.width(8.dp))
                    Text("Sin conexión · suena lo descargado", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary)
                }
            }
        }
        MiniPlayer(sharedScope = shared)
        // Línea finísima sobre la barra, como en la web.
        Box(
            Modifier
                .fillMaxWidth()
                .height(1.dp)
                .background(LyraColors.Border),
        )
        NavigationBar(containerColor = LyraColors.Background, tonalElevation = 0.dp) {
            tabs.forEach { tab ->
                val selected = destination?.hierarchy?.any { it.hasRoute(tab.route::class) } == true
                NavigationBarItem(
                    selected = selected,
                    onClick = {
                        actions.nav.navigate(tab.route) {
                            popUpTo(actions.nav.graph.findStartDestination().id) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    },
                    icon = {
                        Icon(
                            if (selected) tab.selectedIcon else tab.icon,
                            tab.label,
                            modifier = Modifier.bounceOnChange(selected, onlyWhen = selected),
                        )
                    },
                    label = { Text(tab.label, style = MaterialTheme.typography.labelMedium) },
                    colors = NavigationBarItemDefaults.colors(
                        selectedIconColor = LyraColors.TextPrimary,
                        selectedTextColor = LyraColors.TextPrimary,
                        unselectedIconColor = LyraColors.TextTertiary,
                        unselectedTextColor = LyraColors.TextTertiary,
                        indicatorColor = Color.Transparent,
                    ),
                )
            }
        }
    }
}

/** Destino del NavHost que da a su pantalla la animación con la que entra y sale. */
private inline fun <reified T : Any> NavGraphBuilder.screen(
    noinline content: @Composable (NavBackStackEntry) -> Unit,
) {
    composable<T> { entry ->
        CompositionLocalProvider(LocalNavScope provides this) { content(entry) }
    }
}
