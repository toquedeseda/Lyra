package com.lyra.desktop.ui.screens

import com.lyra.desktop.ui.components.FilledPill
import androidx.compose.material3.Icon
import androidx.compose.material.icons.rounded.Sync
import androidx.compose.material.icons.rounded.Close
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.lyra.desktop.data.Library
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.FilterChip
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LoadingView
import com.lyra.desktop.ui.components.Mosaic
import com.lyra.desktop.ui.components.QuickTile
import com.lyra.desktop.ui.components.SpecialCover
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.Chip
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.Section
import kotlinx.coroutines.launch
import java.time.LocalTime

/** El Inicio: saludo, accesos rápidos, tus mixes y lo de YouTube Music. */
@Composable
fun HomeScreen() {
    val actions = LocalActions.current
    val app = actions.app
    val feed by app.home.feed.collectAsState()
    val scope = rememberCoroutineScope()
    var chip by rememberSaveable { mutableStateOf<String?>(null) }
    var chipSections by remember { mutableStateOf<List<Section>?>(null) }
    var chipFailed by remember { mutableStateOf(false) }
    var chipRetry by remember { mutableStateOf(0) }
    val list = rememberLazyListState()

    LaunchedEffect(Unit) { app.home.refresh() }
    LaunchedEffect(chip, chipRetry) {
        val selected = feed.chips.firstOrNull { it.title == chip }
        chipSections = null
        chipFailed = false
        if (selected != null) {
            val result = runCatching { app.home.chipSections(selected) }
            chipFailed = result.isFailure
            chipSections = result.getOrDefault(emptyList())
        }
    }
    // Más secciones al llegar abajo.
    LaunchedEffect(list) {
        snapshotFlow { list.layoutInfo.visibleItemsInfo.lastOrNull()?.index to list.layoutInfo.totalItemsCount }
            .collect { (last, total) -> if (last != null && total > 0 && last >= total - 2 && chip == null) app.home.loadMore() }
    }

    ScreenList(state = list) {
        item {
            Row(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding - 8.dp, top = 8.dp, bottom = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(greeting(), style = MaterialTheme.typography.displaySmall, color = LyraColors.TextPrimary, modifier = Modifier.weight(1f))
                IconBtn(Icons.Rounded.Refresh, "Actualizar", onClick = { scope.launch { app.home.refresh(force = true) } })
            }
        }
        if (feed.chips.isNotEmpty()) {
            item {
                Row(Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = PagePadding, vertical = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip("Todo", chip == null, onClick = { chip = null })
                    feed.chips.take(9).forEach { c: Chip ->
                        FilterChip(c.title, chip == c.title, onClick = { chip = if (chip == c.title) null else c.title })
                    }
                }
            }
        }
        if (chip == null) item { SyncHint() }
        if (chip == null && feed.quickAccess.isNotEmpty()) {
            item { QuickAccess(feed.quickAccess) }
        }
        val sections = if (chip != null) chipSections else feed.sections
        when {
            chip != null && chipFailed -> item { ErrorView(null) { chipRetry++ } }
            sections == null || (sections.isEmpty() && feed.loading) -> item { LoadingView() }
            sections.isEmpty() && feed.error != null -> item { ErrorView(feed.error) { scope.launch { app.home.refresh(force = true) } } }
            else -> items(sections, key = { "s-" + it.title }) { section -> SectionView(section) }
        }
        if (chip == null && feed.canLoadMore) item { LoadingView() }
    }
}

/** Con la biblioteca vacía y sin emparejar: invita a traer la del móvil. */
@Composable
private fun SyncHint() {
    val actions = LocalActions.current
    val app = actions.app
    val settings by app.settings.flow.collectAsState()
    val data by app.library.data.collectAsState()
    val status by app.sync.status.collectAsState()
    val empty = data.liked.isEmpty() && data.playlists.isEmpty() && data.albums.isEmpty()
    if (!empty || settings.syncHintDismissed || status !is com.lyra.desktop.sync.SyncStatus.Off) return
    Row(
        Modifier
            .padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(LyraColors.SurfaceHigh)
            .padding(start = 20.dp, end = 10.dp, top = 16.dp, bottom = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(Icons.Rounded.Sync, null, tint = LyraColors.Accent, modifier = Modifier.size(28.dp))
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f)) {
            Text("¿Usas Lyra en el móvil?", style = MaterialTheme.typography.titleMedium, color = LyraColors.TextPrimary)
            Text(
                "Trae aquí tus Me gusta, playlists y carpetas. En el móvil: Ajustes → Biblioteca sincronizada → Activar; luego escribe el código en Ajustes de aquí.",
                style = MaterialTheme.typography.bodySmall,
                color = LyraColors.TextSecondary,
            )
        }
        Spacer(Modifier.width(12.dp))
        FilledPill("Sincronizar", onClick = { actions.nav.navigate(com.lyra.desktop.ui.Screen.Settings) })
        IconBtn(Icons.Rounded.Close, "No, gracias", onClick = { app.settings.update { it.copy(syncHintDismissed = true) } })
    }
}

/** Los ocho accesos rápidos de arriba (Me gusta y lo último que has puesto). */
@Composable
private fun QuickAccess(items: List<MusicItem>) {
    val actions = LocalActions.current
    val data by actions.app.library.data.collectAsState()
    BoxWithConstraints(Modifier.fillMaxWidth().padding(start = PagePadding, end = PagePadding, top = 8.dp, bottom = 18.dp)) {
        val columns = when {
            maxWidth > 1000.dp -> 4
            maxWidth > 560.dp -> 2
            else -> 1
        }
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items.chunked(columns).forEach { row ->
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    row.forEach { item ->
                        QuickTile(
                            item,
                            cover = { modifier ->
                                when {
                                    item.id == Library.LIKED_ID -> SpecialCover(Icons.Rounded.Favorite, modifier, shape = RoundedCornerShape(0.dp))
                                    item.id.startsWith(Library.LOCAL_PREFIX) -> {
                                        val playlist = data.playlists.firstOrNull { Library.LOCAL_PREFIX + it.id == item.id }
                                        val custom = playlist?.customCover ?: playlist?.coverUrl
                                        if (custom != null || playlist == null) Cover(custom ?: item.thumbnailUrl, modifier, RoundedCornerShape(0.dp))
                                        else Mosaic(playlist.songIds.take(8).map { data.songs[it]?.thumbnailUrl }, modifier, RoundedCornerShape(0.dp))
                                    }
                                    item is ArtistItem -> Cover(item.thumbnailUrl, modifier, RoundedCornerShape(0.dp))
                                    else -> Cover(item.thumbnailUrl, modifier, RoundedCornerShape(0.dp))
                                }
                            },
                            onOpen = { actions.open(item) },
                            onPlay = { actions.playItem(item) },
                            modifier = Modifier.weight(1f),
                        )
                    }
                    repeat(columns - row.size) { Spacer(Modifier.weight(1f)) }
                }
            }
        }
    }
}

private fun greeting(): String {
    val hour = LocalTime.now().hour
    return when (hour) {
        in 6..13 -> "Buenos días"
        in 14..20 -> "Buenas tardes"
        else -> "Buenas noches"
    }
}
