package com.lyra.music.ui.playlist

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.LibraryAdd
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.model.Song
import com.lyra.music.data.share.SharedPlaylist
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.BackBar
import com.lyra.music.ui.components.CollapsingTopBar
import com.lyra.music.ui.components.CollectionHeader
import com.lyra.music.ui.components.CollectionSkeleton
import com.lyra.music.ui.components.ErrorView
import com.lyra.music.ui.components.Loadable
import com.lyra.music.ui.components.LoadableCrossfade
import com.lyra.music.ui.components.Mosaic
import com.lyra.music.ui.components.PillButton
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.totalDurationText
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.navigation.SharedPlaylistRoute

/**
 * Playlist que te han pasado por enlace: se escucha tal cual y, si te gusta, se guarda en tu
 * biblioteca con un botón. Primero salen las canciones con lo que trae el enlace y enseguida
 * llegan sus carátulas y datos completos.
 */
@UnstableApi
@Composable
fun SharedPlaylistScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val container = actions.container
    val link = actions.sharedLink
    var state by remember(link) { mutableStateOf<Loadable<SharedPlaylist>>(Loadable.Loading) }
    var songs by remember(link) { mutableStateOf<List<Song>>(emptyList()) }
    var attempt by remember { mutableIntStateOf(0) }
    val contextId = "shared:${link.hashCode()}"

    LaunchedEffect(link, attempt) {
        if (link == null) {
            state = Loadable.Error("No hay ninguna playlist que abrir")
            return@LaunchedEffect
        }
        state = Loadable.Loading
        val shared = runCatching { container.sharing.load(link) }.getOrElse {
            state = Loadable.Error(it.message ?: "No se pudo abrir la playlist")
            return@LaunchedEffect
        }
        songs = shared.songs.map { it.toSong() }
        state = Loadable.Ready(shared)
        // Carátulas, álbumes y duraciones de verdad.
        val full = runCatching { container.music.songsByIds(songs.map { it.id }) }.getOrDefault(emptyMap())
        if (full.isNotEmpty()) songs = songs.map { full[it.id] ?: it }
    }

    val listState = rememberLazyListState()
    LoadableCrossfade(state) { loaded ->
        when (val s = loaded) {
            Loadable.Loading -> CollectionSkeleton()
            is Loadable.Error -> Column { BackBar(); ErrorView(s.message, onRetry = { attempt++ }) }
            is Loadable.Ready -> {
                val name = s.value.name
                var saving by remember { mutableStateOf(false) }
                val play = { index: Int -> actions.play(songs, index, fromLabel = name, contextId = contextId) }
                Box(Modifier.fillMaxSize()) {
                    LazyColumn(
                        state = listState,
                        modifier = Modifier.fillMaxSize(),
                        contentPadding = PaddingValues(bottom = contentPadding.calculateBottomPadding() + 24.dp),
                    ) {
                        item {
                            CollectionHeader(
                                title = name,
                                subtitle = null,
                                eyebrow = "Playlist compartida contigo",
                                meta = totalDurationText(songs),
                                backdrop = songs.firstOrNull()?.thumbnailUrl,
                                cover = { Mosaic(songs.mapNotNull { it.thumbnailUrl }.take(4), it, RoundedCornerShape(16.dp)) },
                                onPlay = { play(0) },
                                onShuffle = { actions.shuffle(songs, fromLabel = name, contextId = contextId) },
                                contextId = contextId,
                                listState = listState,
                            ) {
                                PillButton(
                                    if (saving) "Guardando…" else "Guardar",
                                    icon = Icons.Rounded.LibraryAdd,
                                    onClick = {
                                        if (!saving) {
                                            saving = true
                                            actions.launch {
                                                val id = container.library.createPlaylist(name, songs)
                                                actions.message("«$name» guardada en tu biblioteca")
                                                actions.nav.navigate(LocalPlaylistRoute(id)) {
                                                    popUpTo(SharedPlaylistRoute) { inclusive = true }
                                                }
                                            }
                                        }
                                    },
                                )
                            }
                        }
                        itemsIndexed(songs, key = { index, song -> "$index-${song.id}" }) { index, song ->
                            SongRow(song, onClick = { play(index) })
                        }
                    }
                    CollapsingTopBar(listState, name, contextId, onPlay = { play(0) })
                }
            }
        }
    }
}
