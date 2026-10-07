package com.lyra.desktop.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.VerticalScrollbar
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.rememberScrollbarAdapter
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyra.desktop.data.RightPanel
import com.lyra.desktop.player.QueueItem
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.HoverBox
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LinkText
import com.lyra.desktop.ui.components.LoadingView
import com.lyra.desktop.ui.components.LyraMenu
import com.lyra.desktop.ui.components.MenuState
import com.lyra.desktop.ui.components.MessageView
import com.lyra.desktop.ui.components.PlayingBars
import com.lyra.desktop.ui.components.SongMenuEntries
import com.lyra.desktop.ui.components.contextMenu
import com.lyra.desktop.ui.components.MenuEntry
import com.lyra.music.data.source.lyrics.Lyrics

@Composable
fun RightPanelView(panel: RightPanel, modifier: Modifier = Modifier) {
    val app = LocalActions.current.app
    Column(modifier) {
        Row(Modifier.fillMaxWidth().padding(start = 18.dp, end = 8.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(
                when (panel) {
                    RightPanel.QUEUE -> "Cola"
                    RightPanel.LYRICS -> "Letra"
                    else -> "Sonando"
                },
                style = MaterialTheme.typography.titleMedium,
                color = LyraColors.TextPrimary,
                modifier = Modifier.weight(1f),
            )
            IconBtn(Icons.Rounded.Close, "Cerrar", onClick = { app.settings.update { it.copy(rightPanel = RightPanel.NONE) } }, size = 32.dp, iconSize = 18.dp)
        }
        Box(Modifier.weight(1f).fillMaxWidth()) {
            when (panel) {
                RightPanel.QUEUE -> QueuePanel()
                RightPanel.LYRICS -> LyricsPanel()
                else -> NowPlayingPanel()
            }
        }
    }
}

// ---------------------------------------------------------------------- cola

@Composable
private fun QueuePanel() {
    val actions = LocalActions.current
    val app = actions.app
    val state by app.player.state.collectAsState()
    val current = state.current
    if (current == null) {
        MessageView(Icons.AutoMirrored.Rounded.QueueMusic, "La cola está vacía", "Pon una canción, un álbum o una playlist.")
        return
    }
    val upcoming = state.queue.drop(state.index + 1)
    val manual = upcoming.take(state.manualCount)
    val rest = upcoming.drop(state.manualCount)
    val list = rememberLazyListState()
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(start = 8.dp, end = 8.dp, bottom = 16.dp)) {
            item { QueueHeading("Sonando") }
            item(key = "actual-${current.uid}") { QueueRow(current, isCurrent = true) }
            if (manual.isNotEmpty()) {
                item { QueueHeading("En cola") }
                itemsIndexed(manual, key = { _, it -> "m-${it.uid}" }) { _, item -> QueueRow(item) }
            }
            if (rest.isNotEmpty()) {
                item {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        QueueHeading(state.context?.label?.let { "Después: de $it" } ?: "Después", Modifier.weight(1f))
                        LinkText("Vaciar", onClick = { app.player.clearUpcoming() }, modifier = Modifier.padding(end = 10.dp, top = 14.dp))
                    }
                }
                itemsIndexed(rest, key = { _, it -> "r-${it.uid}" }) { index, item ->
                    if (item.radio && (index == 0 || !rest[index - 1].radio)) {
                        Row(Modifier.padding(start = 10.dp, top = 12.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                            Icon(Icons.Rounded.Radio, null, tint = LyraColors.TextTertiary, modifier = Modifier.size(14.dp))
                            Spacer(Modifier.width(6.dp))
                            Text("Radio · después de tu lista", style = MaterialTheme.typography.labelMedium, color = LyraColors.TextTertiary)
                        }
                    }
                    QueueRow(item)
                }
            }
            if (state.radioLoading) item { LoadingView() }
        }
        VerticalScrollbar(rememberScrollbarAdapter(list), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp))
    }
}

