package com.lyra.music.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.DeleteOutline
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlaylistPlay
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.RemoveCircleOutline
import androidx.compose.material.icons.rounded.Image
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.model.Song
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.SongMenuRequest
import com.lyra.music.ui.theme.LyraColors

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun SongMenuSheet(request: SongMenuRequest, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val library = LocalLibraryState.current
    val song = request.song
    val liked = song.id in library.likedIds
    val download = library.downloads[song.id]

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LyraColors.Surface,
    ) {
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .navigationBarsPadding()
                .padding(bottom = 12.dp),
        ) {
            Row(Modifier.padding(horizontal = 20.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                Artwork(artworkFor(song), Modifier.size(52.dp))
                Spacer(Modifier.width(12.dp))
                Column {
                    Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(song.artistsText, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, maxLines = 1)
                }
            }
            HorizontalDivider(color = LyraColors.Divider, modifier = Modifier.padding(vertical = 8.dp))

            fun act(block: () -> Unit) {
                onDismiss()
                block()
            }

            MenuEntry(if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, if (liked) "Quitar de Me gusta" else "Me gusta") {
                act { actions.toggleLike(song) }
            }
            MenuEntry(Icons.AutoMirrored.Rounded.PlaylistAdd, "Añadir a playlist") { act { actions.addToPlaylist = listOf(song) } }
            MenuEntry(Icons.Rounded.PlaylistPlay, "Reproducir a continuación") { act { actions.playNext(listOf(song)) } }
            MenuEntry(Icons.AutoMirrored.Rounded.QueueMusic, "Añadir a la cola") { act { actions.addToQueue(listOf(song)) } }
            MenuEntry(Icons.Rounded.Radio, "Iniciar radio") { act { actions.startRadio(song) } }
            when (download?.state) {
                DownloadState.COMPLETED -> MenuEntry(Icons.Rounded.DeleteOutline, "Eliminar descarga") { act { actions.removeDownload(song) } }
                DownloadState.QUEUED, DownloadState.DOWNLOADING -> MenuEntry(Icons.Rounded.RemoveCircleOutline, "Cancelar descarga") {
                    act { actions.removeDownload(song) }
                }
                else -> MenuEntry(Icons.Rounded.Download, if (download?.state == DownloadState.FAILED) "Reintentar descarga" else "Descargar") {
                    act { actions.download(listOf(song)) }
                }
            }
            song.artists.firstOrNull { it.id != null }?.let { artist ->
                MenuEntry(Icons.Rounded.Person, "Ir a ${artist.name}") { act { actions.openArtist(artist.id) } }
            }
            song.album?.id?.let { albumId ->
                MenuEntry(Icons.Rounded.Album, "Ir al álbum") { act { actions.openAlbum(albumId) } }
            }
            request.localPlaylistId?.let { playlistId ->
                MenuEntry(Icons.Rounded.RemoveCircleOutline, "Quitar de esta playlist") {
                    act { actions.launch { actions.container.library.removeFromPlaylist(playlistId, song.id) } }
                }
            }
            request.queueIndex?.let { index ->
                MenuEntry(Icons.Rounded.RemoveCircleOutline, "Quitar de la cola") { act { actions.container.player.remove(index) } }
            }
            MenuEntry(Icons.Rounded.Image, "Compartir como imagen") { act { actions.shareCard = song } }
            MenuEntry(Icons.Rounded.Share, "Compartir enlace") { act { actions.share(song) } }
        }
    }
}

@Composable
fun MenuEntry(icon: ImageVector, label: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 20.dp, vertical = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(24.dp))
        Spacer(Modifier.width(18.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun AddToPlaylistSheet(songs: List<Song>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val playlists by actions.container.library.playlistSummaries.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }

    ModalBottomSheet(onDismissRequest = onDismiss, containerColor = LyraColors.Surface) {
        Text(
            "Añadir a playlist",
            style = MaterialTheme.typography.titleLarge,
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding()) {
            item {
                MenuEntry(Icons.Rounded.Add, "Nueva playlist") { creating = true }
            }
            items(playlists, key = { it.id }) { playlist ->
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable {
                            onDismiss()
                            actions.addToPlaylist(playlist.id, songs, playlist.name)
                        }
                        .padding(horizontal = 20.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    com.lyra.music.ui.library.PlaylistCover(playlist, Modifier.size(48.dp), androidx.compose.foundation.shape.RoundedCornerShape(12.dp))
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                        Text("${playlist.songCount} canciones", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
                    }
                }
            }
            item { Spacer(Modifier.height(16.dp)) }
        }
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
                onDismiss()
                actions.createPlaylist(name, songs)
            },
        )
    }
}

@Composable
fun TextInputDialog(
    title: String,
    initial: String,
    placeholder: String,
    confirm: String,
    onDismiss: () -> Unit,
    onConfirm: (String) -> Unit,
) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyraColors.Surface,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = {
            OutlinedTextField(
                value = text,
                onValueChange = { text = it },
                singleLine = true,
                placeholder = { Text(placeholder) },
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = LyraColors.Accent,
                    unfocusedBorderColor = LyraColors.Border,
                    cursorColor = LyraColors.Accent,
                    focusedTextColor = LyraColors.TextPrimary,
                ),
            )
        },
        confirmButton = {
            TextButton(onClick = { onConfirm(text.trim()) }, enabled = text.isNotBlank()) { Text(confirm, color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = LyraColors.TextSecondary) } },
    )
}

@Composable
fun ConfirmDialog(title: String, message: String, confirm: String, onDismiss: () -> Unit, onConfirm: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyraColors.Surface,
        title = { Text(title, style = MaterialTheme.typography.headlineMedium) },
        text = { Text(message, color = LyraColors.TextSecondary) },
        confirmButton = { TextButton(onClick = { onDismiss(); onConfirm() }) { Text(confirm, color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancelar", color = LyraColors.TextSecondary) } },
    )
}

@Composable
fun ChipRow(labels: List<String>, selected: Int, onSelect: (Int) -> Unit, modifier: Modifier = Modifier) {
    androidx.compose.foundation.lazy.LazyRow(
        modifier = modifier,
        contentPadding = androidx.compose.foundation.layout.PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(labels.size) { index -> Pill(labels[index], index == selected) { onSelect(index) } }
    }
}

/** Pastilla: oscura con texto gris, o color hueso cuando está elegida. */
@Composable
fun Pill(label: String, selected: Boolean = false, onClick: () -> Unit) {
    val background by androidx.compose.animation.animateColorAsState(
        if (selected) LyraColors.Accent else LyraColors.SurfaceHigh,
        androidx.compose.animation.core.tween(220),
        label = "píldora",
    )
    val content by androidx.compose.animation.animateColorAsState(
        if (selected) LyraColors.OnAccent else LyraColors.TextSecondary,
        androidx.compose.animation.core.tween(220),
        label = "texto de la píldora",
    )
    androidx.compose.foundation.layout.Box(
        Modifier
            .height(36.dp)
            .clip(androidx.compose.foundation.shape.RoundedCornerShape(50))
            .background(background)
            .pressable(pressedScale = 0.94f, onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            label,
            color = content,
            style = MaterialTheme.typography.labelLarge,
        )
    }
}
