package com.lyra.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.LibraryBooks
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SwapVert
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.VerticalScrollbar
import com.lyra.desktop.data.Library
import com.lyra.desktop.data.LibraryEntry
import com.lyra.desktop.data.LibrarySort
import com.lyra.desktop.player.PlayContext
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.FilterChip
import com.lyra.desktop.ui.components.HoverBox
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LibraryRow
import com.lyra.desktop.ui.components.MenuDivider
import com.lyra.desktop.ui.components.MenuEntry
import com.lyra.desktop.ui.components.MenuState
import com.lyra.desktop.ui.components.Mosaic
import com.lyra.desktop.ui.components.SearchBox
import com.lyra.desktop.ui.components.SpecialCover
import com.lyra.desktop.ui.components.LocalNowPlaying
import com.lyra.desktop.ui.components.LyraMenu
import com.lyra.music.data.model.PlaylistItem
import com.lyra.desktop.ui.screens.songCount

/** La barra de la izquierda: Inicio, Buscar y tu biblioteca, como en Spotify. */
@Composable
fun Sidebar(modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val nav = actions.nav
    Column(modifier) {
        Column(
            Modifier.fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LyraColors.Surface).padding(vertical = 8.dp, horizontal = 8.dp),
        ) {
            val screen = nav.current.screen
            NavEntry("Inicio", if (screen == Screen.Home) Icons.Rounded.Home else Icons.Outlined.Home, screen == Screen.Home) { nav.navigate(Screen.Home) }
            NavEntry("Buscar", if (screen is Screen.Search) Icons.Rounded.Search else Icons.Outlined.Search, screen is Screen.Search) {
                if (screen !is Screen.Search) nav.navigate(Screen.Search())
                actions.focusSearch++
            }
        }
        Spacer(Modifier.height(8.dp))
        LibraryPanel(Modifier.weight(1f).fillMaxWidth().clip(RoundedCornerShape(10.dp)).background(LyraColors.Surface))
    }
}