@Composable
private fun QueueHeading(text: String, modifier: Modifier = Modifier) {
    Text(text, style = MaterialTheme.typography.titleSmall, color = LyraColors.TextPrimary, modifier = modifier.padding(start = 10.dp, top = 14.dp, bottom = 6.dp), maxLines = 1, overflow = TextOverflow.Ellipsis)
}

@Composable
private fun QueueRow(item: QueueItem, isCurrent: Boolean = false) {
    val app = LocalActions.current.app
    val now = com.lyra.desktop.ui.components.LocalNowPlaying.current
    val menu = remember { MenuState() }
    Box(Modifier.contextMenu(menu)) {
        HoverBox(onClick = { if (isCurrent) app.player.togglePlay() else app.player.playAt(item.uid) }, selected = menu.open) { hovered ->
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                Box {
                    Cover(item.song.thumbnailUrl, Modifier.size(44.dp), RoundedCornerShape(4.dp))
                    if (isCurrent) {
                        Box(Modifier.size(44.dp).background(androidx.compose.ui.graphics.Color(0x88000000), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                            PlayingBars(now.playing, Modifier.size(14.dp))
                        }
                    }
                }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(item.song.title, style = MaterialTheme.typography.bodyMedium, color = if (isCurrent) LyraColors.Accent else LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(item.song.artistsText, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (hovered && !isCurrent) {
                    IconBtn(Icons.Rounded.Close, "Quitar de la cola", onClick = { app.player.removeFromQueue(item.uid) }, size = 28.dp, iconSize = 16.dp)
                }
            }
        }
        LyraMenu(menu) { close ->
            if (!isCurrent) MenuEntry("Quitar de la cola", Icons.Rounded.Close, { close(); app.player.removeFromQueue(item.uid) })
            SongMenuEntries(item.song, close)
        }
    }
}

// ---------------------------------------------------------------------- letra

@Composable
private fun LyricsPanel() {
    val app = LocalActions.current.app
    val state by app.player.state.collectAsState()
    val song = state.current?.song
    if (song == null) {
        MessageView(Icons.Rounded.Lyrics, "Sin canción", "Pon algo y aquí saldrá su letra.")
        return
    }
    var lyrics by remember(song.id) { mutableStateOf<Lyrics?>(null) }
    var loading by remember(song.id) { mutableStateOf(true) }
    LaunchedEffect(song.id) {
        loading = true
        lyrics = runCatching { app.lyrics.lyrics(song) }.getOrNull()
        loading = false
    }
    when {
        loading -> LoadingView()
        lyrics == null -> MessageView(Icons.Rounded.Lyrics, "No hay letra", "No he encontrado la letra de esta canción.")
        lyrics!!.isSynced -> SyncedLyrics(lyrics!!)
        else -> PlainLyrics(lyrics!!)
    }
}

@Composable
private fun SyncedLyrics(lyrics: Lyrics) {
    val app = LocalActions.current.app
    val lines = lyrics.synced.orEmpty()
    val (position, _) = rememberProgress()
    // La línea que suena (un pelín adelantada, como en el móvil).
    val active = lines.indexOfLast { it.timeMs <= position + 250 }
    val list = rememberLazyListState()
    // Si mueves tú la letra, no se recoloca hasta unos segundos después (como en Spotify).
    var userScrolledAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableStateOf(false) }
    LaunchedEffect(list) {
        snapshotFlow { list.isScrollInProgress }.collect { scrolling ->
            if (scrolling && !autoScrolling) userScrolledAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(active) {
        if (active < 0 || System.currentTimeMillis() - userScrolledAt < 4_000) return@LaunchedEffect
        autoScrolling = true
        try {
            list.animateScrollToItem((active - 3).coerceAtLeast(0))
        } finally {
            autoScrolling = false
        }
    }
    Box(Modifier.fillMaxSize()) {
        LazyColumn(state = list, contentPadding = PaddingValues(start = 22.dp, end = 22.dp, top = 12.dp, bottom = 220.dp)) {
            itemsIndexed(lines) { index, line ->
                val color by animateColorAsState(
                    when {
                        index == active -> LyraColors.TextPrimary
                        index < active -> LyraColors.TextSecondary
                        else -> LyraColors.TextTertiary
                    },
                    tween(300),
                )
                HoverBox(onClick = { app.player.seek(line.timeMs) }, modifier = Modifier.fillMaxWidth()) {
                    Text(
                        line.text.ifBlank { "♪" },
                        style = MaterialTheme.typography.titleLarge.copy(fontSize = 22.sp, lineHeight = 30.sp, fontWeight = FontWeight.Bold),
                        color = color,
                        modifier = Modifier.padding(vertical = 6.dp, horizontal = 4.dp),
                    )
                }
            }
            item {
                Text("Letra: ${lyrics.source}", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary, modifier = Modifier.padding(top = 24.dp))
            }
        }
    }
}

@Composable
private fun PlainLyrics(lyrics: Lyrics) {
    val scroll = rememberScrollState()
    Box(Modifier.fillMaxSize()) {
        Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 22.dp, vertical = 12.dp)) {
            Text(lyrics.plain.orEmpty(), style = MaterialTheme.typography.titleMedium.copy(lineHeight = 26.sp), color = LyraColors.TextPrimary)
            Spacer(Modifier.height(20.dp))
            Text("Letra: ${lyrics.source}", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary)
            Spacer(Modifier.height(40.dp))
        }
        VerticalScrollbar(androidx.compose.foundation.rememberScrollbarAdapter(scroll), Modifier.align(Alignment.CenterEnd).fillMaxHeight().width(8.dp))
    }
}

// ---------------------------------------------------------------------- sonando ahora

@Composable
private fun NowPlayingPanel() {
    val actions = LocalActions.current
    val app = actions.app
    val state by app.player.state.collectAsState()
    val liked by app.library.likedIds.collectAsState()
    val song = state.current?.song
    if (song == null) {
        MessageView(Icons.AutoMirrored.Rounded.QueueMusic, "No suena nada", "Elige algo en el Inicio o búscalo.")
        return
    }
    val scroll = rememberScrollState()
    Column(Modifier.fillMaxSize().verticalScroll(scroll).padding(horizontal = 18.dp)) {
        Spacer(Modifier.height(6.dp))
        Cover(song.thumbnailUrl?.let(::bigArtwork), Modifier.fillMaxWidth().aspectRatio(1f).shadow(18.dp, RoundedCornerShape(10.dp)), RoundedCornerShape(10.dp))
        Spacer(Modifier.height(18.dp))
        Row(verticalAlignment = Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(4.dp))
                Text(song.artistsText, style = MaterialTheme.typography.bodyLarge, color = LyraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
            val isLiked = song.id in liked
            IconBtn(
                if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                if (isLiked) "Quitar de Me gusta" else "Añadir a Me gusta",
                onClick = { actions.toggleLike(song) },
                tint = if (isLiked) LyraColors.Like else LyraColors.TextSecondary,
                hoverTint = if (isLiked) LyraColors.Like else LyraColors.TextPrimary,
            )
        }
        song.album?.let { album ->
            Spacer(Modifier.height(10.dp))
            if (album.id != null) LinkText("Álbum: ${album.title}", onClick = { actions.goToAlbum(song) }) else Text("Álbum: ${album.title}", style = MaterialTheme.typography.labelLarge, color = LyraColors.TextSecondary)
        }
        state.context?.let { context ->
            Spacer(Modifier.height(6.dp))
            Text("Reproduciendo desde ${context.label}", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary)
        }
        val next = state.queue.getOrNull(state.index + 1)
        if (next != null) {
            Spacer(Modifier.height(22.dp))
            Column(Modifier.fillMaxWidth().background(LyraColors.SurfaceHigh, RoundedCornerShape(10.dp)).padding(12.dp)) {
                Text("A continuación", style = MaterialTheme.typography.titleSmall, color = LyraColors.TextPrimary)
                Spacer(Modifier.height(8.dp))
                QueueRow(next)
            }
        }
        Spacer(Modifier.height(24.dp))
    }
}

/** Carátula grande (las de YouTube Music llegan pequeñas por defecto). */
fun bigArtwork(url: String): String = com.lyra.music.data.source.innertube.hiResArtwork(url) ?: url
