package com.lyra.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOpen
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyra.desktop.Paths
import com.lyra.desktop.data.DownloadStatus
import com.lyra.desktop.data.Library
import com.lyra.desktop.player.PlayContext
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.FilledPill
import com.lyra.desktop.ui.components.HoverBox
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LoadingView
import com.lyra.desktop.ui.components.LocalNowPlaying
import com.lyra.desktop.ui.components.LyraMenu
import com.lyra.desktop.ui.components.MenuDivider
import com.lyra.desktop.ui.components.MenuEntry
import com.lyra.desktop.ui.components.MenuState
import com.lyra.desktop.ui.components.MessageView
import com.lyra.desktop.ui.components.Mosaic
import com.lyra.desktop.ui.components.OutlinePill
import com.lyra.desktop.ui.components.PlayButton
import com.lyra.desktop.ui.components.SearchBox
import com.lyra.desktop.ui.components.SectionHeader
import com.lyra.desktop.ui.components.SongRow
import com.lyra.desktop.ui.components.SpecialCover
import com.lyra.desktop.ui.components.art
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.model.remoteId
import com.lyra.music.data.repo.SearchTab
import kotlinx.coroutines.delay
import java.awt.FileDialog
import java.awt.Frame
import java.io.File

// ---------------------------------------------------------------------- playlist de YouTube Music o SoundCloud

@Composable
fun RemotePlaylistScreen(id: String, preview: PlaylistItem?) {
    val actions = LocalActions.current
    val app = actions.app
    val (load, retry) = rememberLoad("playlist:$id") { app.music.fullPlaylist(id) }
    val data by app.library.data.collectAsState()
    val now = LocalNowPlaying.current
    val playerState by app.player.state.collectAsState()
    ScreenList {
        val page = (load as? Load.Ready)?.value
        val playlist = page?.playlist ?: preview
        item {
            CollectionHeader(
                type = if (id.startsWith("sc:")) "Playlist de SoundCloud" else "Playlist",
                title = playlist?.title ?: "",
                cover = { modifier ->
                    val thumb = playlist?.thumbnailUrl
                    if (thumb != null) Cover(art(thumb, 600), modifier, RoundedCornerShape(8.dp))
                    else Mosaic(page?.songs.orEmpty().take(8).map { it.thumbnailUrl }, modifier, RoundedCornerShape(8.dp))
                },
                description = page?.description,
            ) {
                playlist?.author?.let { MetaText(it, strong = true); MetaDot() }
                if (page != null) {
                    MetaText(songCount(page.songs.size))
                    totalDuration(page.songs).takeIf { it.isNotEmpty() }?.let { MetaText(", $it") }
                }
            }
        }
        when (load) {
            is Load.Loading -> item { LoadingView() }
            is Load.Failed -> item { ErrorView(load.message, retry) }
            is Load.Ready -> {
                val loaded = load.value
                val context = PlayContext(loaded.playlist.title, loaded.playlist.id)
                val playingHere = playerState.context?.id == loaded.playlist.id
                item {
                    ActionBar {
                        PlayButton(playingHere && now.playing, onClick = {
                            if (playingHere) app.player.togglePlay() else actions.play(loaded.songs, 0, context, loaded.playlist)
                        }, size = 56.dp)
                        IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(loaded.songs, context, loaded.playlist) }, size = 44.dp, iconSize = 26.dp)
                        val saved = data.playlists.any { it.remoteId == loaded.playlist.id }
                        IconBtn(
                            if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                            if (saved) "Ya está en tu biblioteca" else "Guardar en tu biblioteca",
                            onClick = { actions.saveRemotePlaylist(loaded.playlist, loaded.songs) },
                            size = 44.dp,
                            iconSize = 28.dp,
                            active = saved,
                        )
                        IconBtn(Icons.Rounded.ArrowDownward, "Descargar", onClick = { actions.download(loaded.songs) }, size = 44.dp, iconSize = 24.dp)
                        if (!loaded.playlist.id.startsWith("sc:")) {
                            IconBtn(Icons.Rounded.Radio, "Radio de la playlist", onClick = {
                                app.player.startPlaylistRadio(loaded.playlist.id.remoteId().removePrefix("VL"), "Radio de ${loaded.playlist.title}")
                            }, size = 44.dp, iconSize = 24.dp)
                        }
                        IconBtn(Icons.Rounded.Link, "Compartir enlace", onClick = { actions.sharePlaylist(loaded.playlist.title, loaded.songs) }, size = 44.dp, iconSize = 22.dp)
                    }
                }
                item { SongTableHeader(showAlbum = true) }
                songItems(loaded.songs, "pl", onPlay = { index -> actions.play(loaded.songs, index, context, loaded.playlist) })
            }
        }
    }
}

