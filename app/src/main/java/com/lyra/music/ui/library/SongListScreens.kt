package com.lyra.music.ui.library

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.LinearProgressIndicator
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
import androidx.compose.runtime.saveable.rememberSaveable
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
import com.lyra.music.ui.components.OutlineIconButton
import com.lyra.music.ui.components.PageHeader
import com.lyra.music.ui.components.SongFilterBar
import com.lyra.music.ui.components.SongOrder
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.filterSongs
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
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(SongOrder.DEFAULT) }
    val shown = remember(songs, query, order) { songs.filterSongs(query, order) { it } }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            CollectionHeader(
                title = "Canciones que te gustan",
                subtitle = null,
                eyebrow = "Tu colección",
                meta = totalDurationText(songs),
                backdrop = songs.firstOrNull()?.thumbnailUrl,
                cover = { SpecialCover(Icons.Rounded.Favorite, it, RoundedCornerShape(16.dp), filled = true) },
                onPlay = { actions.play(songs, 0, from = from) },
                onShuffle = { actions.shuffle(songs, from = from) },
            ) {
                DownloadAllButton(songs, "Canciones que te gustan")
                OutlineIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Añadir a playlist", onClick = { actions.addToPlaylist = songs })
            }
        }
        if (rows != null && songs.isEmpty()) {
            item { EmptyView(Icons.Rounded.Favorite, "Aún no hay canciones", "Pulsa el corazón en cualquier canción para guardarla aquí.") }
        }
        if (songs.isNotEmpty()) {
            item(key = "filter") { SongFilterBar(query, { query = it }, order, { order = it }, "Recientes") }
            if (shown.isEmpty()) item { NoMatches(query) }
        }
        itemsIndexed(shown, key = { _, song -> song.id }) { index, song ->
            SongRow(song, onClick = { actions.play(shown, index, from = from) })
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
    var query by rememberSaveable { mutableStateOf("") }
    var order by rememberSaveable { mutableStateOf(SongOrder.DEFAULT) }
    val shownRows = remember(rows, query, order) { rows.orEmpty().filterSongs(query, order) { it.toSong() } }
    val shownCompleted = shownRows.filter { it.downloadState == DownloadState.COMPLETED }.map { it.toSong() }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            CollectionHeader(
                title = "Descargas",
                subtitle = "${completed.size} canciones · ${formatBytes(total)}",
                eyebrow = "Sin conexión",
                meta = if (actions.container.settings.current.downloadsVisible) {
                    "En la carpeta ${downloads.folder.displayPath}, con nombre y carátula. Suenan sin cobertura."
                } else {
                    "Guardadas dentro de Lyra. Suenan aunque no tengas cobertura."
                },
                backdrop = completed.firstOrNull()?.thumbnailUrl,
                cover = { SpecialCover(Icons.Rounded.ArrowDownward, it, RoundedCornerShape(16.dp), filled = false) },
                onPlay = { actions.play(completed, 0, from = from) },
                onShuffle = { actions.shuffle(completed, from = from) },
            ) {
                if (failed > 0) {
                    OutlineIconButton(Icons.Rounded.Refresh, "Reintentar fallidas", onClick = { actions.launch { downloads.retryFailed() } })
                }
                if (all.isNotEmpty()) {
                    OutlineIconButton(Icons.Rounded.DeleteSweep, "Borrar todas", onClick = { confirmClear = true })
                }
            }
        }
        if (rows != null && all.isEmpty()) {
            item { EmptyView(Icons.Rounded.ArrowDownward, "Nada descargado todavía", "Descarga canciones, álbumes o playlists para escucharlos sin conexión.") }
        }
        if (all.isNotEmpty()) {
            item(key = "filter") { SongFilterBar(query, { query = it }, order, { order = it }, "Recientes") }
            if (shownRows.isEmpty()) item { NoMatches(query) }
        }
        itemsIndexed(shownRows, key = { _, row -> row.id }) { _, row ->
            val song = row.toSong()
            SongRow(
                song,
                onClick = {
                    val index = shownCompleted.indexOfFirst { it.id == song.id }
                    if (index >= 0) actions.play(shownCompleted, index, from = from) else actions.startRadio(song)
                },
                trailing = if (row.downloadState == DownloadState.FAILED) {
                    {
                        TextButton(onClick = { actions.download(listOf(song)) }) { Text("Reintentar", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) }
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
        PageHeader("Historial", "Lo último que has escuchado.") {
            if (!songs.isNullOrEmpty()) {
                IconButton(onClick = { confirm = true }) { Icon(Icons.Rounded.DeleteOutline, "Borrar historial", tint = LyraColors.TextSecondary) }
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
    var query by rememberSaveable { mutableStateOf("") }
    var sortOrder by rememberSaveable { mutableStateOf(SongOrder.DEFAULT) }

    // Copia local para reordenar sin parpadeos.
    val order = remember { mutableStateListOf<Song>() }
    LaunchedEffect(rows) {
        if (rows != null) {
            order.clear()
            order.addAll(songs)
        }
    }
    // Buscando u ordenando se muestra una copia; reordenar solo con la lista tal cual.
    val filtering = query.isNotBlank() || sortOrder != SongOrder.DEFAULT
    val shown = if (filtering) order.toList().filterSongs(query, sortOrder) { it } else order
    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        firstIndex = 2,
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
                eyebrow = when {
                    current?.remoteId?.startsWith("spotify:") == true -> "Playlist · De Spotify"
                    current?.remoteId != null -> "Playlist · Importada"
                    else -> "Playlist"
                } + if (current?.syncEnabled == true) " · Sincronizada" else "",
                meta = totalDurationText(songs),
                backdrop = covers.firstOrNull() ?: current?.coverUrl,
                cover = { Mosaic(if (covers.size >= 4) covers else listOfNotNull(current?.coverUrl ?: covers.firstOrNull()), it, RoundedCornerShape(16.dp)) },
                onPlay = { actions.play(songs, 0, from = from) },
                onShuffle = { actions.shuffle(songs, from = from) },
            ) {
                DownloadAllButton(songs, current?.name)
                OutlineIconButton(
                    Icons.Rounded.DragHandle,
                    "Reordenar",
                    onClick = {
                        editing = !editing
                        if (editing) {
                            query = ""
                            sortOrder = SongOrder.DEFAULT
                        }
                    },
                    tint = if (editing) LyraColors.Accent else LyraColors.TextPrimary,
                )
                Row {
                    OutlineIconButton(Icons.Rounded.Edit, "Editar", onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = LyraColors.SurfaceHigh) {
                        DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { menuOpen = false; renaming = true })
                        if (current?.remoteId != null) {
                            DropdownMenuItem(
                                text = { Text("Buscar canciones nuevas ahora") },
                                leadingIcon = { Icon(Icons.Rounded.Sync, null) },
                                onClick = {
                                    menuOpen = false
                                    syncing = true
                                    actions.launch {
                                        runCatching { actions.container.playlistSync.sync(current) }
                                            .onSuccess { actions.message(if (it.added == 0) "Ya está al día" else "${it.added} canciones nuevas") }
                                            .onFailure { actions.message("No se pudo sincronizar") }
                                        syncing = false
                                    }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (current.syncEnabled) "✓ Mantener sincronizada" else "Mantener sincronizada") },
                                onClick = {
                                    menuOpen = false
                                    actions.launch { library.setPlaylistSync(id, !current.syncEnabled, current.autoDownload) }
                                },
                            )
                            DropdownMenuItem(
                                text = { Text(if (current.autoDownload) "✓ Descargar canciones nuevas" else "Descargar canciones nuevas") },
                                onClick = {
                                    menuOpen = false
                                    actions.launch { library.setPlaylistSync(id, true, !current.autoDownload) }
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
        if (songs.isNotEmpty()) {
            item(key = "filter") {
                SongFilterBar(
                    query,
                    { query = it; if (it.isNotBlank()) editing = false },
                    sortOrder,
                    { sortOrder = it; if (it != SongOrder.DEFAULT) editing = false },
                    "Tu orden",
                )
            }
            if (shown.isEmpty()) item { NoMatches(query) }
        }
        itemsIndexed(shown, key = { _, song -> song.id }) { index, song ->
            SongRow(
                song,
                onClick = { if (!editing) actions.play(shown.toList(), index, from = from) },
                modifier = if (filtering) Modifier else Modifier.reorderItem(reorder, index),
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
        LinearProgressIndicator(
            Modifier
                .fillMaxWidth()
                .statusBarsPadding(),
            color = LyraColors.Accent,
            trackColor = Color.Transparent,
        )
    }
}

@Composable
private fun NoMatches(query: String) {
    EmptyView(Icons.Rounded.SearchOff, "Sin resultados", "Nada en esta lista coincide con «${query.trim()}».")
}

fun formatBytes(bytes: Long): String = when {
    bytes >= 1L shl 30 -> String.format(java.util.Locale.forLanguageTag("es"), "%.1f GB", bytes / (1L shl 30).toDouble())
    bytes >= 1L shl 20 -> String.format(java.util.Locale.forLanguageTag("es"), "%.0f MB", bytes / (1L shl 20).toDouble())
    else -> String.format(java.util.Locale.forLanguageTag("es"), "%.0f KB", bytes / 1024.0)
}
