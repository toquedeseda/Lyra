package com.lyra.music.ui.library

import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.ChipRow
import com.lyra.music.ui.components.EmptyView
import com.lyra.music.ui.components.ItemRow
import com.lyra.music.ui.components.Mosaic
import com.lyra.music.ui.components.SpecialCover
import com.lyra.music.ui.components.TextInputDialog
import com.lyra.music.ui.navigation.DownloadsRoute
import com.lyra.music.ui.navigation.LikedRoute
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.theme.LyraColors

@UnstableApi
@Composable
fun LibraryScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val library = actions.container.library
    val playlists by library.playlistSummaries.collectAsState(initial = emptyList())
    val albums by library.savedAlbums.collectAsState(initial = emptyList())
    val artists by library.followedArtists.collectAsState(initial = emptyList())
    val liked by library.likedIds.collectAsState()
    val downloads by actions.container.downloads.states.collectAsState()
    val completedDownloads = downloads.values.count { it.state == com.lyra.music.data.db.DownloadState.COMPLETED }

    var filter by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val filters = listOf("Todo", "Playlists", "Álbumes", "Artistas")

    Column(Modifier.fillMaxSize()) {
        Row(
            Modifier
                .statusBarsPadding()
                .padding(start = 16.dp, end = 4.dp, top = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Text("Tu biblioteca", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.weight(1f))
            IconButton(onClick = { creating = true }) { Icon(Icons.Rounded.Add, "Crear playlist", modifier = Modifier.size(28.dp)) }
        }
        ChipRow(filters, filter, { filter = it }, Modifier.padding(vertical = 12.dp))

        LazyColumn(contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp)) {
            if (filter == 0 || filter == 1) {
                item {
                    PinnedRow(Icons.Rounded.Favorite, "Canciones que te gustan", "Playlist • ${liked.size} canciones") {
                        actions.nav.navigate(LikedRoute)
                    }
                }
                item {
                    PinnedRow(Icons.Rounded.ArrowDownward, "Descargas", "$completedDownloads canciones sin conexión") {
                        actions.nav.navigate(DownloadsRoute)
                    }
                }
                items(playlists, key = { "p${it.id}" }) { playlist ->
                    val covers by library.playlistCovers(playlist.id).collectAsState(initial = emptyList())
                    Row(
                        Modifier
                            .fillMaxWidth()
                            .clickable { actions.nav.navigate(LocalPlaylistRoute(playlist.id)) }
                            .padding(horizontal = 16.dp, vertical = 7.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Mosaic(if (covers.size >= 4) covers else listOfNotNull(playlist.coverUrl ?: covers.firstOrNull()), Modifier.size(64.dp))
                        Spacer(Modifier.width(12.dp))
                        Column {
                            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
                            Text(
                                "Playlist • ${playlist.songCount} canciones" + if (playlist.remoteId != null) " • Importada" else "",
                                style = MaterialTheme.typography.bodyMedium,
                                color = LyraColors.TextSecondary,
                            )
                        }
                    }
                }
            }
            if (filter == 0 || filter == 2) {
                items(albums, key = { "a${it.id}" }) { album -> ItemRow(album) }
            }
            if (filter == 0 || filter == 3) {
                items(artists, key = { "r${it.id}" }) { artist -> ItemRow(artist) }
            }
            val empty = when (filter) {
                1 -> false
                2 -> albums.isEmpty()
                3 -> artists.isEmpty()
                else -> false
            }
            if (empty) {
                item {
                    EmptyView(
                        Icons.Rounded.LibraryMusic,
                        if (filter == 2) "Sin álbumes guardados" else "No sigues a nadie",
                        if (filter == 2) "Guarda álbumes con el botón + de su página." else "Pulsa «Seguir» en la página de un artista.",
                    )
                }
            }
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
                actions.launch {
                    val id = actions.container.library.createPlaylist(name)
                    actions.nav.navigate(LocalPlaylistRoute(id))
                }
            },
        )
    }
}

@Composable
private fun PinnedRow(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        SpecialCover(icon, Modifier.size(64.dp))
        Spacer(Modifier.width(12.dp))
        Column {
            Text(title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
        }
    }
}

@Suppress("unused")
private val placeholderShape = CircleShape

@Suppress("unused")
private fun PlaylistItem.isLocal() = id.startsWith("local:")
