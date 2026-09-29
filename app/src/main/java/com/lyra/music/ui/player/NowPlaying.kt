package com.lyra.music.ui.player

import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Share
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.data.source.lyrics.Lyrics
import com.lyra.music.island.formatTime
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.SongMenuRequest
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.navigation.EqualizerRoute
import com.lyra.music.ui.theme.LyraColors

/** Estado de las letras de la canción que suena. */
sealed interface LyricsState {
    data object Loading : LyricsState
    data object None : LyricsState
    data class Found(val lyrics: Lyrics) : LyricsState
}

@UnstableApi
@Composable
fun rememberLyrics(song: Song?): LyricsState {
    val actions = LocalActions.current
    val settings by actions.container.settings.flow.collectAsState()
    val state by produceState<LyricsState>(LyricsState.Loading, song?.id, settings.showLyrics) {
        value = LyricsState.Loading
        if (song == null || !settings.showLyrics) {
            value = LyricsState.None
            return@produceState
        }
        val found = runCatching { actions.container.lyrics.lyrics(song) }.getOrNull()
        value = if (found != null) LyricsState.Found(found) else LyricsState.None
    }
    return state
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
fun NowPlayingScreen(onClose: () -> Unit) {
    val actions = LocalActions.current
    val player = actions.container.player
    val state by player.state.collectAsState()
    val liked = LocalLibraryState.current.likedIds
    val song = state.song
    val lyrics = rememberLyrics(song)

    BackHandler(onBack = onClose)

    var dragY by remember { mutableFloatStateOf(0f) }
    val offsetY by animateFloatAsState(dragY, label = "cerrar")

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = offsetY.coerceAtLeast(0f) }
            .background(Brush.verticalGradient(listOf(Color(0xFF4A4A4A), Color(0xFF1A1A1A), LyraColors.Background)))
            .pointerInput(Unit) {
                // Deslizar hacia abajo cierra el reproductor.
                detectVerticalDragGestures(
                    onDragEnd = {
                        if (dragY > 220) onClose()
                        dragY = 0f
                    },
                    onDragCancel = { dragY = 0f },
                    onVerticalDrag = { change, amount ->
                        if (dragY + amount >= 0) {
                            change.consume()
                            dragY += amount
                        }
                    },
                )
            },
    ) {
        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Barra superior
            Row(Modifier.padding(horizontal = 4.dp, vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", modifier = Modifier.size(32.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("REPRODUCIENDO DESDE", style = MaterialTheme.typography.labelSmall, color = LyraColors.TextSecondary)
                    Text(
                        state.playingFrom ?: song?.album?.title ?: "Tu cola",
                        style = MaterialTheme.typography.labelLarge,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = { song?.let { actions.songMenu = SongMenuRequest(it) } }) { Icon(Icons.Rounded.MoreVert, "Más") }
            }

            if (song == null) {
                Spacer(Modifier.height(200.dp))
                Text("No suena nada", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center)
                return@Column
            }

            // Carátula: deslizar a los lados cambia de canción.
            var swipe by remember { mutableFloatStateOf(0f) }
            val swipeAnimated by animateFloatAsState(swipe, label = "carátula")
            Box(
                Modifier
                    .padding(horizontal = 24.dp, vertical = 20.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        translationX = swipeAnimated
                        alpha = 1f - (kotlin.math.abs(swipeAnimated) / 900f).coerceIn(0f, 0.5f)
                    }
                    .pointerInput(Unit) {
                        detectHorizontalDragGestures(
                            onDragEnd = {
                                when {
                                    swipe < -160 -> player.next()
                                    swipe > 160 -> player.previous()
                                }
                                swipe = 0f
                            },
                            onDragCancel = { swipe = 0f },
                            onHorizontalDrag = { change, amount ->
                                change.consume()
                                swipe += amount
                            },
                        )
                    },
            ) {
                val cover = actions.container.downloads.localCover(song.id) ?: hiResArtwork(song.thumbnailUrl, 1080)
                Artwork(
                    cover,
                    Modifier
                        .fillMaxSize()
                        .shadow(24.dp, RoundedCornerShape(8.dp)),
                    RoundedCornerShape(8.dp),
                )
            }

            // Título, artista y corazón
            Row(Modifier.padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.headlineSmall,
                        maxLines = 1,
                        modifier = Modifier.basicMarquee(),
                    )
                    Text(
                        song.artistsText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = LyraColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.clickable {
                            song.artists.firstOrNull { it.id != null }?.let {
                                onClose()
                                actions.openArtist(it.id)
                            }
                        },
                    )
                }
                IconButton(onClick = { actions.toggleLike(song) }) {
                    val isLiked = song.id in liked
                    Icon(
                        if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (isLiked) "Quitar de Me gusta" else "Me gusta",
                        tint = if (isLiked) Color.White else LyraColors.TextSecondary,
                        modifier = Modifier.size(28.dp),
                    )
                }
            }

            SeekBar()

            // Controles
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = player::toggleShuffle) {
                    Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = if (state.shuffle) Color.White else LyraColors.TextTertiary)
                }
                IconButton(onClick = player::previous, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipPrevious, "Anterior", modifier = Modifier.size(40.dp))
                }
                Box(
                    Modifier
                        .size(68.dp)
                        .clip(CircleShape)
                        .background(Color.White)
                        .clickable(onClick = player::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering && !state.isPlaying) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = Color.Black, strokeWidth = 3.dp)
                    } else {
                        Icon(
                            if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                            if (state.isPlaying) "Pausa" else "Reproducir",
                            tint = Color.Black,
                            modifier = Modifier.size(40.dp),
                        )
                    }
                }
                IconButton(onClick = player::next, modifier = Modifier.size(56.dp)) {
                    Icon(Icons.Rounded.SkipNext, "Siguiente", modifier = Modifier.size(40.dp))
                }
                IconButton(onClick = player::cycleRepeat) {
                    Icon(
                        if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                        "Repetir",
                        tint = if (state.repeatMode == Player.REPEAT_MODE_OFF) LyraColors.TextTertiary else Color.White,
                    )
                }
            }

            // Fila inferior
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    onClose()
                    actions.nav.navigate(EqualizerRoute)
                }) { Icon(Icons.Rounded.Tune, "Ecualizador", tint = LyraColors.TextSecondary) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.share(song) }) { Icon(Icons.Rounded.Share, "Compartir", tint = LyraColors.TextSecondary) }
                IconButton(onClick = { actions.queueOpen = true }) {
                    Icon(Icons.AutoMirrored.Rounded.QueueMusic, "Cola", tint = LyraColors.TextSecondary)
                }
            }

            state.error?.let { error ->
                Text(
                    error,
                    style = MaterialTheme.typography.bodySmall,
                    color = LyraColors.TextSecondary,
                    textAlign = TextAlign.Center,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp, vertical = 4.dp),
                )
            }

            LyricsCard(lyrics, onOpen = { actions.lyricsOpen = true })
            Spacer(Modifier.height(24.dp))
        }
    }

    if (actions.queueOpen) QueueSheet(onDismiss = { actions.queueOpen = false })
    if (actions.lyricsOpen && lyrics is LyricsState.Found && song != null) {
        LyricsFullScreen(song, lyrics.lyrics, onClose = { actions.lyricsOpen = false })
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@UnstableApi
@Composable
private fun SeekBar() {
    val player = LocalActions.current.container.player
    val progress by player.progress.collectAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val duration = progress.durationMs.coerceAtLeast(1)
    val value = if (dragging) dragValue else (progress.positionMs.toFloat() / duration).coerceIn(0f, 1f)

    Column(Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) {
        Slider(
            value = value,
            onValueChange = {
                dragging = true
                dragValue = it
            },
            onValueChangeFinished = {
                player.seekTo((dragValue * duration).toLong())
                dragging = false
            },
            colors = SliderDefaults.colors(
                thumbColor = Color.White,
                activeTrackColor = Color.White,
                inactiveTrackColor = Color(0xFF4D4D4D),
            ),
            thumb = {
                Box(
                    Modifier
                        .size(if (dragging) 16.dp else 12.dp)
                        .clip(CircleShape)
                        .background(Color.White),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(activeTrackColor = Color.White, inactiveTrackColor = Color(0xFF4D4D4D)),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(formatTime(if (dragging) (dragValue * duration).toLong() else progress.positionMs), style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            Text(formatTime(progress.durationMs), style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
        }
    }
}

@UnstableApi
@Composable
private fun LyricsCard(state: LyricsState, onOpen: () -> Unit) {
    val progress by LocalActions.current.container.player.progress.collectAsState()
    Column(
        Modifier
            .padding(horizontal = 16.dp, vertical = 12.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .background(Color(0xFF2B2B2B))
            .clickable(enabled = state is LyricsState.Found, onClick = onOpen)
            .padding(16.dp),
    ) {
        Text("Letra", style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(10.dp))
        when (state) {
            LyricsState.Loading -> Text("Buscando la letra…", color = LyraColors.TextSecondary)
            LyricsState.None -> Text("No hay letra para esta canción", color = LyraColors.TextSecondary)
            is LyricsState.Found -> {
                val synced = state.lyrics.synced
                if (synced != null) {
                    val current = synced.indexOfLast { it.timeMs <= progress.positionMs + 300 }.coerceAtLeast(0)
                    synced.subList(current, minOf(current + 4, synced.size)).forEachIndexed { index, line ->
                        Text(
                            line.text.ifBlank { "♪" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = if (index == 0) Color.White else Color(0xFF7D7D7D),
                            modifier = Modifier.padding(vertical = 3.dp),
                        )
                    }
                } else {
                    Text(
                        state.lyrics.plain.orEmpty(),
                        style = MaterialTheme.typography.titleMedium,
                        maxLines = 6,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                Text(
                    "Toca para verla entera · ${state.lyrics.source}",
                    style = MaterialTheme.typography.labelSmall,
                    color = LyraColors.TextTertiary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
