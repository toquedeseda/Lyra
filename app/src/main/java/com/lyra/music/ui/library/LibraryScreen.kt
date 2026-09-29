package com.lyra.music.ui.library

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Favorite
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.db.DownloadState
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.ChipRow
import com.lyra.music.ui.components.GhostPillButton
import com.lyra.music.ui.components.InfoCard
import com.lyra.music.ui.components.ItemRow
import com.lyra.music.ui.components.Mosaic
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.components.SectionHeader
import com.lyra.music.ui.components.SpecialCover
import com.lyra.music.ui.components.TextInputDialog
import com.lyra.music.ui.components.pressable
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
    val completedDownloads = downloads.values.count { it.state == DownloadState.COMPLETED }

    var filter by rememberSaveable { mutableIntStateOf(0) }
    var creating by remember { mutableStateOf(false) }
    val filters = listOf("Todo", "Playlists", "Álbumes", "Artistas")
    val showPlaylists = filter == 0 || filter == 1

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
                GhostPillButton("Nueva", onClick = { creating = true }, icon = Icons.Rounded.Add, modifier = Modifier.padding(top = 10.dp))
            }
        }
        item {
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                PinnedCard(Icons.Rounded.Favorite, true, "Canciones que te gustan", "${liked.size} temas") {
                    actions.nav.navigate(LikedRoute)
                }
                PinnedCard(Icons.Rounded.ArrowDownward, false, "Descargas", "$completedDownloads temas sin conexión") {
                    actions.nav.navigate(DownloadsRoute)
                }
            }
        }
        item { ChipRow(filters, filter, { filter = it }, Modifier.padding(top = 22.dp)) }

        if (showPlaylists) {
            item { SectionHeader("Tus listas") }
            if (playlists.isEmpty()) {
                item {
                    InfoCard(
                        title = "Aún no tienes listas",
                        text = "Crea una aquí, o guarda cualquier playlist de YouTube Music o SoundCloud con el botón + de su página.",
                        modifier = Modifier.padding(horizontal = 20.dp),
                    ) {
                        PillButton("Nueva playlist", onClick = { creating = true })
                    }
                }
            }
            items(playlists, key = { "p${it.id}" }) { playlist ->
                val covers by library.playlistCovers(playlist.id).collectAsState(initial = emptyList())
                Row(
                    Modifier
                        .fillMaxWidth()
                        .pressable(pressedScale = 0.985f) { actions.nav.navigate(LocalPlaylistRoute(playlist.id)) }
                        .padding(horizontal = 20.dp, vertical = 7.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Mosaic(
                        if (covers.size >= 4) covers else listOfNotNull(playlist.coverUrl ?: covers.firstOrNull()),
                        Modifier.size(56.dp),
                        RoundedCornerShape(10.dp),
                    )
                    Spacer(Modifier.width(14.dp))
                    Column {
                        Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
                        Text(
                            "Playlist · ${playlist.songCount} temas" + if (playlist.remoteId != null) " · Importada" else "",
                            style = MaterialTheme.typography.bodySmall,
                            color = LyraColors.TextSecondary,
                        )
                    }
                }
            }
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
            items(albums, key = { "a${it.id}" }) { album -> ItemRow(album) }
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
            items(artists, key = { "r${it.id}" }) { artist -> ItemRow(artist) }
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
