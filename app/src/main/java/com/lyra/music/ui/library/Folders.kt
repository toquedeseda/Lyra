package com.lyra.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.CreateNewFolder
import androidx.compose.material.icons.rounded.Edit
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.FolderOff
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.material.icons.rounded.RadioButtonUnchecked
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.db.FolderSummary
import com.lyra.music.data.db.PlaylistSummary
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.CollectionHeader
import com.lyra.music.ui.components.ConfirmDialog
import com.lyra.music.ui.components.EmptyView
import com.lyra.music.ui.components.Mosaic
import com.lyra.music.ui.components.OutlineIconButton
import com.lyra.music.ui.components.SpecialCover
import com.lyra.music.ui.components.TextInputDialog
import com.lyra.music.ui.components.pressable
import com.lyra.music.ui.navigation.FolderRoute
import com.lyra.music.ui.theme.LyraColors

/** Portada de una playlist: la elegida por ti o, si no, el mosaico de sus canciones. */
@UnstableApi
@Composable
fun PlaylistCover(playlist: PlaylistSummary, modifier: Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    val custom = playlist.customCover
    if (custom != null) {
        Artwork(custom, modifier, shape)
        return
    }
    val covers by LocalActions.current.container.library.playlistCovers(playlist.id).collectAsState(initial = emptyList())
    Mosaic(if (covers.size >= 4) covers else listOfNotNull(playlist.coverUrl ?: covers.firstOrNull()), modifier, shape)
}

/** Mosaico con las portadas de sus playlists y una carpetita en la esquina. */
@Composable
fun FolderCover(covers: List<String>, modifier: Modifier, shape: Shape = RoundedCornerShape(10.dp)) {
    BoxWithConstraints(modifier) {
        if (covers.isEmpty()) {
            SpecialCover(Icons.Rounded.Folder, Modifier.fillMaxSize(), shape, filled = false)
        } else {
            Mosaic(covers, Modifier.fillMaxSize(), shape)
            val badge = (maxWidth * 0.34f).coerceAtMost(40.dp)
            Box(
                Modifier
                    .align(Alignment.BottomEnd)
                    .padding(maxWidth * 0.05f)
                    .size(badge)
                    .clip(CircleShape)
                    .background(LyraColors.Background.copy(alpha = 0.85f)),
                contentAlignment = Alignment.Center,
            ) {
                Icon(Icons.Rounded.Folder, null, tint = LyraColors.TextPrimary, modifier = Modifier.size(badge * 0.58f))
            }
        }
    }
}

@UnstableApi
@Composable
fun FolderRow(folder: FolderSummary) {
    val actions = LocalActions.current
    val covers by actions.container.library.folderCovers(folder.id).collectAsState(initial = emptyList())
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f) { actions.nav.navigate(FolderRoute(folder.id)) }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FolderCover(covers, Modifier.size(56.dp))
        Spacer(Modifier.width(14.dp))
        Column {
            Text(folder.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "Carpeta · " + if (folder.playlistCount == 1) "1 playlist" else "${folder.playlistCount} playlists",
                style = MaterialTheme.typography.bodySmall,
                color = LyraColors.TextSecondary,
            )
        }
    }
}

/** Una carpeta: sus playlists, reproducirlas todas seguidas y organizarla. */
@UnstableApi
@Composable
fun FolderScreen(id: Long, contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val library = actions.container.library
    val folder by library.folder(id).collectAsState(initial = null)
    val all by library.playlistSummaries.collectAsState(initial = emptyList())
    val covers by library.folderCovers(id).collectAsState(initial = emptyList())
    val inside = all.filter { it.folderId == id }
    val name = folder?.name.orEmpty()

    var menuOpen by remember { mutableStateOf(false) }
    var renaming by remember { mutableStateOf(false) }
    var deleting by remember { mutableStateOf(false) }
    var choosing by remember { mutableStateOf(false) }

    fun playAll(shuffle: Boolean) = actions.launch {
        val songs = inside.flatMap { library.playlistSongList(it.id) }.distinctBy { it.id }
        when {
            songs.isEmpty() -> actions.message("Esta carpeta no tiene canciones")
            shuffle -> actions.shuffle(songs, fromLabel = name)
            else -> actions.play(songs, 0, fromLabel = name)
        }
    }

    LazyColumn(Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
        item {
            CollectionHeader(
                title = name,
                subtitle = null,
                eyebrow = "Carpeta",
                meta = if (inside.size == 1) "1 playlist" else "${inside.size} playlists",
                backdrop = covers.firstOrNull(),
                cover = { FolderCover(covers, it, RoundedCornerShape(16.dp)) },
                onPlay = { playAll(shuffle = false) },
                onShuffle = { playAll(shuffle = true) },
            ) {
                OutlineIconButton(Icons.Rounded.LibraryAdd, "Añadir playlists", onClick = { choosing = true })
                Row {
                    OutlineIconButton(Icons.Rounded.Edit, "Editar", onClick = { menuOpen = true })
                    DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }, containerColor = LyraColors.SurfaceHigh) {
                        DropdownMenuItem(text = { Text("Cambiar nombre") }, onClick = { menuOpen = false; renaming = true })
                        DropdownMenuItem(text = { Text("Eliminar carpeta") }, onClick = { menuOpen = false; deleting = true })
                    }
                }
            }
        }
        if (folder != null && inside.isEmpty()) {
            item {
                EmptyView(
                    Icons.Rounded.Folder,
                    "Carpeta vacía",
                    "Mete playlists aquí o desde el menú de cada playlist (lápiz → Mover a carpeta).",
                    action = "Añadir playlists",
                    onAction = { choosing = true },
                )
            }
        }
        items(inside, key = { it.id }) { PlaylistRow(it) }
    }

    if (renaming) {
        TextInputDialog(
            title = "Cambiar nombre",
            initial = name,
            placeholder = "Nombre de la carpeta",
            confirm = "Guardar",
            onDismiss = { renaming = false },
            onConfirm = { newName ->
                renaming = false
                actions.launch { library.renameFolder(id, newName) }
            },
        )
    }
    if (deleting) {
        ConfirmDialog(
            title = "¿Eliminar la carpeta «$name»?",
            message = "Las playlists no se borran: vuelven a tu biblioteca.",
            confirm = "Eliminar",
            onDismiss = { deleting = false },
            onConfirm = {
                actions.launch {
                    library.deleteFolder(id)
                    actions.nav.popBackStack()
                }
            },
        )
    }
    if (choosing) FolderPlaylistsSheet(id, name, all, onDismiss = { choosing = false })
}