// ---------------------------------------------------------------------- tus playlists

@Composable
fun LocalPlaylistScreen(id: String) {
    val actions = LocalActions.current
    val app = actions.app
    val data by app.library.data.collectAsState()
    val now = LocalNowPlaying.current
    val playerState by app.player.state.collectAsState()
    val playlist = data.playlists.firstOrNull { it.id == id }
    if (playlist == null) {
        MessageView(Icons.AutoMirrored.Rounded.PlaylistAdd, "Esta playlist ya no existe")
        return
    }
    val songs = remember(playlist, data.songs) { playlist.songIds.mapNotNull { data.songs[it] } }
    val item = Library.localPlaylistItem(playlist)
    val context = PlayContext(playlist.name, item.id)
    val playingHere = playerState.context?.id == item.id
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = if (filter.isBlank()) songs else songs.filter { it.title.contains(filter, true) || it.artistsText.contains(filter, true) }
    ScreenList {
        item {
            CollectionHeader(
                type = "Playlist",
                title = playlist.name,
                cover = { modifier ->
                    HoverBox(onClick = { pickCover(id) }, shape = RoundedCornerShape(8.dp)) {
                        val custom = playlist.customCover ?: playlist.coverUrl
                        if (custom != null) Cover(custom, modifier, RoundedCornerShape(8.dp))
                        else Mosaic(songs.take(8).map { it.thumbnailUrl }, modifier, RoundedCornerShape(8.dp))
                    }
                },
                description = playlist.description,
            ) {
                MetaText(songCount(songs.size))
                totalDuration(songs).takeIf { it.isNotEmpty() }?.let { MetaText(", $it") }
            }
        }
        item {
            ActionBar {
                PlayButton(playingHere && now.playing, onClick = {
                    if (playingHere) app.player.togglePlay() else actions.play(songs, 0, context, item)
                }, size = 56.dp)
                IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(songs, context, item) }, size = 44.dp, iconSize = 26.dp, enabled = songs.isNotEmpty())
                IconBtn(Icons.Rounded.ArrowDownward, "Descargar", onClick = { actions.download(songs) }, size = 44.dp, iconSize = 24.dp, enabled = songs.isNotEmpty())
                IconBtn(Icons.Rounded.Link, "Compartir enlace", onClick = { actions.sharePlaylist(playlist.name, songs) }, size = 44.dp, iconSize = 22.dp, enabled = songs.isNotEmpty())
                Box {
                    val menu = remember { MenuState() }
                    IconBtn(Icons.Rounded.MoreHoriz, "Más opciones", onClick = { menu.open = true }, size = 44.dp, iconSize = 26.dp)
                    LyraMenu(menu) { close ->
                        MenuEntry("Editar nombre y descripción", Icons.Rounded.Edit, { close(); actions.renamePlaylist(id) })
                        MenuEntry("Cambiar la portada", Icons.Rounded.Image, { close(); pickCover(id) })
                        if (playlist.customCover != null) MenuEntry("Quitar la portada", Icons.Rounded.Image, { close(); app.library.setPlaylistCover(id, null) })
                        if (data.folders.isNotEmpty()) {
                            MenuDivider()
                            data.folders.filter { it.id != playlist.folderId }.forEach { folder ->
                                MenuEntry("Mover a «${folder.name}»", Icons.Rounded.Folder, { close(); app.library.movePlaylistToFolder(id, folder.id) })
                            }
                            if (playlist.folderId != null) MenuEntry("Sacar de la carpeta", Icons.Rounded.Folder, { close(); app.library.movePlaylistToFolder(id, null) })
                        }
                        MenuDivider()
                        MenuEntry("Borrar playlist", Icons.Rounded.Delete, { close(); actions.deletePlaylist(id) }, danger = true)
                    }
                }
                Spacer(Modifier.weight(1f))
                if (songs.size > 8) SearchBox(filter, { filter = it }, "Buscar en la playlist", Modifier.width(240.dp), height = 36.dp)
            }
        }
        if (songs.isEmpty()) {
            item {
                Text(
                    "Esta playlist está vacía. Busca canciones aquí abajo o añádelas desde cualquier canción (clic derecho → Añadir a una playlist).",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyraColors.TextSecondary,
                    modifier = Modifier.padding(horizontal = PagePadding),
                )
            }
        } else {
            item { SongTableHeader(showAlbum = true) }
            items(shown.size, key = { "lp-${shown[it].id}" }) { index ->
                val song = shown[index]
                SongRow(
                    song,
                    onPlay = { actions.play(songs, songs.indexOf(song), context, item) },
                    modifier = Modifier.padding(horizontal = PagePadding - 12.dp),
                    index = songs.indexOf(song) + 1,
                    showAlbum = true,
                    menuExtra = { close ->
                        MenuEntry("Quitar de esta playlist", Icons.Rounded.RemoveCircleOutline, {
                            close()
                            app.library.removeFromPlaylist(id, setOf(song.id))
                            actions.message("Quitada de «${playlist.name}»", "Deshacer") { app.library.addToPlaylist(id, listOf(song)) }
                        })
                        val position = songs.indexOf(song)
                        if (position > 0) MenuEntry("Subir", null, { close(); app.library.movePlaylistSong(id, position, position - 1) })
                        if (position < songs.lastIndex) MenuEntry("Bajar", null, { close(); app.library.movePlaylistSong(id, position, position + 1) })
                    },
                )
            }
        }
        item { AddSongsSection(id) }
    }
}