@Composable
private fun NavEntry(label: String, icon: ImageVector, selected: Boolean, onClick: () -> Unit) {
    HoverBox(onClick = onClick, modifier = Modifier.fillMaxWidth()) { hovered ->
        Row(Modifier.padding(horizontal = 12.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(icon, null, tint = if (selected || hovered) LyraColors.TextPrimary else LyraColors.TextSecondary, modifier = Modifier.size(24.dp))
            Spacer(Modifier.width(16.dp))
            Text(label, style = MaterialTheme.typography.titleMedium, color = if (selected || hovered) LyraColors.TextPrimary else LyraColors.TextSecondary)
        }
    }
}

@Composable
private fun LibraryPanel(modifier: Modifier) {
    val actions = LocalActions.current
    val app = actions.app
    val data by app.library.data.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val downloads by app.downloads.entries.collectAsState()
    val now = LocalNowPlaying.current
    val playerState by app.player.state.collectAsState()
    var filterText by remember { mutableStateOf("") }
    var searching by remember { mutableStateOf(false) }
    val filter = settings.libraryFilter
    val sort = settings.librarySort
    val entries = remember(data, sort) { app.library.entries(data, sort) }
    val shown = entries.filter { entry ->
        val byType = when (filter) {
            1 -> entry is LibraryEntry.Playlist || entry is LibraryEntry.Folder
            2 -> entry is LibraryEntry.Album
            3 -> entry is LibraryEntry.Artist
            else -> true
        }
        byType && (filterText.isBlank() || entry.name.contains(filterText.trim(), ignoreCase = true))
    }
    val contextId = playerState.context?.id

    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.AutoMirrored.Rounded.LibraryBooks, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(22.dp))
            Spacer(Modifier.width(12.dp))
            Text("Tu biblioteca", style = MaterialTheme.typography.titleMedium, color = LyraColors.TextPrimary, modifier = Modifier.weight(1f))
            Box {
                val menu = remember { MenuState() }
                IconBtn(Icons.Rounded.Add, "Crear", onClick = { menu.open = true })
                LyraMenu(menu) { close ->
                    MenuEntry("Nueva playlist", Icons.Rounded.Add, { close(); actions.createPlaylist() })
                    MenuEntry("Nueva carpeta", Icons.Rounded.Folder, { close(); actions.createFolder() })
                    MenuEntry("Importar de Spotify", Icons.Rounded.Link, { close(); actions.spotifyImport = "" })
                }
            }
        }
        Row(Modifier.padding(horizontal = 12.dp, vertical = 6.dp)) {
            listOf("Playlists", "Álbumes", "Artistas").forEachIndexed { i, label ->
                FilterChip(label, filter == i + 1, onClick = { app.settings.update { it.copy(libraryFilter = if (filter == i + 1) 0 else i + 1) } })
                Spacer(Modifier.width(8.dp))
            }
        }
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if (searching) {
                SearchBox(filterText, { filterText = it }, "Buscar en tu biblioteca", Modifier.weight(1f), height = 34.dp, onEscape = { searching = false; filterText = "" })
            } else {
                IconBtn(Icons.Rounded.Search, "Buscar en tu biblioteca", onClick = { searching = true })
                Spacer(Modifier.weight(1f))
            }
            SortButton(sort) { option -> app.settings.update { it.copy(librarySort = option) } }
        }
        val listState = rememberLazyListState()
        Box(Modifier.weight(1f)) {
            LazyColumn(state = listState, modifier = Modifier.fillMaxSize().padding(horizontal = 4.dp), contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)) {
                if (filter == 0 || filter == 1) {
                    item(key = "liked") {
                        LibraryRow(
                            title = "Canciones que te gustan",
                            subtitle = "Playlist · " + songCount(data.liked.size),
                            cover = { SpecialCover(Icons.Rounded.Favorite, it) },
                            onClick = { actions.nav.navigate(Screen.Liked) },
                            selected = actions.nav.current.screen == Screen.Liked,
                            playing = contextId == Library.LIKED_ID && now.playing,
                        )
                    }
                    item(key = "downloads") {
                        val count = downloads.values.count { it.status == com.lyra.desktop.data.DownloadStatus.DONE }
                        LibraryRow(
                            title = "Descargas",
                            subtitle = songCount(count) + " sin conexión",
                            cover = { SpecialCover(Icons.Rounded.ArrowDownward, it, filled = false) },
                            onClick = { actions.nav.navigate(Screen.Downloads) },
                            selected = actions.nav.current.screen == Screen.Downloads,
                            playing = contextId == Library.DOWNLOADS_ID && now.playing,
                        )
                    }
                    item(key = "history") {
                        LibraryRow(
                            title = "Historial",
                            subtitle = "Lo que has escuchado",
                            cover = { SpecialCover(Icons.Rounded.History, it, filled = false) },
                            onClick = { actions.nav.navigate(Screen.History) },
                            selected = actions.nav.current.screen == Screen.History,
                            playing = false,
                        )
                    }
                }
                items(shown, key = { it.key }) { entry -> LibraryEntryRow(entry, contextId, now.playing) }
                if (shown.isEmpty() && data.playlists.isEmpty() && data.albums.isEmpty() && data.artists.isEmpty()) {
                    item {
                        Column(Modifier.padding(16.dp)) {
                            Text("Crea tu primera playlist", style = MaterialTheme.typography.titleSmall, color = LyraColors.TextPrimary)
                            Spacer(Modifier.height(4.dp))
                            Text("Con el botón +, o guarda álbumes, artistas y playlists desde sus páginas.", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
                        }
                    }
                }
            }
            VerticalScrollbar(rememberScrollbarAdapter(listState), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp).padding(end = 2.dp))
        }
    }
}

