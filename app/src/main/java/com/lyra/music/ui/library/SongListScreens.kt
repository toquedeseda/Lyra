package com.lyra.music.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.DeleteSweep
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.MusicOff
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.DOWNLOADS_ID
import com.lyra.music.data.repo.LIKED_SONGS_ID
import com.lyra.music.data.repo.localPlaylistId
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.CollectionHeader
import com.lyra.music.ui.components.ConfirmDialog
import com.lyra.music.ui.components.DownloadAllButton
import com.lyra.music.ui.components.EmptyView
import com.lyra.music.ui.components.Mosaic
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.SpecialCover
import com.lyra.music.ui.components.TextInputDialog
import com.lyra.music.ui.components.rememberReorderState
import com.lyra.music.ui.components.reorderHandle
import com.lyra.music.ui.components.reorderItem
import com.lyra.music.ui.components.totalDurationText
import com.lyra.music.ui.theme.LyraColors

@UnstableApi
@Composable
fun LikedScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val rows by actions.container.library.likedSongs.collectAsState(initial = null)
    val songs = rows?.map { it.toSong() }.orEmpty()
    val from = PlaylistItem(LIKED_SONGS_ID, "Canciones que te gustan")

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            CollectionHeader(
                title = "Canciones que te gustan",
                subtitle = null,
                meta = totalDurationText(songs),
                cover = { SpecialCover(Icons.Rounded.Favorite, it) },
                onPlay = { actions.play(songs, 0, from = from) },
                onShuffle = { actions.shuffle(songs, from = from) },
            ) {
                DownloadAllButton(songs)
                IconButton(onClick = { actions.addToPlaylist = songs }) {
                    Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Añadir a playlist", tint = LyraColors.TextSecondary)
                }
            }
        }
        if (rows != null && songs.isEmpty()) {
            item { EmptyView(Icons.Rounded.Favorite, "Aún no hay canciones", "Pulsa el corazón en cualquier canción para guardarla aquí.") }
        }
        itemsIndexed(songs, key = { _, song -> song.id }) { index, song ->
            SongRow(song, onClick = { actions.play(songs, index, from = from) })
        }
    }
}

@UnstableApi
@Composable
fun DownloadsScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val downloads = actions.container.downloads
    val rows by downloads.downloads.collectAsState(initial = null)
    val total by downloads.totalBytes.collectAsState(initial = 0L)
    val completed = rows.orEmpty().filter { it.downloadState == DownloadState.COMPLETED }.map { it.toSong() }
    val failed = rows.orEmpty().count { it.downloadState == DownloadState.FAILED }
    val all = rows.orEmpty().map { it.toSong() }
    val from = PlaylistItem(DOWNLOADS_ID, "Descargas")
    var confirmClear by remember { mutableStateOf(false) }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            CollectionHeader(
                title = "Descargas",
                subtitle = "${completed.size} canciones • ${formatBytes(total)}",
                meta = "Guardadas dentro de Lyra. Suenan sin conexión.",
                cover = { SpecialCover(Icons.Rounded.ArrowDownward, it) },
                onPlay = { actions.play(completed, 0, from = from) },
                onShuffle = { actions.shuffle(completed, from = from) },
            ) {
                if (failed > 0) {
                    IconButton(onClick = { actions.launch { downloads.retryFailed() } }) {
                        Icon(Icons.Rounded.Refresh, "Reintentar fallidas", tint = LyraColors.TextSecondary)
                    }
                }
                if (all.isNotEmpty()) {
                    IconButton(onClick = { confirmClear = true }) {
                        Icon(Icons.Rounded.DeleteSweep, "Borrar todas", tint = LyraColors.TextSecondary)
                    }
                }
            }
        }
        if (rows != null && all.isEmpty()) {
            item { EmptyView(Icons.Rounded.ArrowDownward, "Nada descargado todavía", "Descarga canciones, álbumes o playlists para escucharlos sin conexión.") }
        }
        itemsIndexed(rows.orEmpty(), key = { _, row -> row.id }) { _, row ->
            val song = row.toSong()
            SongRow(
                song,
                onClick = {
                    val index = completed.indexOfFirst { it.id == song.id }
                    if (index >= 0) actions.play(completed, index, from = from) else actions.startRadio(song)
                },
                trailing = if (row.downloadState == DownloadState.FAILED) {
                    {
                        TextButton(onClick = { actions.download(listOf(song)) }) { Text("Reintentar", color = Color.White) }
                    }
                } else null,
            )
            if (row.downloadState == DownloadState.FAILED && row.error != null) {
                Text(
                    row.error,
                    style = MaterialTheme.typography.bodySmall,
                    color = LyraColors.TextTertiary,
                    modifier = Modifier.padding(start = 78.dp, end = 16.dp, bottom = 6.dp),
                )
            }
        }
    }

    if (confirmClear) {
        ConfirmDialog(
            title = "¿Borrar todas las descargas?",
            message = "Se liberará ${formatBytes(total)}. Las canciones seguirán en tu biblioteca, pero necesitarán conexión.",
            confirm = "Borrar",
            onDismiss = { confirmClear = false },
            onConfirm = { actions.launch { downloads.clearAll() } },
        )
    }
}