/** «Busquemos algo para tu playlist» (como en Spotify): buscar y añadir sin salir de ella. */
@Composable
private fun AddSongsSection(playlistId: String) {
    val actions = LocalActions.current
    val app = actions.app
    var query by rememberSaveable { mutableStateOf("") }
    var results by remember { mutableStateOf<List<Song>>(emptyList()) }
    val data by app.library.data.collectAsState()
    val inPlaylist = data.playlists.firstOrNull { it.id == playlistId }?.songIds?.toSet().orEmpty()
    LaunchedEffect(query) {
        if (query.isBlank()) {
            results = emptyList()
            return@LaunchedEffect
        }
        delay(300)
        results = runCatching { app.music.search(query.trim(), SearchTab.SONGS).items.filterIsInstance<Song>().take(10) }.getOrDefault(emptyList())
    }
    Column(Modifier.padding(horizontal = PagePadding, vertical = 28.dp)) {
        SectionHeader("Busca algo para tu playlist")
        SearchBox(query, { query = it }, "Canciones, artistas…", Modifier.widthIn(max = 420.dp), height = 40.dp, leading = Icons.Rounded.Search)
        Spacer(Modifier.height(10.dp))
        results.forEach { song ->
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.weight(1f)) { SongRow(song, onPlay = { app.player.startRadio(song) }) }
                Spacer(Modifier.width(10.dp))
                if (song.id in inPlaylist) {
                    Text("Añadida", style = MaterialTheme.typography.labelLarge, color = LyraColors.TextTertiary, modifier = Modifier.width(80.dp))
                } else {
                    OutlinePill("Añadir", onClick = { app.library.addToPlaylist(playlistId, listOf(song)) }, modifier = Modifier.width(80.dp))
                }
            }
        }
    }
}

