package com.lyra.music.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.basicMarquee
import androidx.compose.foundation.border
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
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.QueueMusic
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.outlined.Share
import androidx.compose.material.icons.outlined.Tune
import androidx.compose.material.icons.rounded.AutoAwesome
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
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
import com.lyra.music.ui.components.bounceOnChange
import com.lyra.music.ui.components.PlayPauseIcon
import com.lyra.music.ui.components.NudgeButton
import com.lyra.music.ui.components.LikeButton
import androidx.compose.animation.togetherWith
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.data.source.lyrics.Lyrics
import com.lyra.music.island.formatTime
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.SongMenuRequest
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.Eyebrow
import com.lyra.music.ui.components.pressable
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

/** Clave común de la carátula que "vuela" del mini reproductor a esta pantalla. */
const val NOW_PLAYING_ART_KEY = "now-playing-art"

@OptIn(ExperimentalMaterial3Api::class, ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
fun NowPlayingScreen(
    onClose: () -> Unit,
    sharedScope: SharedTransitionScope? = null,
    visibilityScope: AnimatedVisibilityScope? = null,
) {
    val actions = LocalActions.current
    val player = actions.container.player
    val state by player.state.collectAsState()
    val settings by actions.container.settings.flow.collectAsState()
    val liked = LocalLibraryState.current.likedIds
    val song = state.song
    val lyrics = rememberLyrics(song)
    val motion = rememberMusicMotion(state.isPlaying, settings.visualizer)

    BackHandler(onBack = onClose)

    var dragY by remember { mutableFloatStateOf(0f) }
    val offsetY by animateFloatAsState(dragY, label = "cerrar")
    val cover: Any? = song?.let { actions.container.downloads.localCover(it.id) ?: hiResArtwork(it.thumbnailUrl, 1080) }

    Box(
        Modifier
            .fillMaxSize()
            .graphicsLayer { translationY = offsetY.coerceAtLeast(0f) }
            .background(LyraColors.Background)
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
        // Fondo: la portada muy difuminada, para que cada canción tenga su ambiente.
        if (cover != null) {
            AsyncImage(
                model = cover,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .fillMaxSize()
                    .graphicsLayer { scaleX = 1.3f; scaleY = 1.3f }
                    .blur(110.dp)
                    .alpha(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) 0.85f else 0.25f),
            )
        }
        Box(
            Modifier
                .fillMaxSize()
                .background(
                    Brush.verticalGradient(
                        0f to LyraColors.Background.copy(alpha = 0.30f),
                        0.45f to LyraColors.Background.copy(alpha = 0.55f),
                        0.8f to LyraColors.Background.copy(alpha = 0.88f),
                        1f to LyraColors.Background,
                    ),
                ),
        )

        Column(
            Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            // Barra superior
            Row(Modifier.padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", modifier = Modifier.size(30.dp)) }
                Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
                    Eyebrow(
                        when {
                            song != null && song.id in state.recommendedIds -> "Recomendada · aleatorio inteligente"
                            song != null && song.id in state.radioIds && state.contextId?.startsWith("radio:") != true -> "Radio · después de tu lista"
                            else -> "Reproduciendo desde"
                        },
                    )
                    Text(
                        state.playingFrom ?: song?.album?.title ?: "Tu cola",
                        style = MaterialTheme.typography.titleSmall,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
                IconButton(onClick = { song?.let { actions.songMenu = SongMenuRequest(it) } }) { Icon(Icons.Outlined.MoreVert, "Más") }
            }

            if (song == null) {
                Spacer(Modifier.height(200.dp))
                Text("No suena nada", modifier = Modifier.fillMaxWidth(), textAlign = TextAlign.Center, color = LyraColors.TextSecondary)
                return@Column
            }

            // Carátula: deslizar a los lados cambia de canción.
            var swipe by remember { mutableFloatStateOf(0f) }
            val swipeAnimated by animateFloatAsState(swipe, label = "carátula")
            val artShape = RoundedCornerShape(16.dp)
            val sharedArt = if (sharedScope != null && visibilityScope != null) {
                with(sharedScope) {
                    Modifier.sharedElement(
                        rememberSharedContentState(NOW_PLAYING_ART_KEY),
                        animatedVisibilityScope = visibilityScope,
                        clipInOverlayDuringTransition = OverlayClip(artShape),
                    )
                }
            } else {
                Modifier
            }
            Box(
                Modifier
                    .padding(start = 28.dp, end = 28.dp, top = 22.dp, bottom = if (settings.visualizer) 12.dp else 22.dp)
                    .fillMaxWidth()
                    .aspectRatio(1f)
                    .graphicsLayer {
                        translationX = swipeAnimated
                        rotationZ = swipeAnimated / 120f
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
                Artwork(
                    cover,
                    sharedArt
                        .fillMaxSize()
                        .graphicsLayer {
                            // Pulso con el bajo (solo si están puestas las barritas).
                            val scale = motion.coverScale()
                            scaleX = scale
                            scaleY = scale
                        }
                        .shadow(32.dp, artShape, ambientColor = Color.Black, spotColor = Color.Black),
                    artShape,
                )
            }
            if (settings.visualizer) {
                MusicBars(
                    motion,
                    state.isPlaying,
                    Modifier
                        .padding(horizontal = 28.dp)
                        .fillMaxWidth()
                        .height(26.dp),
                )
                Spacer(Modifier.height(12.dp))
            }

            // Título en serif, artista y corazón
            Row(Modifier.padding(horizontal = 28.dp), verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    AnimatedContent(
                        targetState = song.title,
                        transitionSpec = {
                            (slideInVertically { it / 3 } + fadeIn(tween(260))) togetherWith fadeOut(tween(120))
                        },
                        label = "título",
                    ) { title ->
                        Text(
                            title,
                            style = MaterialTheme.typography.headlineLarge,
                            maxLines = 1,
                            modifier = Modifier.basicMarquee(),
                        )
                    }
                    Text(
                        song.artistsText,
                        style = MaterialTheme.typography.bodyLarge,
                        color = LyraColors.TextSecondary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier
                            .padding(top = 4.dp)
                            .clickable {
                                song.artists.firstOrNull { it.id != null }?.let {
                                    onClose()
                                    actions.openArtist(it.id)
                                }
                            },
                    )
                    if (state.waitingForNetwork) {
                        Text(
                            "Sin cobertura · seguirá sola en cuanto vuelva la señal",
                            style = MaterialTheme.typography.labelMedium,
                            color = LyraColors.Accent,
                            modifier = Modifier.padding(top = 6.dp),
                        )
                    }
                }
                LikeButton(song.id in liked, onClick = { actions.toggleLike(song) }, size = 28.dp, inactiveTint = LyraColors.TextPrimary)
            }

            SeekBar()

            // Controles
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ShuffleControl(state.shuffle, settings.smartShuffle, actions::cycleShuffle)
                NudgeButton(Icons.Rounded.SkipPrevious, "Anterior", direction = -1, size = 38.dp, onClick = player::previous)
                Box(
                    Modifier
                        .size(74.dp)
                        .shadow(16.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
                        .clip(CircleShape)
                        .background(LyraColors.Accent)
                        .pressable(pressedScale = 0.92f, onClick = player::togglePlay),
                    contentAlignment = Alignment.Center,
                ) {
                    if (state.isBuffering && !state.isPlaying) {
                        CircularProgressIndicator(Modifier.size(30.dp), color = LyraColors.OnAccent, strokeWidth = 3.dp)
                    } else {
                        PlayPauseIcon(state.isPlaying, LyraColors.OnAccent, 40.dp)
                    }
                }
                NudgeButton(Icons.Rounded.SkipNext, "Siguiente", direction = 1, size = 38.dp, onClick = player::next)
                ToggleControl(
                    if (state.repeatMode == Player.REPEAT_MODE_ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    "Repetir",
                    state.repeatMode != Player.REPEAT_MODE_OFF,
                    player::cycleRepeat,
                )
            }

            // Fila inferior
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 20.dp, vertical = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(onClick = {
                    onClose()
                    actions.nav.navigate(EqualizerRoute)
                }) { Icon(Icons.Outlined.Tune, "Ecualizador", tint = LyraColors.TextSecondary) }
                Spacer(Modifier.weight(1f))
                IconButton(onClick = { actions.shareCard = song }) { Icon(Icons.Outlined.Share, "Compartir", tint = LyraColors.TextSecondary) }
                IconButton(onClick = { actions.queueOpen = true }) {
                    Icon(Icons.AutoMirrored.Outlined.QueueMusic, "Cola", tint = LyraColors.TextSecondary)
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
                        .padding(horizontal = 28.dp, vertical = 4.dp),
                )
            }

            LyricsCard(lyrics, onOpen = { actions.lyricsOpen = true })
            Spacer(Modifier.height(28.dp))
        }
    }

    if (actions.queueOpen) QueueSheet(onDismiss = { actions.queueOpen = false })
    if (actions.lyricsOpen && lyrics is LyricsState.Found && song != null) {
        LyricsFullScreen(song, lyrics.lyrics, onClose = { actions.lyricsOpen = false })
    }
}

/** Aleatorio / repetir: hueso con un puntito debajo cuando están activos. */
@Composable
private fun ShuffleControl(shuffle: Boolean, smart: Boolean, onClick: () -> Unit) {
    val isSmart = shuffle && smart
    val tint by animateColorAsState(if (shuffle) LyraColors.Accent else LyraColors.TextTertiary, label = "aleatorio")
    val dot by animateColorAsState(if (shuffle) LyraColors.Accent else Color.Transparent, label = "punto")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Box(Modifier.bounceOnChange(shuffle to smart)) {
                Icon(
                    Icons.Rounded.Shuffle,
                    if (isSmart) "Aleatorio inteligente" else "Aleatorio",
                    tint = tint,
                )
                if (isSmart) {
                    Icon(
                        Icons.Rounded.AutoAwesome,
                        null,
                        tint = LyraColors.Accent,
                        modifier = Modifier
                            .align(Alignment.TopEnd)
                            .offset(x = 7.dp, y = (-6).dp)
                            .size(12.dp),
                    )
                }
            }
        }
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(dot),
        )
    }
}

