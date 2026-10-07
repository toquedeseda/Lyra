package com.lyra.desktop.ui.screens

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.SearchOff
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.FilterChip
import com.lyra.desktop.ui.components.HoverBox
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LoadingView
import com.lyra.desktop.ui.components.MessageView
import com.lyra.desktop.ui.components.OutlinePill
import com.lyra.desktop.ui.components.PlayButton
import com.lyra.desktop.ui.components.SectionHeader
import com.lyra.desktop.ui.components.SongRow
import com.lyra.desktop.ui.components.art
import com.lyra.desktop.ui.components.cardSubtitle
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MoodGroup
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.SearchPage
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.LinkTarget
import com.lyra.music.data.repo.SearchTab
import com.lyra.music.data.repo.SpotifyImporter
import com.lyra.music.data.share.PlaylistSharing
import kotlinx.coroutines.delay
import kotlin.math.absoluteValue

/** Buscar: lo reciente y los géneros si no hay texto; si lo hay, resultados por pestañas. */
@Composable
fun SearchScreen(query: String, initialTab: Int) {
    val actions = LocalActions.current
    val app = actions.app
    var tab by rememberSaveable { mutableStateOf(initialTab) }
    val text = query.trim()

    if (text.isEmpty()) {
        BrowseAll()
        return
    }

    // Enlaces pegados: de YouTube, YouTube Music, SoundCloud, Spotify o de Lyra.
    if (text.startsWith("http")) {
        LinkResult(text)
        return
    }

    var page by remember(text, tab) { mutableStateOf<SearchPage?>(null) }
    var error by remember(text, tab) { mutableStateOf<String?>(null) }
    var loadingMore by remember(text, tab) { mutableStateOf(false) }
    val searchTab = SearchTab.entries[tab]
    LaunchedEffect(text, tab) {
        delay(280)
        val cacheKey = "search:$tab:$text"
        PageCache.get<SearchPage>(cacheKey)?.let { page = it; return@LaunchedEffect }
        runCatching { app.music.search(text, searchTab) }
            .onSuccess {
                page = it
                PageCache.put(cacheKey, it)
            }
            .onFailure { error = it.message ?: "No se pudo buscar" }
    }
    val list = rememberLazyListState()
    LaunchedEffect(list, page) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) ->
                val current = page ?: return@collect
                val token = current.continuation ?: return@collect
                if (tab != 0 && !loadingMore && last != null && last >= total - 3) {
                    loadingMore = true
                    runCatching { app.music.searchMore(token) }.onSuccess { more ->
                        page = current.copy(items = (current.items + more.items).distinctBy { it.id }, continuation = more.continuation)
                    }.onFailure { page = current.copy(continuation = null) }
                    loadingMore = false
                }
            }
    }

    ScreenList(state = list) {
        item {
            Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = PagePadding, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                SearchTab.entries.forEachIndexed { index, t ->
                    FilterChip(t.label, tab == index, onClick = {
                        tab = index
                        val current = actions.nav.current.screen
                        if (current is Screen.Search) actions.nav.replace(current.copy(tab = index))
                    })
                }
            }
        }
        val result = page
        when {
            error != null && result == null -> item { ErrorView(error) { error = null; tab = tab } }
            result == null -> item { LoadingView() }
            result.items.isEmpty() && result.topResult == null -> item {
                MessageView(Icons.Rounded.SearchOff, "Nada con «$text»", "Prueba con otras palabras o en la pestaña SoundCloud.")
            }
            tab == 0 -> item { AllResults(result, text) }
            else -> {
                val songs = result.items.filterIsInstance<Song>()
                val others = result.items.filterNot { it is Song }
                if (songs.isNotEmpty()) {
                    items(songs.size, key = { "r-${songs[it].id}-$it" }) { index ->
                        SongRow(
                            songs[index],
                            onPlay = {
                                app.library.addSearch(text)
                                actions.play(songs, index, com.lyra.desktop.player.PlayContext("Búsqueda «$text»"))
                            },
                            modifier = Modifier.padding(horizontal = PagePadding - 12.dp),
                            showAlbum = true,
                        )
                    }
                }
                if (others.isNotEmpty()) {
                    item { CardsGrid(others, Modifier.padding(horizontal = PagePadding - 10.dp, vertical = 8.dp)) }
                }
                if (result.continuation != null) item { LoadingView() }
            }
        }
    }
}

