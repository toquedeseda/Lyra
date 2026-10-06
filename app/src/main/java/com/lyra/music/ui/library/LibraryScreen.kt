package com.lyra.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.db.PlaylistSummary
import com.lyra.music.data.model.Song
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.ChipRow
import com.lyra.music.ui.components.EmptyView
import com.lyra.music.ui.components.GhostPillButton
import com.lyra.music.ui.components.InfoCard
import com.lyra.music.ui.components.ItemRow
import com.lyra.music.ui.components.Mosaic
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.components.SearchPill
import com.lyra.music.ui.components.SectionHeader
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.SpecialCover
import com.lyra.music.ui.components.TextInputDialog
import com.lyra.music.ui.components.matchesQuery
import com.lyra.music.ui.components.pressable
import com.lyra.music.ui.components.queryWords
import com.lyra.music.ui.components.searchText
import com.lyra.music.ui.navigation.DownloadsRoute
import com.lyra.music.ui.navigation.LikedRoute
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.theme.LyraColors
import com.lyra.music.ui.components.CoverFlight
import com.lyra.music.ui.components.flyingCover
import com.lyra.music.ui.components.rememberCoverTag
import com.lyra.music.data.repo.localPlaylistId
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.SwapVert
import com.lyra.music.data.repo.LibraryStats
import com.lyra.music.data.settings.LibrarySort
import com.lyra.music.core.plural