@Composable
private fun ToggleControl(icon: ImageVector, description: String, active: Boolean, onClick: () -> Unit) {
    val tint by animateColorAsState(if (active) LyraColors.Accent else LyraColors.TextTertiary, label = "conmutador")
    val dot by animateColorAsState(if (active) LyraColors.Accent else Color.Transparent, label = "punto")
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        IconButton(onClick = onClick) {
            Icon(icon, description, tint = tint, modifier = Modifier.bounceOnChange(icon to active))
        }
        Box(
            Modifier
                .size(4.dp)
                .clip(CircleShape)
                .background(dot),
        )
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
    val inactive = LyraColors.TextPrimary.copy(alpha = 0.16f)

    Column(Modifier.padding(horizontal = 20.dp, vertical = 10.dp)) {
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
            colors = SliderDefaults.colors(thumbColor = LyraColors.Accent, activeTrackColor = LyraColors.Accent, inactiveTrackColor = inactive),
            thumb = {
                Box(
                    Modifier
                        .size(if (dragging) 16.dp else 12.dp)
                        .clip(CircleShape)
                        .background(LyraColors.Accent),
                )
            },
            track = { sliderState ->
                SliderDefaults.Track(
                    sliderState = sliderState,
                    modifier = Modifier.height(4.dp),
                    colors = SliderDefaults.colors(activeTrackColor = LyraColors.Accent, inactiveTrackColor = inactive),
                    thumbTrackGapSize = 0.dp,
                    drawStopIndicator = null,
                )
            },
        )
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp)) {
            Text(formatTime(if (dragging) (dragValue * duration).toLong() else progress.positionMs), style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary)
            Spacer(Modifier.weight(1f))
            Text(formatTime(progress.durationMs), style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary)
        }
    }
}