/** «Todo»: el mejor resultado, las primeras canciones y luego por tipos. */
@Composable
private fun AllResults(page: SearchPage, query: String) {
    val actions = LocalActions.current
    val songs = page.items.filterIsInstance<Song>().filterNot { it.isVideo }
    val videos = page.items.filterIsInstance<Song>().filter { it.isVideo }
    val top = page.topResult ?: page.items.firstOrNull()
    Column {
        BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = PagePadding, vertical = 8.dp)) {
            val wide = maxWidth > 760.dp
            if (wide) {
                Row {
                    if (top != null) {
                        Column(Modifier.width(380.dp)) {
                            SectionHeader("Mejor resultado")
                            TopResultCard(top, query)
                        }
                        Spacer(Modifier.width(20.dp))
                    }
                    Column(Modifier.weight(1f)) {
                        SectionHeader("Canciones")
                        songs.take(4).forEachIndexed { index, song ->
                            SongRow(song, onPlay = {
                                actions.app.library.addSearch(query)
                                actions.play(songs, index, com.lyra.desktop.player.PlayContext("Búsqueda «$query»"))
                            })
                        }
                    }
                }
            } else {
                Column {
                    if (top != null) {
                        SectionHeader("Mejor resultado")
                        TopResultCard(top, query)
                        Spacer(Modifier.height(16.dp))
                    }
                    SectionHeader("Canciones")
                    songs.take(4).forEachIndexed { index, song ->
                        SongRow(song, onPlay = { actions.play(songs, index) })
                    }
                }
            }
        }
        val groups = listOf(
            "Artistas" to page.items.filterIsInstance<ArtistItem>(),
            "Álbumes" to page.items.filterIsInstance<AlbumItem>(),
            "Playlists" to page.items.filterIsInstance<PlaylistItem>(),
            "Vídeos" to videos,
        )
        groups.filter { it.second.isNotEmpty() }.forEach { (title, items) ->
            Column(Modifier.padding(horizontal = PagePadding - 10.dp, vertical = 14.dp)) {
                SectionHeader(title, Modifier.padding(horizontal = 10.dp))
                CardsRow(items)
            }
        }
    }
}