/** Elegir una imagen del PC como portada de la playlist. */
private fun pickCover(playlistId: String) {
    val dialog = FileDialog(null as Frame?, "Elige una imagen para la portada", FileDialog.LOAD).apply {
        setFilenameFilter { _, name -> name.lowercase().let { it.endsWith(".jpg") || it.endsWith(".jpeg") || it.endsWith(".png") || it.endsWith(".webp") } }
        file = "*.jpg;*.jpeg;*.png;*.webp"
        isVisible = true
    }
    val chosen = dialog.files.firstOrNull() ?: return
    val app = com.lyra.desktop.Lyra.app
    val target = File(Paths.covers, "$playlistId-${System.currentTimeMillis()}.${chosen.extension.lowercase()}")
    runCatching { chosen.copyTo(target, overwrite = true) }.onSuccess {
        app.library.playlist(playlistId)?.customCover?.let { old -> if (old.startsWith(Paths.covers.path)) File(old).delete() }
        app.library.setPlaylistCover(playlistId, target.absolutePath)
    }
}

// ---------------------------------------------------------------------- Me gusta, descargas e historial

@Composable
fun LikedScreen() {
    val actions = LocalActions.current
    val app = actions.app
    val data by app.library.data.collectAsState()
    val now = LocalNowPlaying.current
    val playerState by app.player.state.collectAsState()
    val songs = remember(data.liked, data.songs) { app.library.likedSongs(data) }
    val item = PlaylistItem(Library.LIKED_ID, "Canciones que te gustan")
    val context = PlayContext("Canciones que te gustan", Library.LIKED_ID)
    val playingHere = playerState.context?.id == Library.LIKED_ID
    var filter by rememberSaveable { mutableStateOf("") }
    val shown = if (filter.isBlank()) songs else songs.filter { it.title.contains(filter, true) || it.artistsText.contains(filter, true) }
    ScreenList {
        item {
            CollectionHeader("Playlist", "Canciones que te gustan", cover = { SpecialCover(Icons.Rounded.Favorite, it, shape = RoundedCornerShape(8.dp)) }) {
                MetaText(songCount(songs.size))
            }
        }
        item {
            ActionBar {
                PlayButton(playingHere && now.playing, onClick = { if (playingHere) app.player.togglePlay() else actions.play(songs, 0, context, item) }, size = 56.dp)
                IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(songs, context, item) }, size = 44.dp, iconSize = 26.dp, enabled = songs.isNotEmpty())
                IconBtn(Icons.Rounded.ArrowDownward, "Descargar todas", onClick = { actions.download(songs) }, size = 44.dp, iconSize = 24.dp, enabled = songs.isNotEmpty())
                Spacer(Modifier.weight(1f))
                if (songs.size > 8) SearchBox(filter, { filter = it }, "Buscar en Me gusta", Modifier.width(240.dp), height = 36.dp)
            }
        }
        if (songs.isEmpty()) {
            item { MessageView(Icons.Rounded.Favorite, "Aún no te gusta nada", "Pulsa el corazón de cualquier canción y aparecerá aquí.") }
        } else {
            item { SongTableHeader(showAlbum = true) }
            items(shown.size, key = { "lk-${shown[it].id}" }) { index ->
                val song = shown[index]
                SongRow(song, onPlay = { actions.play(songs, songs.indexOf(song), context, item) }, modifier = Modifier.padding(horizontal = PagePadding - 12.dp), index = songs.indexOf(song) + 1, showAlbum = true)
            }
        }
    }
}