@UnstableApi
@Composable
fun HistoryScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val songs by actions.container.library.history.collectAsState(initial = null)
    var confirm by remember { mutableStateOf(false) }
    Column(Modifier.fillMaxSize()) {
        com.lyra.music.ui.components.BackBar("Historial") {
            if (!songs.isNullOrEmpty()) {
                IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteOutline, "Borrar historial") }
            }
        }
        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
            if (songs != null && songs!!.isEmpty()) {
                item { EmptyView(Icons.Rounded.History, "Sin historial", "Aquí aparecerá lo que escuches.") }
            }
            itemsIndexed(songs.orEmpty(), key = { _, song -> song.id }) { index, song ->
                SongRow(song, onClick = { actions.play(songs.orEmpty(), index, fromLabel = "Historial") })
            }
        }
    }
    if (confirm) {
        ConfirmDialog(
            title = "¿Borrar el historial?",
            message = "También se reinician las estadísticas que usan tus mixes.",
            confirm = "Borrar",
            onDismiss = { confirm = false },
            onConfirm = { actions.launch { actions.container.library.clearHistory() } },
        )
    }
}

@UnstableApi
@Composable
fun LocalPlaylistScreen(id: Long, contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val library = actions.container.library
    val playlist by library.playlist(id).collectAsState(initial = null)
    val rows by library.playlistSongs(id).collectAsState(initial = null)
    val covers by library.playlistCovers(id).collectAsState(initial = emptyList())
    val songs = rows?.map { it.toSong() }.orEmpty()

    var editing by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var syncing by remember { mutableStateOf(false) }

    // Copia local para reordenar sin parpadeos.
    val order = remember { mutableStateListOf<Song>() }
    LaunchedEffect(rows) {
        if (rows != null) {
            order.clear()
            order.addAll(songs)
        }
    }
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        firstIndex = 1,
        onMove = { from, to -> if (from in order.indices && to in order.indices) order.add(to, order.removeAt(from)) },
        onDrop = { _, _ -> actions.launch { library.reorderPlaylist(id, order.map { it.id }) } },
    )
    val current = playlist
    val from = current?.let { PlaylistItem(localPlaylistId(it.id), it.name, thumbnailUrl = covers.firstOrNull()) }

    LazyColumn(
        state = listState,
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
    ) {
        item {
            CollectionHeader(
                title = current?.name.orEmpty(),
                subtitle = current?.description,
                meta = totalDurationText(songs),
                cover = { Mosaic(if (covers.size >= 4) covers else listOfNotNull(current?.coverUrl ?: covers.firstOrNull()), it) },
                onPlay = { actions.play(songs, 0, from = from) },
                onShuffle = { actions.shuffle(songs, from = from) },
            ) {
                DownloadAllButton(songs)
                IconButton(onClick = { editing = !editing }) {
                    Icon(Icons.Rounded.DragHandle, "Reordenar", tint = if (editing) Color.White else LyraColors.TextSecondary)
                }
                Row {
                    IconButton(onClick = { menuOpen = true }) { Icon(Icons.Rounded.Edit, "Editar", tint = LyraColors.TextSecondary) }
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = LyraColors.SurfaceHigher) {
                        DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { menuOpen = false; renaming = true })
                        if (current?.remoteId != null) {
                            DropdownMenuItem(
                                text = { Text("Sincronizar con el original") },
                                leadingIcon = { Icon(Icons.Rounded.Sync, null) },
                                onClick = {
                                    menuOpen = false
                                    syncing = true
                                    actions.launch {
                                        runCatching {
                                            val page = actions.container.music.fullPlaylist(current.remoteId)
                                            library.importPlaylist(page.playlist.copy(id = current.remoteId), page.songs)
                                        }.onSuccess { actions.message("Playlist sincronizada") }
                                            .onFailure { actions.message("No se pudo sincronizar") }
                                        syncing = false
                                    }
                                },
                            )
                        }
                        DropdownMenuItem(text = { Text("Eliminar playlist") }, onClick = { menuOpen = false; deleting = true })
                    }
                }
            }
        }
        if (rows != null && songs.isEmpty()) {
            item { EmptyView(Icons.Rounded.MusicOff, "Playlist vacía", "Añade canciones desde su menú (⋮ → Añadir a playlist).") }
        }
        itemsIndexed(order, key = { _, song -> song.id }) { index, song ->
            SongRow(
                song,
                onClick = { if (!editing) actions.play(order.toList(), index, from = from) },
                modifier = Modifier.reorderItem(reorder, index),
                localPlaylistId = id,
                trailing = if (editing) {
                    {
                        Icon(
                            Icons.Rounded.DragHandle,
                            "Arrastrar",
                            tint = LyraColors.TextSecondary,
                            modifier = Modifier
                                .padding(12.dp)
                                .size(24.dp)
                                .reorderHandle(reorder, index),
                        )
                    }
                } else null,
            )
        }
    }

    if (renaming && current != null) {
        TextInputDialog(
            title = "Cambiar nombre",
            initial = current.name,
            placeholder = "Nombre",
            confirm = "Guardar",
            onDismiss = { renaming = false },
            onConfirm = { name ->
                renaming = false
                actions.launch { library.renamePlaylist(id, name, current.description) }
            },
        )
    }
    if (deleting && current != null) {
        ConfirmDialog(
            title = "¿Eliminar «${current.name}»?",
            message = "Las canciones descargadas no se borran.",
            confirm = "Eliminar",
            onDismiss = { deleting = false },
            onConfirm = {
                actions.launch {
                    library.deletePlaylist(id)
                    actions.nav.popBackStack()
                }
            },
        )
    }
    if (syncing) {
        Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) { }
    }
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.forLanguageTag("es"), "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.forLanguageTag("es"), "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.forLanguageTag("es"), "%.0f KB", bytes / 1024.0)
}