@Composable
private fun TopResultCard(item: MusicItem, query: String) {
    val actions = LocalActions.current
    HoverBox(onClick = {
        actions.app.library.addSearch(query)
        actions.open(item)
    }, shape = RoundedCornerShape(10.dp), modifier = Modifier.fillMaxWidth().background(LyraColors.SurfaceHigh, RoundedCornerShape(10.dp))) { hovered ->
        Column(Modifier.fillMaxWidth().padding(20.dp)) {
            Cover(art(item.thumbnailUrl), Modifier.size(96.dp), if (item is ArtistItem) CircleShape else RoundedCornerShape(8.dp))
            Spacer(Modifier.height(18.dp))
            Text(item.title, style = MaterialTheme.typography.headlineLarge, color = LyraColors.TextPrimary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Spacer(Modifier.height(6.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    when (item) {
                        is Song -> if (item.isVideo) "Vídeo" else "Canción"
                        is AlbumItem -> item.kind ?: "Álbum"
                        is ArtistItem -> "Artista"
                        is PlaylistItem -> "Playlist"
                        else -> ""
                    },
                    style = MaterialTheme.typography.labelMedium,
                    color = LyraColors.TextPrimary,
                    modifier = Modifier.clip(RoundedCornerShape(50)).background(LyraColors.Background.copy(alpha = 0.5f)).padding(horizontal = 10.dp, vertical = 4.dp),
                )
                Spacer(Modifier.width(10.dp))
                Text(item.cardSubtitle(), style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
            }
        }
        AnimatedVisibility(hovered, Modifier.align(Alignment.BottomEnd).padding(18.dp), enter = fadeIn(), exit = fadeOut()) {
            PlayButton(false, onClick = { actions.playItem(item) }, size = 48.dp)
        }
    }
}

/** Sin texto: búsquedas recientes y todos los géneros y estados de ánimo. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BrowseAll() {
    val actions = LocalActions.current
    val app = actions.app
    val data by app.library.data.collectAsState()
    val (moods, retry) = rememberLoad("moods") { app.music.moods() }
    ScreenList {
        if (data.searches.isNotEmpty()) {
            item {
                Column(Modifier.padding(horizontal = PagePadding, vertical = 12.dp)) {
                    SectionHeader("Búsquedas recientes", onMore = { app.library.clearSearches() }, moreLabel = "Borrar todo")
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        data.searches.take(12).forEach { recent ->
                            Row(
                                Modifier.clip(RoundedCornerShape(50)).background(LyraColors.SurfaceHigh).padding(start = 12.dp),
                                verticalAlignment = Alignment.CenterVertically,
                            ) {
                                Icon(Icons.Rounded.History, null, tint = LyraColors.TextTertiary, modifier = Modifier.size(16.dp))
                                Spacer(Modifier.width(6.dp))
                                HoverBox(onClick = { actions.nav.replace(Screen.Search(recent)) }) {
                                    Text(recent, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextPrimary, modifier = Modifier.padding(vertical = 7.dp, horizontal = 4.dp))
                                }
                                IconBtn(Icons.Rounded.Close, "Quitar", onClick = { app.library.removeSearch(recent) }, size = 30.dp, iconSize = 14.dp)
                            }
                        }
                    }
                }
            }
        }
        item {
            Text("Explorar todo", style = MaterialTheme.typography.headlineSmall, color = LyraColors.TextPrimary, modifier = Modifier.padding(start = PagePadding, top = 16.dp, bottom = 12.dp))
        }
        when (moods) {
            is Load.Loading -> item { LoadingView() }
            is Load.Failed -> item { ErrorView(moods.message, retry) }
            is Load.Ready -> moods.value.forEach { group -> item(key = "g-" + group.title) { MoodGroupView(group) } }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun MoodGroupView(group: MoodGroup) {
    val actions = LocalActions.current
    Column(Modifier.padding(horizontal = PagePadding, vertical = 10.dp)) {
        Text(group.title, style = MaterialTheme.typography.titleMedium, color = LyraColors.TextSecondary, modifier = Modifier.padding(bottom = 10.dp))
        FlowRow(horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            group.categories.forEach { category ->
                val color = moodColor(category.title)
                HoverBox(onClick = { actions.nav.navigate(Screen.Browse(category.endpoint, category.title)) }, shape = RoundedCornerShape(10.dp)) { hovered ->
                    Box(Modifier.width(196.dp).height(104.dp).background(if (hovered) color.copy(alpha = 0.92f) else color, RoundedCornerShape(10.dp))) {
                        Text(category.title, style = MaterialTheme.typography.titleLarge, color = Color.White, modifier = Modifier.padding(14.dp), maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Box(Modifier.align(Alignment.BottomEnd).padding(end = 10.dp, bottom = 8.dp).size(42.dp).rotate(22f).background(Color.White.copy(alpha = 0.14f), RoundedCornerShape(8.dp)))
                    }
                }
            }
        }
    }
}

/** Colores apagados, siempre el mismo para cada género. */
private fun moodColor(title: String): Color {
    val palette = listOf(
        0xFF8C3B4A, 0xFF3B6E8C, 0xFF4A7A52, 0xFF8A5A2B, 0xFF5B4B8C, 0xFF7A3B6E, 0xFF2F6F6A,
        0xFF8C6A2F, 0xFF4F5D8C, 0xFF7A4A3B, 0xFF356B45, 0xFF6E3B3B, 0xFF3B4A7A, 0xFF6A5A3B,
    )
    return Color(palette[title.hashCode().absoluteValue % palette.size])
}

/** Un enlace pegado: se abre o suena directamente. */
@Composable
private fun LinkResult(url: String) {
    val actions = LocalActions.current
    val app = actions.app
    if (PlaylistSharing.isPlaylistLink(url)) {
        LaunchedEffect(url) { actions.nav.replace(Screen.SharedPlaylist(url)) }
        return
    }
    if (SpotifyImporter.parseLink(url) != null) {
        ScreenList {
            item {
                MessageView(
                    Icons.Rounded.Link,
                    "Enlace de Spotify",
                    "Lyra puede copiar esa playlist, álbum o canción buscándola en YouTube Music.",
                    action = "Importar de Spotify",
                    onAction = { actions.spotifyImport = url },
                )
            }
        }
        return
    }
    val target by produceState<Result<LinkTarget?>?>(null, url) {
        value = runCatching { app.music.resolveLink(url) }
    }
    ScreenList {
        val result = target
        when {
            result == null -> item { LoadingView() }
            result.getOrNull() == null -> item {
                MessageView(Icons.Rounded.Link, "No reconozco ese enlace", "Pega enlaces de YouTube, YouTube Music, SoundCloud o Spotify.")
            }
            else -> item {
                when (val link = result.getOrNull()!!) {
                    is LinkTarget.PlaySong -> Column(Modifier.padding(horizontal = PagePadding, vertical = 12.dp)) {
                        SectionHeader("Del enlace")
                        SongRow(link.song, onPlay = { app.player.startRadio(link.song) })
                        Spacer(Modifier.height(12.dp))
                        OutlinePill("Reproducir", onClick = { app.player.startRadio(link.song) })
                    }
                    is LinkTarget.Open -> LaunchedEffect(link) { actions.open(link.item) }
                }
            }
        }
    }
}