@Composable
fun DownloadsScreen() {
    val actions = LocalActions.current
    val app = actions.app
    val entries by app.downloads.entries.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val sorted = entries.values.sortedByDescending { it.addedAt }
    val done = sorted.filter { it.status == DownloadStatus.DONE }.map { it.song }
    val item = PlaylistItem(Library.DOWNLOADS_ID, "Descargas")
    val context = PlayContext("Descargas", Library.DOWNLOADS_ID)
    ScreenList {
        item {
            CollectionHeader("Sin conexión", "Descargas", cover = { SpecialCover(Icons.Rounded.ArrowDownward, it, filled = false, shape = RoundedCornerShape(8.dp)) }) {
                MetaText(songCount(done.size))
                val pending = sorted.count { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
                if (pending > 0) { MetaDot(); MetaText("$pending bajando") }
            }
        }
        item {
            ActionBar {
                PlayButton(false, onClick = { actions.play(done, 0, context, item) }, size = 56.dp)
                IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(done, context, item) }, size = 44.dp, iconSize = 26.dp, enabled = done.isNotEmpty())
                OutlinePill("Abrir la carpeta", onClick = { actions.openFolder(Paths.downloadsFolder(settings.downloadsFolder)) }, icon = Icons.Rounded.FolderOpen)
            }
        }
        if (sorted.isEmpty()) {
            item { MessageView(Icons.Rounded.ArrowDownward, "No hay descargas", "Descarga canciones, álbumes o playlists con su botón ↓ para escucharlas sin internet. Se guardan en Música\\Lyra.") }
        }
        items(sorted, key = { "dl-" + it.song.id }) { entry ->
            Column(Modifier.padding(horizontal = PagePadding - 12.dp)) {
                SongRow(
                    entry.song,
                    onPlay = {
                        if (entry.status == DownloadStatus.DONE) actions.play(done, done.indexOf(entry.song).coerceAtLeast(0), context, item)
                        else app.player.startRadio(entry.song)
                    },
                    subtitle = when (entry.status) {
                        DownloadStatus.DONE -> null
                        DownloadStatus.QUEUED -> "En cola…"
                        DownloadStatus.DOWNLOADING -> "Descargando · ${(entry.progress * 100).toInt()} %"
                        DownloadStatus.FAILED -> "No se pudo: ${entry.error ?: "error"}"
                    },
                    menuExtra = { close ->
                        if (entry.status == DownloadStatus.FAILED) MenuEntry("Reintentar", Icons.Rounded.Refresh, { close(); app.downloads.retry(entry.song.id) })
                        entry.file?.let { path -> MenuEntry("Mostrar en la carpeta", Icons.Rounded.FolderOpen, { close(); actions.showInExplorer(File(path)) }) }
                        MenuEntry("Quitar descarga", Icons.Rounded.Delete, { close(); app.downloads.remove(entry.song.id) }, danger = true)
                    },
                )
                if (entry.status == DownloadStatus.DOWNLOADING) {
                    LinearProgressIndicator(
                        progress = { entry.progress },
                        color = LyraColors.Accent,
                        trackColor = LyraColors.SurfaceHigher,
                        modifier = Modifier.fillMaxWidth().padding(horizontal = 12.dp).height(2.dp),
                    )
                }
            }
        }
    }
}

@Composable
fun HistoryScreen() {
    val actions = LocalActions.current
    val app = actions.app
    val data by app.library.data.collectAsState()
    val songs = remember(data.history) { app.library.recentlyPlayed(300, data) }
    val context = PlayContext("Historial")
    ScreenList {
        item {
            CollectionHeader("Historial", "Escuchado recientemente", cover = { SpecialCover(Icons.Rounded.History, it, filled = false, shape = RoundedCornerShape(8.dp)) }) {
                MetaText(songCount(songs.size))
            }
        }
        item {
            ActionBar {
                PlayButton(false, onClick = { actions.play(songs, 0, context) }, size = 56.dp)
                Spacer(Modifier.weight(1f))
                if (songs.isNotEmpty()) {
                    OutlinePill("Borrar historial", onClick = {
                        actions.confirmRequest = com.lyra.desktop.ui.ConfirmRequest(
                            "¿Borrar el historial?",
                            "Se olvida lo que has escuchado (también para tus mixes y «Más escuchadas»). Tu biblioteca no se toca.",
                            "Borrar",
                        ) { app.library.clearHistory() }
                    }, icon = Icons.Rounded.Delete)
                }
            }
        }
        if (songs.isEmpty()) {
            item { MessageView(Icons.Rounded.History, "Aún no has escuchado nada", "Lo que escuches (más de 30 segundos) aparecerá aquí.") }
        }
        items(songs.size, key = { "h-${songs[it].id}" }) { index ->
            SongRow(songs[index], onPlay = { actions.play(songs, index, context) }, modifier = Modifier.padding(horizontal = PagePadding - 12.dp), showAlbum = true)
        }
    }
}

