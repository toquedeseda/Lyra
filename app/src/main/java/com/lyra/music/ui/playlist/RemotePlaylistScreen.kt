package com.lyra.music.ui.playlist

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import com.lyra.music.AppContainer
import com.lyra.music.data.model.PlaylistPage
import com.lyra.music.data.model.Source
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
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.totalDurationText
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import kotlinx.coroutines.launch

@UnstableApi
class RemotePlaylistViewModel(private val container: AppContainer, private val id: String) : LoadViewModel<PlaylistPage>() {
    private var loadingMore = false

    override suspend fun fetch(): PlaylistPage = container.music.playlist(id)

    init { load() }

    fun loadMore() {
        val page = (state.value as? Loadable.Ready)?.value ?: return
        val token = page.continuation ?: return
        if (loadingMore) return
        loadingMore = true
        viewModelScope.launch {
            val result = runCatching { container.music.playlistMore(token) }.getOrNull()
            update { current ->
                if (result == null) current.copy(continuation = null)
                else current.copy(songs = (current.songs + result.first).distinctBy { it.id }, continuation = result.second)
            }
            loadingMore = false
        }
    }

    /** Todas las canciones (las playlists largas vienen por páginas). */
    suspend fun allSongs() = container.music.fullPlaylist(id).songs
}

@UnstableApi
@Composable
fun RemotePlaylistScreen(id: String, contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val vm: RemotePlaylistViewModel = viewModel(key = id) { RemotePlaylistViewModel(actions.container, id) }
    val state by vm.state.collectAsState()
    val listState = rememberLazyListState()
    val nearEnd by remember { derivedStateOf { (listState.layoutInfo.visibleItemsInfo.lastOrNull()?.index ?: 0) >= listState.layoutInfo.totalItemsCount - 5 } }
    LaunchedEffect(nearEnd, state) { if (nearEnd) vm.loadMore() }

    when (val s = state) {
        Loadable.Loading -> Column { BackBar(); LoadingView() }
        is Loadable.Error -> Column { BackBar(); ErrorView(s.message, onRetry = vm::load) }
        is Loadable.Ready -> {
            val page = s.value
            val playlist = page.playlist
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            ) {
                item {
                    CollectionHeader(
                        title = playlist.title,
                        subtitle = playlist.author,
                        eyebrow = if (Source.of(playlist.id) == Source.SOUNDCLOUD) "Playlist · SoundCloud" else "Playlist",
                        meta = page.subtitle ?: totalDurationText(page.songs),
                        description = page.description,
                        backdrop = playlist.thumbnailUrl,
                        cover = { Artwork(playlist.thumbnailUrl, it, RoundedCornerShape(16.dp)) },
                        onPlay = { actions.play(page.songs, 0, from = playlist) },
                        onShuffle = { actions.shuffle(page.songs, from = playlist) },
                    ) {
                        OutlineIconButton(Icons.Rounded.Add, "Guardar en la biblioteca", onClick = {
                            actions.launch {
                                val songs = runCatching { vm.allSongs() }.getOrDefault(page.songs)
                                val localId = actions.container.library.importPlaylist(playlist, songs)
                                actions.message("Guardada en tu biblioteca", "Abrir") { actions.nav.navigate(LocalPlaylistRoute(localId)) }
                            }
                        })
                        DownloadAllButton(page.songs, playlist.title)
                    }
                }
                itemsIndexed(page.songs, key = { _, song -> song.id }) { index, song ->
                    SongRow(song, onClick = { actions.play(page.songs, index, from = playlist) })
                }
            }
        }
    }
}
