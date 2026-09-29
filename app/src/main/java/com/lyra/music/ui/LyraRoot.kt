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
    val libraryState = LibraryUiState(liked, downloads, playerState.song?.id, playerState.isPlaying)

    // Eventos de fuera (notificación, isla, compartir enlaces…).
    LaunchedEffect(Unit) {
        container.events.collect { event ->
            when (event) {
                AppEvent.OpenPlayer -> actions.nowPlayingOpen = true
                AppEvent.OpenDownloads -> nav.navigate(DownloadsRoute)
                is AppEvent.OpenLink -> actions.openLink(event.url)
            }
        }
    }

    // Actualizaciones al abrir y "Novedades" tras actualizar.
    var whatsNew by remember { mutableStateOf<Pair<String, String>?>(null) }
    LaunchedEffect(settings.loaded) {
        if (!settings.loaded) return@LaunchedEffect
        if (settings.checkUpdates) container.updates.check()
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
                        enterTransition = { fadeIn(tween(240)) + slideInHorizontally(tween(320, easing = SmoothOut)) { it / 14 } },
                        exitTransition = { fadeOut(tween(160)) },
                        popEnterTransition = { fadeIn(tween(240)) },
                        popExitTransition = { fadeOut(tween(180)) + slideOutHorizontally(tween(240)) { it / 14 } },
                    ) {
                        composable<HomeRoute> { HomeScreen(padding) }
                        composable<SearchRoute> { SearchScreen(padding) }
                        composable<LibraryRoute> { LibraryScreen(padding) }
                        composable<AlbumRoute> { AlbumScreen(it.toRoute<AlbumRoute>().id, padding) }
                        composable<ArtistRoute> { ArtistScreen(it.toRoute<ArtistRoute>().id, padding) }
                        composable<PlaylistRoute> { RemotePlaylistScreen(it.toRoute<PlaylistRoute>().id, padding) }
                        composable<LocalPlaylistRoute> { LocalPlaylistScreen(it.toRoute<LocalPlaylistRoute>().id, padding) }
                        composable<BrowseRoute> {
                            val route = it.toRoute<BrowseRoute>()
                            BrowseScreen(route.browseId, route.params, route.title, padding)
                        }
                        composable<LikedRoute> { LikedScreen(padding) }
                        composable<DownloadsRoute> { DownloadsScreen(padding) }
                        composable<HistoryRoute> { HistoryScreen(padding) }
                        composable<SettingsRoute> { SettingsScreen(padding) }
                        composable<EqualizerRoute> { EqualizerScreen(padding) }
                        composable<IslandRoute> { IslandScreen(padding) }
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
                    icon = { Icon(if (selected) tab.selectedIcon else tab.icon, tab.label) },
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
