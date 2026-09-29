package com.lyra.music.ui.album

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.Check
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
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
import com.lyra.music.ui.components.OutlineIconButton
import com.lyra.music.ui.components.SectionView
import com.lyra.music.ui.components.SongRow

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
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            ) {
                item {
                    CollectionHeader(
                        title = album.title,
                        subtitle = album.artistsText,
                        onSubtitleClick = { actions.openArtist(album.artists.firstOrNull()?.id) },
                        eyebrow = listOfNotNull(album.kind ?: "Álbum", album.year).joinToString(" · "),
                        meta = page.subtitle,
                        backdrop = album.thumbnailUrl,
                        cover = { Artwork(album.thumbnailUrl, it, RoundedCornerShape(16.dp)) },
                        onPlay = { actions.play(page.songs, 0, from = album) },
                        onShuffle = { actions.shuffle(page.songs, from = album) },
                    ) {
                        OutlineIconButton(
                            if (saved) Icons.Rounded.Check else Icons.Rounded.Add,
                            if (saved) "Quitar de la biblioteca" else "Guardar en la biblioteca",
                            onClick = {
                                actions.launch { actions.container.library.setAlbumSaved(album, !saved, page.songs) }
                                actions.message(if (saved) "Quitado de tu biblioteca" else "Guardado en tu biblioteca")
                            },
                        )
                        DownloadAllButton(page.songs)
                        OutlineIconButton(Icons.AutoMirrored.Rounded.PlaylistAdd, "Añadir a playlist", onClick = { actions.addToPlaylist = page.songs })
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
