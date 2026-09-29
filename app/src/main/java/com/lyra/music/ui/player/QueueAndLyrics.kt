package com.lyra.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.animateColorAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.DragHandle
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.SwipeToDismissBox
import androidx.compose.material3.SwipeToDismissBoxValue
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.material3.rememberSwipeToDismissBoxState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.lyrics.Lyrics
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.SongRow
import com.lyra.music.ui.components.rememberReorderState
import com.lyra.music.ui.components.reorderHandle
import com.lyra.music.ui.components.reorderItem
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.launch

private data class QueueEntry(val index: Int, val song: Song)

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun QueueSheet(onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val player = actions.container.player
    val state by player.state.collectAsState()
    val settings by actions.container.settings.flow.collectAsState()

    // Lo que viene después, en el orden real (respeta el aleatorio).
    val upcoming = remember { mutableStateListOf<QueueEntry>() }
    LaunchedEffect(state.queue, state.currentIndex, state.shuffle) {
        upcoming.clear()
        upcoming.addAll(player.upcomingIndices().mapNotNull { index -> state.queue.getOrNull(index)?.let { QueueEntry(index, it) } })
    }

    val listState = rememberLazyListState()
    val reorder = rememberReorderState(
        listState = listState,
        firstIndex = 2,
        onMove = { from, to -> if (from in upcoming.indices && to in upcoming.indices) upcoming.add(to, upcoming.removeAt(from)) },
        onDrop = { from, to ->
            // En la cola real las posiciones van seguidas tras la actual (sin aleatorio).
            val base = state.currentIndex + 1
            player.move(base + from, base + to)
        },
    )

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true),
        containerColor = LyraColors.Surface,
    ) {
        LazyColumn(state = listState, modifier = Modifier.navigationBarsPadding(), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                Text("Reproduciendo ahora", style = MaterialTheme.typography.headlineMedium, modifier = Modifier.padding(horizontal = 20.dp, vertical = 8.dp))
                state.song?.let { SongRow(it, onClick = {}) }
            }
            item {
                Row(Modifier.padding(start = 20.dp, end = 8.dp, top = 18.dp), verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("A continuación", style = MaterialTheme.typography.headlineSmall)
                        Text(
                            if (state.shuffle) "Aleatorio activado · desactívalo para reordenar" else "Mantén el asa para reordenar · desliza para quitar",
                            style = MaterialTheme.typography.bodySmall,
                            color = LyraColors.TextSecondary,
                        )
                    }
                    if (upcoming.isNotEmpty()) TextButton(onClick = player::clearUpcoming) { Text("Vaciar", color = LyraColors.TextSecondary) }
                }
                Row(Modifier.padding(horizontal = 20.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Radio infinita al acabar", style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
                    Switch(
                        checked = settings.infiniteRadio,
                        onCheckedChange = { value -> actions.launch { actions.container.settings.update { it.copy(infiniteRadio = value) } } },
                        colors = SwitchDefaults.colors(
                            checkedThumbColor = LyraColors.OnAccent,
                            checkedTrackColor = LyraColors.Accent,
                            uncheckedThumbColor = LyraColors.TextSecondary,
                            uncheckedTrackColor = LyraColors.SurfaceHigher,
                            uncheckedBorderColor = LyraColors.SurfaceHigher,
                        ),
                    )
                }
            }
            itemsIndexed(upcoming, key = { _, entry -> "${entry.index}-${entry.song.id}" }) { position, entry ->
                val dismiss = rememberSwipeToDismissBoxState()
                LaunchedEffect(dismiss.currentValue) {
                    if (dismiss.currentValue != SwipeToDismissBoxValue.Settled) player.remove(entry.index)
                }
                SwipeToDismissBox(
                    state = dismiss,
                    backgroundContent = {
                        Box(
                            Modifier
                                .fillMaxSize()
                                .background(LyraColors.SurfaceHigher)
                                .padding(horizontal = 24.dp),
                            contentAlignment = Alignment.CenterEnd,
                        ) { Text("Quitar", color = LyraColors.TextPrimary, style = MaterialTheme.typography.labelLarge) }
                    },
                    modifier = Modifier.reorderItem(reorder, position),
                ) {
                    Box(Modifier.background(LyraColors.Surface)) {
                        SongRow(
                            entry.song,
                            onClick = { player.skipTo(entry.index) },
                            queueIndex = entry.index,
                            trailing = if (!state.shuffle) {
                                {
                                    Icon(
                                        Icons.Rounded.DragHandle,
                                        "Arrastrar",
                                        tint = LyraColors.TextSecondary,
                                        modifier = Modifier
                                            .padding(12.dp)
                                            .size(24.dp)
                                            .reorderHandle(reorder, position),
                                    )
                                }
                            } else null,
                        )
                    }
                }
            }
        }
    }
}

@UnstableApi
@Composable
fun LyricsFullScreen(song: Song, lyrics: Lyrics, onClose: () -> Unit) {
    val player = LocalActions.current.container.player
    val progress by player.progress.collectAsState()
    BackHandler(onBack = onClose)

    Column(
        Modifier
            .fillMaxSize()
            .background(Brush.verticalGradient(listOf(LyraColors.SurfaceHigher, LyraColors.Background)))
            .statusBarsPadding()
            .navigationBarsPadding(),
    ) {
        Row(Modifier.padding(4.dp), verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", modifier = Modifier.size(32.dp)) }
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleMedium, maxLines = 1)
                Text(song.artistsText, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1)
            }
        }
        val synced = lyrics.synced
        if (synced == null) {
            Text(
                lyrics.plain.orEmpty(),
                style = MaterialTheme.typography.headlineSmall,
                modifier = Modifier
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            )
        } else {
            val current = synced.indexOfLast { it.timeMs <= progress.positionMs + 300 }
            val listState = rememberLazyListState()
            val scope = androidx.compose.runtime.rememberCoroutineScope()
            LaunchedEffect(current) {
                if (current >= 0) scope.launch { listState.animateScrollToItem((current - 2).coerceAtLeast(0)) }
            }
            LazyColumn(
                state = listState,
                modifier = Modifier.weight(1f),
                contentPadding = PaddingValues(horizontal = 24.dp, vertical = 32.dp),
            ) {
                itemsIndexed(synced) { index, line ->
                    val color by animateColorAsState(
                        when {
                            index == current -> LyraColors.TextPrimary
                            index < current -> LyraColors.TextSecondary
                            else -> LyraColors.TextTertiary
                        },
                        label = "línea",
                    )
                    Text(
                        line.text.ifBlank { "♪" },
                        color = color,
                        style = MaterialTheme.typography.headlineLarge,
                        modifier = Modifier
                            .fillMaxWidth()
                            .clip(androidx.compose.foundation.shape.RoundedCornerShape(8.dp))
                            .clickable { player.seekTo(line.timeMs) }
                            .padding(vertical = 8.dp),
                    )
                }
                item { Spacer(Modifier.height(200.dp)) }
            }
        }
        Text(
            "Letra: ${lyrics.source}",
            style = MaterialTheme.typography.labelSmall,
            color = LyraColors.TextTertiary,
            modifier = Modifier.padding(16.dp),
        )
    }
}
