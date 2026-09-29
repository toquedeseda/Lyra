package com.lyra.music.ui.album

import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.lyra.music.AppContainer
import com.lyra.music.data.model.AlbumPage
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.BackBar
import com.lyra.music.ui.components.CollectionHeader
import com.lyra.music.ui.components.DownloadAllButton
import com.lyra.music.ui.components.ErrorView
import com.lyra.music.ui.components.LoadViewModel
import com.lyra.music.ui.components.Loadable
import com.lyra.music.ui.components.LoadingView
import com.lyra.music.ui.components.SectionView
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.theme.LyraColors
import androidx.compose.foundation.layout.Column

@UnstableApi
class AlbumViewModel(private val container: AppContainer, private val id: String) : LoadViewModel<AlbumPage>() {
    override suspend fun fetch(): AlbumPage = container.music.album(id)
    init { load() }
}

@UnstableApi
@Composable
fun AlbumScreen(id: String, contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val vm: AlbumViewModel = viewModel(key = id) { AlbumViewModel(actions.container, id) }
    val state by vm.state.collectAsState()

    when (val s = state) {
        Loadable.Loading -> Column { BackBar(); LoadingView() }
        is Loadable.Error -> Column { BackBar(); ErrorView(s.message, onRetry = vm::load) }
        is Loadable.Ready -> {
            val page = s.value
            val album = page.album
            val saved by actions.container.library.isAlbumSaved(album.id).collectAsState(initial = false)
            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 16.dp),
            ) {
                item {
                    CollectionHeader(
                        title = album.title,
                        subtitle = album.artistsText,
                        onSubtitleClick = { actions.openArtist(album.artists.firstOrNull()?.id) },
                        meta = listOfNotNull(album.kind, album.year, page.subtitle).joinToString(" • "),
                        cover = { Artwork(album.thumbnailUrl, it) },
                        onPlay = { actions.play(page.songs, 0, from = album) },
                        onShuffle = { actions.shuffle(page.songs, from = album) },
                    ) {
                        IconButton(onClick = {
                            actions.launch { actions.container.library.setAlbumSaved(album, !saved, page.songs) }
                            actions.message(if (saved) "Quitado de tu biblioteca" else "Guardado en tu biblioteca")
                        }) {
                            Icon(
                                if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                                if (saved) "Quitar de la biblioteca" else "Guardar en la biblioteca",
                                tint = if (saved) Color.White else LyraColors.TextSecondary,
                            )
                        }
                        DownloadAllButton(page.songs)
                        IconButton(onClick = { actions.addToPlaylist = page.songs }) {
                            Icon(Icons.AutoMirrored.Rounded.PlaylistAdd, "Añadir a playlist", tint = LyraColors.TextSecondary)
                        }
                    }
                }
                itemsIndexed(page.songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(
                        song,
                        onClick = { actions.play(page.songs, index, from = album) },
                        index = index + 1,
                        showArtwork = false,
                    )
                }
                page.sections.forEach { section ->
                    item(key = "s-${section.title}") { SectionView(section) }
                }
            }
        }
    }
}