@UnstableApi
@Composable
private fun LyricsCard(state: LyricsState, onOpen: () -> Unit) {
    val progress by LocalActions.current.container.player.progress.collectAsState()
    Column(
        Modifier
            .padding(horizontal = 20.dp, vertical = 14.dp)
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LyraColors.TextPrimary.copy(alpha = 0.07f))
            .border(1.dp, LyraColors.Border, RoundedCornerShape(16.dp))
            .clickable(enabled = state is LyricsState.Found, onClick = onOpen)
            .padding(18.dp),
    ) {
        Eyebrow("Letra")
        Spacer(Modifier.height(12.dp))
        when (state) {
            LyricsState.Loading -> Text("Buscando la letra…", color = LyraColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            LyricsState.None -> Text("No hay letra para esta canción", color = LyraColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
            is LyricsState.Found -> {
                val synced = state.lyrics.synced
                if (synced != null) {
                    val current = synced.indexOfLast { it.timeMs <= progress.positionMs + 300 }.coerceAtLeast(0)
                    synced.subList(current, minOf(current + 4, synced.size)).forEachIndexed { index, line ->
                        Text(
                            line.text.ifBlank { "♪" },
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = if (index == 0) LyraColors.TextPrimary else LyraColors.TextTertiary,
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
                    style = MaterialTheme.typography.labelMedium,
                    color = LyraColors.TextTertiary,
                    modifier = Modifier.padding(top = 12.dp),
                )
            }
        }
    }
}
