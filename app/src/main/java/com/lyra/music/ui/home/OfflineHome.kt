package com.lyra.music.ui.home

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
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CloudOff
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.db.PlaylistDownloads
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.EmptyView
import com.lyra.music.ui.components.Eyebrow
import com.lyra.music.ui.components.GhostPillButton
import com.lyra.music.ui.components.Mosaic
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.components.SectionHeader
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.pressable
import com.lyra.music.ui.navigation.DownloadsRoute
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.theme.LyraColors

/** Inicio sin internet: lo que tienes descargado, listo para sonar, en vez de errores. */
@UnstableApi
@Composable
fun OfflineHome(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val container = actions.container
    val rows by container.downloads.downloads.collectAsState(initial = null)
    val liked by container.library.likedIds.collectAsState()
    val playlists by container.library.playlistDownloads.collectAsState(initial = emptyList())
    val downloaded = rows.orEmpty().filter { it.downloadState == DownloadState.COMPLETED }.map { it.toSong() }
    val likedDownloaded = downloaded.filter { it.id in liked }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
    ) {
        item {
            Column(
                Modifier
                    .statusBarsPadding()
                    .padding(start = 20.dp, end = 20.dp, top = 30.dp),
            ) {
                Eyebrow("Lyra · sin conexión")
                Text("Sin conexión", style = MaterialTheme.typography.displayMedium, modifier = Modifier.padding(top = 6.dp))
                Text(
                    if (downloaded.isEmpty()) "Ahora mismo no hay internet y no tienes canciones descargadas."
                    else "Puedes escuchar las ${downloaded.size} canciones que tienes descargadas. Lo demás vuelve en cuanto haya internet.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = LyraColors.TextSecondary,
                    modifier = Modifier.padding(top = 10.dp),
                )
                if (downloaded.isNotEmpty()) {
                    Row(Modifier.padding(top = 18.dp), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                        PillButton("Aleatorio", icon = Icons.Rounded.Shuffle, onClick = { actions.shuffle(downloaded, fromLabel = "Descargas") })
                        GhostPillButton("Ver descargas", icon = Icons.Rounded.ArrowDownward, onClick = { actions.nav.navigate(DownloadsRoute) })
                    }
                }
            }
        }
        if (rows != null && downloaded.isEmpty()) {
            item {
                EmptyView(
                    Icons.Rounded.CloudOff,
                    "Nada para escuchar sin conexión",
                    "Cuando vuelva internet, descarga canciones o playlists (flecha ↓) para tenerlas siempre.",
                    modifier = Modifier.padding(top = 20.dp),
                )
            }
        }
        if (likedDownloaded.isNotEmpty()) {
            item { SectionHeader("Me gusta", if (likedDownloaded.size == 1) "1 descargada" else "${likedDownloaded.size} descargadas") }
            itemsIndexed(likedDownloaded.take(5), key = { _, song -> "l${song.id}" }) { index, song ->
                SongRow(song, onClick = { actions.play(likedDownloaded, index, fromLabel = "Canciones que te gustan") })
            }
        }
        if (playlists.isNotEmpty()) {
            item { SectionHeader("Tus listas") }
            items(playlists, key = { "p${it.id}" }) { OfflinePlaylistRow(it) }
        }
        if (downloaded.isNotEmpty()) {
            item { SectionHeader("Descargadas hace poco") }
            itemsIndexed(downloaded.take(30), key = { _, song -> "d${song.id}" }) { index, song ->
                SongRow(song, onClick = { actions.play(downloaded, index, fromLabel = "Descargas") })
            }
        }
    }
}

@UnstableApi
@Composable
private fun OfflinePlaylistRow(playlist: PlaylistDownloads) {
    val actions = LocalActions.current
    val covers by actions.container.library.playlistCovers(playlist.id).collectAsState(initial = emptyList())
    Row(
        Modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f) { actions.nav.navigate(LocalPlaylistRoute(playlist.id)) }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        val custom = playlist.customCover
        Mosaic(
            when {
                custom != null -> listOf(custom)
                covers.size >= 4 -> covers
                else -> listOfNotNull(playlist.coverUrl ?: covers.firstOrNull())
            },
            Modifier.size(56.dp),
            RoundedCornerShape(10.dp),
        )
        Spacer(Modifier.width(14.dp))
        Column {
            Text(playlist.name, style = MaterialTheme.typography.bodyLarge, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                if (playlist.downloaded == playlist.total) "Toda descargada · ${playlist.total} temas"
                else "${playlist.downloaded} de ${playlist.total} descargadas",
                style = MaterialTheme.typography.bodySmall,
                color = LyraColors.TextSecondary,
            )
        }
    }
}
