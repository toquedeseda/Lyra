package com.lyra.music.ui.artist

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Radio
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.lyra.music.AppContainer
import com.lyra.music.data.model.ArtistPage
import com.lyra.music.data.model.Source
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.BackBar
import com.lyra.music.ui.components.ErrorView
import com.lyra.music.ui.components.Eyebrow
import com.lyra.music.ui.components.LoadViewModel
import com.lyra.music.ui.components.Loadable
import com.lyra.music.ui.components.LoadingView
import com.lyra.music.ui.components.OutlineIconButton
import com.lyra.music.ui.components.PlayCircleButton
import com.lyra.music.ui.components.SectionHeader
import com.lyra.music.ui.components.SectionView
import com.lyra.music.ui.components.ShuffleIconButton
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.pressable
import com.lyra.music.ui.navigation.BrowseRoute
import com.lyra.music.ui.navigation.PlaylistRoute
import com.lyra.music.ui.theme.LyraColors

@UnstableApi
class ArtistViewModel(private val container: AppContainer, private val id: String) : LoadViewModel<ArtistPage>() {
    override suspend fun fetch(): ArtistPage = container.music.artist(id)
    init { load() }
}

@UnstableApi
@Composable
fun ArtistScreen(id: String, contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val vm: ArtistViewModel = viewModel(key = id) { ArtistViewModel(actions.container, id) }
    val state by vm.state.collectAsState()

    when (val s = state) {
        Loadable.Loading -> Column { BackBar(); LoadingView() }
        is Loadable.Error -> Column { BackBar(); ErrorView(s.message, onRetry = vm::load) }
        is Loadable.Ready -> {
            val page = s.value
            val artist = page.artist
            val following by actions.container.library.isFollowing(artist.id).collectAsState(initial = false)
            var showAllTop by remember { mutableStateOf(false) }

            LazyColumn(
                modifier = Modifier.fillMaxSize(),
                contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
            ) {
                item {
                    Box(
                        Modifier
                            .fillMaxWidth()
                            .aspectRatio(1.1f),
                    ) {
                        AsyncImage(
                            model = page.bannerUrl ?: artist.thumbnailUrl,
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize(),
                        )
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(
                                    Brush.verticalGradient(
                                        0f to LyraColors.Background.copy(alpha = 0.45f),
                                        0.35f to LyraColors.Background.copy(alpha = 0f),
                                        0.75f to LyraColors.Background.copy(alpha = 0.7f),
                                        1f to LyraColors.Background,
                                    ),
                                ),
                        )
                        BackBar()
                        Column(
                            Modifier
                                .align(Alignment.BottomStart)
                                .padding(horizontal = 20.dp, vertical = 8.dp),
                        ) {
                            Eyebrow(if (Source.of(artist.id) == Source.SOUNDCLOUD) "Artista · SoundCloud" else "Artista")
                            Text(
                                artist.title,
                                style = MaterialTheme.typography.displayLarge,
                                maxLines = 2,
                                overflow = TextOverflow.Ellipsis,
                                modifier = Modifier.padding(top = 6.dp),
                            )
                            page.listeners?.let {
                                Text(it, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                            }
                        }
                    }
                }
                item {
                    Row(Modifier.padding(horizontal = 20.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            Modifier
                                .height(38.dp)
                                .clip(RoundedCornerShape(50))
                                .then(
                                    if (following) Modifier.background(LyraColors.SurfaceHigher)
                                    else Modifier.border(1.dp, LyraColors.TextTertiary, RoundedCornerShape(50)),
                                )
                                .pressable { actions.launch { actions.container.library.setFollowing(artist, !following) } }
                                .padding(horizontal = 18.dp),
                            contentAlignment = Alignment.Center,
                        ) {
                            Text(if (following) "Siguiendo" else "Seguir", style = MaterialTheme.typography.labelLarge)
                        }
                        Spacer(Modifier.width(10.dp))
                        if (page.radioPlaylistId != null) {
                            OutlineIconButton(Icons.Outlined.Radio, "Radio del artista", onClick = {
                                actions.container.player.startPlaylistRadio(page.radioPlaylistId, "Radio de ${artist.title}")
                            })
                        }
                        Spacer(Modifier.weight(1f))
                        ShuffleIconButton { actions.shuffle(page.topSongs, from = artist) }
                        Spacer(Modifier.width(8.dp))
                        PlayCircleButton({ actions.play(page.topSongs, 0, from = artist) })
                    }
                }
                if (page.topSongs.isNotEmpty()) {
                    item { SectionHeader("Populares") }
                    val visible = if (showAllTop) page.topSongs else page.topSongs.take(5)
                    itemsIndexed(visible, key = { _, song -> "top-${song.id}" }) { index, song ->
                        SongRow(song, onClick = { actions.play(page.topSongs, index, from = artist) }, index = index + 1)
                    }
                    item {
                        when {
                            page.topSongsMore != null -> TextButton(
                                onClick = { actions.nav.navigate(PlaylistRoute("yt:" + page.topSongsMore.browseId)) },
                                modifier = Modifier.padding(horizontal = 10.dp),
                            ) { Text("Ver todas las canciones", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
                            page.topSongs.size > 5 -> TextButton(
                                onClick = { showAllTop = !showAllTop },
                                modifier = Modifier.padding(horizontal = 10.dp),
                            ) { Text(if (showAllTop) "Ver menos" else "Ver más", color = LyraColors.TextSecondary, style = MaterialTheme.typography.labelLarge) }
                        }
                    }
                }
                page.sections.forEach { section ->
                    item(key = "s-${section.title}") {
                        SectionView(
                            section,
                            onMore = section.more?.let { more ->
                                {
                                    if (more.browseId.startsWith("VL")) actions.nav.navigate(PlaylistRoute("yt:" + more.browseId))
                                    else actions.nav.navigate(BrowseRoute(more.browseId, more.params, section.title))
                                }
                            },
                        )
                    }
                }
                page.description?.let { description ->
                    item {
                        SectionHeader("Sobre ${artist.title}")
                        Text(
                            description,
                            style = MaterialTheme.typography.bodyMedium,
                            color = LyraColors.TextSecondary,
                            modifier = Modifier
                                .padding(horizontal = 20.dp)
                                .clip(RoundedCornerShape(16.dp))
                                .background(LyraColors.SurfaceHigh)
                                .border(1.dp, LyraColors.Border, RoundedCornerShape(16.dp))
                                .padding(18.dp),
                            maxLines = 12,
                            overflow = TextOverflow.Ellipsis,
                        )
                        Spacer(Modifier.height(8.dp))
                    }
                }
            }
        }
    }
}