// ---------------------------------------------------------------------- carpetas

@Composable
fun FolderScreen(id: String) {
    val actions = LocalActions.current
    val app = actions.app
    val data by app.library.data.collectAsState()
    val folder = data.folders.firstOrNull { it.id == id }
    if (folder == null) {
        MessageView(Icons.Rounded.Folder, "Esta carpeta ya no existe")
        return
    }
    val playlists = data.playlists.filter { it.folderId == id }
    val allSongs = playlists.flatMap { p -> p.songIds.mapNotNull { data.songs[it] } }.distinctBy { it.id }
    ScreenList {
        item {
            CollectionHeader("Carpeta", folder.name, cover = { SpecialCover(Icons.Rounded.Folder, it, filled = false, shape = RoundedCornerShape(8.dp)) }) {
                MetaText(if (playlists.size == 1) "1 playlist" else "${playlists.size} playlists")
                MetaDot()
                MetaText(songCount(allSongs.size))
            }
        }
        item {
            ActionBar {
                PlayButton(false, onClick = { actions.play(allSongs, 0, PlayContext(folder.name, "folder:$id")) }, size = 56.dp)
                IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(allSongs, PlayContext(folder.name, "folder:$id")) }, size = 44.dp, iconSize = 26.dp, enabled = allSongs.isNotEmpty())
                OutlinePill("Renombrar", onClick = {
                    actions.textRequest = com.lyra.desktop.ui.TextRequest("Renombrar carpeta", folder.name, "Nombre", "Guardar") { name, _ -> app.library.renameFolder(id, name) }
                }, icon = Icons.Rounded.Edit)
            }
        }
        if (playlists.isEmpty()) {
            item { MessageView(Icons.Rounded.Folder, "Carpeta vacía", "Mueve playlists aquí con el clic derecho sobre ellas en tu biblioteca.") }
        } else {
            item { CardsGrid(playlists.map { Library.localPlaylistItem(it) }, Modifier.padding(horizontal = PagePadding - 10.dp)) }
        }
    }
}

// ---------------------------------------------------------------------- playlist compartida por enlace

@Composable
fun SharedPlaylistScreen(url: String) {
    val actions = LocalActions.current
    val app = actions.app
    val (load, retry) = rememberLoad("shared:$url") {
        val shared = app.sharing.load(url)
        val songs = shared.songs.map { it.toSong() }
        val full = runCatching { app.music.songsByIds(songs.map { it.id }) }.getOrDefault(emptyMap())
        shared.name to songs.map { full[it.id] ?: it }
    }
    ScreenList {
        when (load) {
            is Load.Loading -> item { LoadingView() }
            is Load.Failed -> item { ErrorView(load.message, retry) }
            is Load.Ready -> {
                val (name, songs) = load.value
                val context = PlayContext(name)
                item {
                    CollectionHeader("Playlist compartida", name, cover = { Mosaic(songs.take(8).map { it.thumbnailUrl }, it, RoundedCornerShape(8.dp)) }) {
                        MetaText(songCount(songs.size))
                    }
                }
                item {
                    ActionBar {
                        PlayButton(false, onClick = { actions.play(songs, 0, context) }, size = 56.dp)
                        IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(songs, context) }, size = 44.dp, iconSize = 26.dp)
                        FilledPill("Guardar en tu biblioteca", onClick = {
                            val id = app.library.createPlaylist(name, songs)
                            actions.message("Guardada en tu biblioteca", "Abrir") { actions.nav.navigate(Screen.LocalPlaylist(id)) }
                        })
                    }
                }
                item { SongTableHeader(showAlbum = true) }
                songItems(songs, "sh", onPlay = { index -> actions.play(songs, index, context) })
            }
        }
    }
}