/** Elegir qué playlists van en la carpeta: tocar mete o saca. */
@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun FolderPlaylistsSheet(folderId: Long, folderName: String, playlists: List<PlaylistSummary>, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val library = actions.container.library
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LyraColors.Surface,
    ) {
        Text("Playlists en «$folderName»", style = MaterialTheme.typography.headlineSmall, modifier = Modifier.padding(horizontal = 20.dp))
        Text(
            "Toca una para meterla o sacarla de la carpeta.",
            style = MaterialTheme.typography.bodySmall,
            color = LyraColors.TextSecondary,
            modifier = Modifier.padding(start = 20.dp, end = 20.dp, top = 2.dp, bottom = 10.dp),
        )
        LazyColumn(Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 16.dp)) {
            if (playlists.isEmpty()) {
                item { Text("Aún no tienes playlists.", color = LyraColors.TextSecondary, modifier = Modifier.padding(20.dp)) }
            }
            items(playlists, key = { it.id }) { playlist ->
                val inside = playlist.folderId == folderId
                Row(
                    Modifier
                        .fillMaxWidth()
                        .clickable { actions.launch { library.moveToFolder(playlist.id, if (inside) null else folderId) } }
                        .padding(horizontal = 20.dp, vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    PlaylistCover(playlist, Modifier.size(48.dp))
                    Spacer(Modifier.width(14.dp))
                    Column(Modifier.weight(1f)) {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (playlist.folderId != null && !inside) {
                            Text("Está en otra carpeta", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary)
                        }
                    }
                    Icon(
                        if (inside) Icons.Rounded.CheckCircle else Icons.Rounded.RadioButtonUnchecked,
                        if (inside) "En la carpeta" else "Fuera de la carpeta",
                        tint = if (inside) LyraColors.Accent else LyraColors.TextTertiary,
                    )
                }
            }
        }
    }
}

/** "Mover a carpeta…" desde una playlist. */
@UnstableApi
@Composable
fun MoveToFolderDialog(playlistId: Long, currentFolderId: Long?, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val library = actions.container.library
    val folders by library.folders.collectAsState(initial = emptyList())
    var creating by remember { mutableStateOf(false) }

    fun move(folderId: Long?, label: String) {
        onDismiss()
        actions.launch {
            library.moveToFolder(playlistId, folderId)
            actions.message(label)
        }
    }

    if (creating) {
        TextInputDialog(
            title = "Nueva carpeta",
            initial = "",
            placeholder = "Nombre de la carpeta",
            confirm = "Crear",
            onDismiss = onDismiss,
            onConfirm = { name ->
                onDismiss()
                actions.launch {
                    val folderId = library.createFolder(name)
                    library.moveToFolder(playlistId, folderId)
                    actions.message("Movida a «${name.trim()}»")
                }
            },
        )
        return
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        containerColor = LyraColors.Surface,
        title = { Text("Mover a carpeta", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                folders.forEach { folder ->
                    FolderChoice(
                        folder.name,
                        if (folder.id == currentFolderId) Icons.Rounded.CheckCircle else Icons.Rounded.Folder,
                        highlighted = folder.id == currentFolderId,
                    ) { move(folder.id, "Movida a «${folder.name}»") }
                }
                FolderChoice("Nueva carpeta…", Icons.Rounded.CreateNewFolder) { creating = true }
                if (currentFolderId != null) {
                    FolderChoice("Sacar de la carpeta", Icons.Rounded.FolderOff) { move(null, "Ya no está en ninguna carpeta") }
                }
            }
        },
        confirmButton = { TextButton(onClick = onDismiss) { Text("Cerrar", color = LyraColors.TextSecondary) } },
    )
}

@Composable
private fun FolderChoice(label: String, icon: ImageVector, highlighted: Boolean = false, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 6.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(icon, null, tint = if (highlighted) LyraColors.Accent else LyraColors.TextSecondary, modifier = Modifier.size(22.dp))
        Spacer(Modifier.width(14.dp))
        Text(label, style = MaterialTheme.typography.bodyLarge, color = if (highlighted) LyraColors.Accent else LyraColors.TextPrimary)
    }
}