@Composable
private fun LibraryEntryRow(entry: LibraryEntry, contextId: String?, playing: Boolean) {
    val actions = LocalActions.current
    val app = actions.app
    val nav = actions.nav
    val data = app.library.current
    val menu = remember { MenuState() }
    when (entry) {
        is LibraryEntry.Folder -> LibraryRow(
            title = entry.folder.name,
            subtitle = "Carpeta · ${entry.playlists.size} " + if (entry.playlists.size == 1) "playlist" else "playlists",
            cover = { SpecialCover(Icons.Rounded.Folder, it, filled = false) },
            onClick = { nav.navigate(Screen.Folder(entry.folder.id)) },
            selected = (nav.current.screen as? Screen.Folder)?.id == entry.folder.id,
            playing = false,
            menu = menu,
        ) { close ->
            MenuEntry("Renombrar", Icons.Rounded.Edit, {
                close()
                actions.textRequest = TextRequest("Renombrar carpeta", entry.folder.name, "Nombre", "Guardar") { name, _ -> app.library.renameFolder(entry.folder.id, name) }
            })
            MenuEntry("Borrar carpeta", Icons.Rounded.Delete, {
                close()
                actions.confirmRequest = ConfirmRequest("¿Borrar «${entry.folder.name}»?", "Las playlists de dentro no se borran: se quedan sueltas en tu biblioteca.", "Borrar") {
                    app.library.deleteFolder(entry.folder.id)
                }
            }, danger = true)
        }
        is LibraryEntry.Playlist -> {
            val playlist = entry.playlist
            val songs = playlist.songIds
            LibraryRow(
                title = playlist.name,
                subtitle = "Playlist · " + songCount(songs.size),
                cover = { mod ->
                    val custom = playlist.customCover ?: playlist.coverUrl
                    if (custom != null) Cover(custom, mod) else Mosaic(songs.take(8).map { data.songs[it]?.thumbnailUrl }, mod)
                },
                onClick = { nav.navigate(Screen.LocalPlaylist(playlist.id)) },
                selected = (nav.current.screen as? Screen.LocalPlaylist)?.id == playlist.id,
                playing = contextId == Library.LOCAL_PREFIX + playlist.id && playing,
                menu = menu,
            ) { close ->
                val list = app.library.playlistSongs(playlist.id)
                val context = PlayContext(playlist.name, Library.LOCAL_PREFIX + playlist.id)
                MenuEntry("Reproducir", Icons.Rounded.PlayArrow, { close(); actions.play(list, 0, context, Library.localPlaylistItem(playlist)) }, enabled = list.isNotEmpty())
                MenuEntry("Aleatorio", Icons.Rounded.Shuffle, { close(); actions.shuffle(list, context, Library.localPlaylistItem(playlist)) }, enabled = list.isNotEmpty())
                MenuDivider()
                MenuEntry("Editar nombre y descripción", Icons.Rounded.Edit, { close(); actions.renamePlaylist(playlist.id) })
                MenuEntry("Compartir enlace", Icons.Rounded.Link, { close(); actions.sharePlaylist(playlist.name, list) })
                if (data.folders.isNotEmpty() || playlist.folderId != null) {
                    MenuDivider()
                    data.folders.filter { it.id != playlist.folderId }.forEach { folder ->
                        MenuEntry("Mover a «${folder.name}»", Icons.Rounded.Folder, { close(); app.library.movePlaylistToFolder(playlist.id, folder.id) })
                    }
                    if (playlist.folderId != null) MenuEntry("Sacar de la carpeta", Icons.Rounded.Folder, { close(); app.library.movePlaylistToFolder(playlist.id, null) })
                }
                MenuDivider()
                MenuEntry("Borrar playlist", Icons.Rounded.Delete, { close(); actions.deletePlaylist(playlist.id) }, danger = true)
            }
        }
        is LibraryEntry.Album -> {
            val album = entry.album.album
            LibraryRow(
                title = album.title,
                subtitle = "Álbum · " + album.artistsText,
                cover = { Cover(album.thumbnailUrl, it) },
                onClick = { nav.navigate(Screen.Album(album.id, album)) },
                selected = (nav.current.screen as? Screen.Album)?.id == album.id,
                playing = contextId == album.id && playing,
                menu = menu,
            ) { close ->
                MenuEntry("Quitar de tu biblioteca", Icons.Rounded.Delete, { close(); app.library.setAlbumSaved(album, false) }, danger = true)
            }
        }
        is LibraryEntry.Artist -> {
            val artist = entry.artist.artist
            LibraryRow(
                title = artist.title,
                subtitle = "Artista",
                cover = { Cover(artist.thumbnailUrl, it, androidx.compose.foundation.shape.CircleShape) },
                onClick = { nav.navigate(Screen.Artist(artist.id, artist)) },
                selected = (nav.current.screen as? Screen.Artist)?.id == artist.id,
                playing = contextId == artist.id && playing,
                menu = menu,
            ) { close ->
                MenuEntry("Dejar de seguir", Icons.Rounded.Delete, { close(); app.library.setFollowing(artist, false) }, danger = true)
            }
        }
    }
}

/** «⇅ Recientes»: cambia el orden de la biblioteca (se recuerda). */
@Composable
private fun SortButton(sort: LibrarySort, onPick: (LibrarySort) -> Unit) {
    var open by remember { mutableStateOf(false) }
    Box {
        HoverBox(onClick = { open = true }) {
            Row(Modifier.padding(horizontal = 10.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(sort.label, style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.width(6.dp))
                Icon(Icons.Rounded.SwapVert, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(16.dp))
            }
        }
        DropdownMenu(expanded = open, onDismissRequest = { open = false }, containerColor = LyraColors.SurfaceHigher) {
            Text("Ordenar por", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary, modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp))
            LibrarySort.entries.forEach { option ->
                MenuEntry(option.label, if (option == sort) Icons.Rounded.Check else null, { open = false; onPick(option) })
            }
        }
    }
}