@UnstableApi
@Composable
fun LibraryScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val library = actions.container.library
    val playlists by library.playlistSummaries.collectAsState(initial = emptyList())
    val folders by library.folders.collectAsState(initial = emptyList())
    val albums by library.savedAlbums.collectAsState(initial = emptyList())
    val artists by library.followedArtists.collectAsState(initial = emptyList())
    val liked by library.likedIds.collectAsState()
    val sort = actions.container.settings.flow.collectAsState().value.librarySort
    // Se recalcula al entrar y cuando cambia algo; mientras, el orden de la última vez.
    val stats by produceState(library.cachedStats ?: LibraryStats(), playlists, folders, albums, artists) {
        value = library.libraryStats()
    }
    val sortedFolders = remember(folders, playlists, stats, sort) { stats.folders(folders, playlists, sort) }
    val sortedPlaylists = remember(playlists, stats, sort) { stats.playlists(playlists.filter { it.folderId == null }, sort) }
    val sortedAlbums = remember(albums, stats, sort) { stats.albums(albums, sort) }
    val sortedArtists = remember(artists, stats, sort) { stats.artists(artists, sort) }
    val downloads by actions.container.downloads.states.collectAsState()
    val completedDownloads = downloads.values.count { it.state == DownloadState.COMPLETED }

    var filter by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    var creatingFolder by remember { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    val searching = query.isNotBlank()
    val filters = listOf("Todo", "Playlists", "Álbumes", "Artistas")
    val showPlaylists = filter == 0 || filter == 1

    // Las canciones de la biblioteca (con su texto ya normalizado) solo se leen mientras buscas.
    val librarySongs by produceState<List<Pair<Song, String>>?>(null, searching) {
        if (searching) library.librarySongs.collect { songs -> value = songs.map { it to it.searchText() } }
    }
    val songMatches = remember(librarySongs, query) {
        val words = queryWords(query)
        librarySongs.orEmpty().filter { (_, text) -> words.all { it in text } }.map { it.first }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Row(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 22.dp),
                verticalAlignment = Alignment.Top,
            ) {
                Column(Modifier.weight(1f)) {
                    Text("Tu biblioteca", style = MaterialTheme.typography.displayMedium)
                    Text(
                        "Favoritas, descargas y tus listas.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LyraColors.TextSecondary,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
                Box(Modifier.padding(top = 10.dp)) {
                    var menu by remember { mutableStateOf(false) }
                    GhostPillButton("Nueva", onClick = { menu = true }, icon = Icons.Rounded.Add)
                    DropdownMenu(expanded = menu, onDismissRequest = { menu = false }, containerColor = LyraColors.SurfaceHigh) {
                        DropdownMenuItem(text = { Text("Nueva playlist") }, onClick = { menu = false; creating = true })
                        DropdownMenuItem(text = { Text("Nueva carpeta") }, onClick = { menu = false; creatingFolder = true })
                        DropdownMenuItem(text = { Text("Importar de Spotify") }, onClick = { menu = false; actions.importDialog = "" })
                    }
                }
            }
        }
        item {
            SearchPill(
                value = query,
                onValueChange = { query = it },
                placeholder = "Buscar en tu biblioteca",
                modifier = Modifier
                    .padding(start = 20.dp, end = 20.dp, top = 20.dp)
                    .fillMaxWidth(),
            )
        }

        if (searching) {
            val text = query.trim()
            val folderMatches = folders.filter { matchesQuery(text, it.name) }
            val playlistMatches = playlists.filter { matchesQuery(text, it.name, it.description) }
            val albumMatches = albums.filter { matchesQuery(text, it.title, it.artistsText, it.year) }
            val artistMatches = artists.filter { matchesQuery(text, it.title) }
            val nothing = songMatches.isEmpty() && folderMatches.isEmpty() && playlistMatches.isEmpty() &&
                albumMatches.isEmpty() && artistMatches.isEmpty()

            if (nothing && librarySongs != null) {
                item {
                    EmptyView(
                        Icons.Rounded.SearchOff,
                        "Nada con «$text»",
                        "No está en tus favoritas, descargas ni listas.",
                        modifier = Modifier.padding(top = 12.dp),
                        action = "Buscarlo en Lyra",
                        onAction = { actions.searchOnline(text) },
                    )
                }
            }
            if (songMatches.isNotEmpty()) {
                item { SectionHeader("Canciones", if (songMatches.size == 1) "1 canción" else "${songMatches.size} canciones") }
                itemsIndexed(songMatches.take(100), key = { _, song -> "s${song.id}" }) { index, song ->
                    SongRow(song, onClick = { actions.play(songMatches, index, fromLabel = "Tu biblioteca") })
                }
            }
            if (folderMatches.isNotEmpty()) {
                item { SectionHeader("Carpetas") }
                items(folderMatches, key = { "f${it.id}" }) { FolderRow(it) }
            }
            if (playlistMatches.isNotEmpty()) {
                item { SectionHeader("Playlists") }
                items(playlistMatches, key = { "p${it.id}" }) { PlaylistRow(it) }
            }
            if (albumMatches.isNotEmpty()) {
                item { SectionHeader("Álbumes") }
                items(albumMatches, key = { "a${it.id}" }) { ItemRow(it) }
            }
            if (artistMatches.isNotEmpty()) {
                item { SectionHeader("Artistas") }
                items(artistMatches, key = { "r${it.id}" }) { ItemRow(it) }
            }
            if (!nothing) {
                item {
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .pressable(pressedScale = 0.985f) { actions.searchOnline(text) }
                            .padding(horizontal = 20.dp, vertical = 18.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Icon(Icons.Outlined.Search, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(20.dp))
                        Spacer(Modifier.width(14.dp))
                        Text(
                            "Buscar «$text» en YouTube Music y SoundCloud",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LyraColors.TextSecondary,
                        )
                    }
                }
            }
            return@LazyColumn
        }

        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 20.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PinnedCard(Icons.Rounded.Favorite, true, "Canciones que te gustan", plural(liked.size, "tema", "temas")) {
                    actions.nav.navigate(LikedRoute)
                }
                PinnedCard(Icons.Rounded.ArrowDownward, false, "Descargas", plural(completedDownloads, "tema", "temas") + " sin conexión") {
                    actions.nav.navigate(DownloadsRoute)
                }
            }
        }
        item { ChipRow(filters, filter, { filter = it }, Modifier.padding(top = 22.dp)) }
        item {
            SortButton(sort) { option ->
                actions.launch { actions.container.settings.update { it.copy(librarySort = option) } }
            }
        }

        if (showPlaylists) {
            item { SectionHeader("Tus listas") }
            if (playlists.isEmpty() && folders.isEmpty()) {
                item {
                    InfoCard(
                        title = "Aún no tienes listas",
                        text = "Crea una aquí, guarda cualquier playlist de YouTube Music o SoundCloud con el botón + de su página, o impórtala de Spotify.",
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        PillButton("Nueva playlist", onClick = { creating = true })
                        Spacer(Modifier.width(8.dp))
                        GhostPillButton("Importar de Spotify", onClick = { actions.importDialog = "" })
                    }
                }
            }
            // Primero las carpetas y después las playlists que no están en ninguna.
            items(sortedFolders, key = { "f${it.id}" }) { FolderRow(it, Modifier.animateItem()) }
            items(sortedPlaylists, key = { "p${it.id}" }) { PlaylistRow(it, Modifier.animateItem()) }
        }
        if ((filter == 0 && albums.isNotEmpty()) || filter == 2) {
            item { SectionHeader("Álbumes") }
            if (albums.isEmpty()) {
                item {
                    InfoCard(
                        "Sin álbumes guardados",
                        "Guarda un álbum con el botón + de su página y aparecerá aquí.",
                        Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(sortedAlbums, key = { "a${it.id}" }) { album -> ItemRow(album, Modifier.animateItem()) }
        }
        if ((filter == 0 && artists.isNotEmpty()) || filter == 3) {
            item { SectionHeader("Artistas") }
            if (artists.isEmpty()) {
                item {
                    InfoCard(
                        "No sigues a nadie todavía",
                        "Pulsa «Seguir» en la página de un artista.",
                        Modifier.padding(horizontal = 20.dp),
                    )
                }
            }
            items(sortedArtists, key = { "r${it.id}" }) { artist -> ItemRow(artist, Modifier.animateItem()) }
        }
    }

    if (creatingFolder) {
        TextInputDialog(
            title = "Nueva carpeta",
            initial = "",
            placeholder = "Nombre de la carpeta",
            confirm = "Crear",
            onDismiss = { creatingFolder = false },
            onConfirm = { name ->
                creatingFolder = false
                actions.launch {
                    val id = actions.container.library.createFolder(name)
                    actions.nav.navigate(com.lyra.music.ui.navigation.FolderRoute(id))
                }
            },
        )
    }
    if (creating) {
        TextInputDialog(
            title = "Nueva playlist",
            initial = "",
            placeholder = "Nombre de la playlist",
            confirm = "Crear",
            onDismiss = { creating = false },
            onConfirm = { name ->
                creating = false
                actions.launch {
                    val id = actions.container.library.createPlaylist(name)
                    actions.nav.navigate(LocalPlaylistRoute(id))
                }
            },
        )
    }
}

@UnstableApi
@Composable
fun PlaylistRow(playlist: PlaylistSummary, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val tag = rememberCoverTag(localPlaylistId(playlist.id))
    Row(
        modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f) {
                CoverFlight.take(tag, playlist.customCover ?: playlist.coverUrl)
                actions.nav.navigate(LocalPlaylistRoute(playlist.id))
            }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        PlaylistCover(playlist, Modifier.size(56.dp).flyingCover(tag, RoundedCornerShape(10.dp)))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val origin = when {
                playlist.remoteId?.startsWith("spotify:") == true -> " · De Spotify"
                playlist.remoteId != null -> " · Importada"
                else -> ""
            }
            Text(
                "Playlist · ${plural(playlist.songCount, "tema", "temas")}$origin" + if (playlist.syncEnabled) " · Sincronizada" else "",
                style = MaterialTheme.typography.bodySmall,
                color = LyraColors.TextSecondary,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** «⇅ Recientes»: cambia el orden de la biblioteca (se recuerda). */
@Composable
private fun SortButton(sort: LibrarySort, onPick: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box(Modifier.padding(start = 10.dp, top = 12.dp)) {
        Row(
            Modifier
                .clip(RoundedCornerShape(50))
                .pressable { open = true }
                .padding(horizontal = 10.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Icon(Icons.Rounded.SwapVert, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(6.dp))
            Text(sort.label, style = MaterialTheme.typography.labelLarge, color = LyraColors.TextPrimary)
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LyraColors.SurfaceHigh) {
            Text(
                "Ordenar por",
                style = MaterialTheme.typography.bodySmall,
                color = LyraColors.TextSecondary,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
            )
            LibrarySort.entries.forEach { option ->
                val selected = option == sort
                DropdownMenuItem(
                    text = { Text(option.label, color = if (selected) LyraColors.Accent else LyraColors.TextPrimary) },
                    trailingIcon = { if (selected) Icon(Icons.Rounded.Check, null, tint = LyraColors.Accent, modifier = Modifier.size(18.dp)) },
                    onClick = {
                        open = false
                        if (!selected) onPick(option)
                    },
                )
            }
        }
    }
}

/** Tarjeta fija de la biblioteca, como "Canciones que te gustan" en la web. */
@Composable
private fun PinnedCard(icon: ImageVector, filled: Boolean, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LyraColors.SurfaceHigh)
            .border(1.dp, LyraColors.Border, RoundedCornerShape(16.dp))
            .pressable(onClick = onClick)
            .padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpecialCover(icon, Modifier.size(64.dp), RoundedCornerShape(12.dp), filled = filled)
        Spacer(Modifier.width(16.dp))
        Column {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
        }
    }
}
