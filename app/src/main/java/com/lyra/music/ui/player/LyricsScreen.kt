package com.lyra.music.ui.player

import android.os.Build
import androidx.activity.compose.BackHandler
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.animateScrollBy
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.snapshotFlow
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.BlurredEdgeTreatment
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.data.source.lyrics.LyricLine
import com.lyra.music.data.source.lyrics.Lyrics
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.PlayPauseIcon
import com.lyra.music.ui.theme.LyraColors
import kotlin.math.abs

/**
 * Letra a pantalla completa, al estilo de Apple Music: la portada difuminada se mueve
 * despacio de fondo, la línea que suena va grande y blanca, las demás más pequeñas,
 * apagadas y algo borrosas, y el texto se desliza suave al cambiar de línea.
 */
@UnstableApi
@Composable
fun LyricsFullScreen(song: Song, lyrics: Lyrics, onClose: () -> Unit) {
    val actions = LocalActions.current
    val player = actions.container.player
    val progress by player.progress.collectAsState()
    val state by player.state.collectAsState()
    BackHandler(onBack = onClose)
    // Mientras se lee la letra, la pantalla no se apaga.
    val view = LocalView.current
    DisposableEffect(view) {
        view.keepScreenOn = true
        onDispose { view.keepScreenOn = false }
    }
    val cover: Any? = actions.container.downloads.localCover(song.id) ?: hiResArtwork(song.thumbnailUrl, 720)

    Box(
        Modifier
            .fillMaxSize()
            .background(LyraColors.Background),
    ) {
        MovingBackdrop(cover)
        Column(
            Modifier
                .fillMaxSize()
                .statusBarsPadding()
                .navigationBarsPadding(),
        ) {
            Row(
                Modifier.padding(start = 20.dp, end = 8.dp, top = 12.dp, bottom = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Artwork(cover, Modifier.size(46.dp), RoundedCornerShape(8.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Text(
                        song.artistsText,
                        style = MaterialTheme.typography.bodySmall,
                        color = LyraColors.TextPrimary.copy(alpha = 0.6f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                IconButton(onClick = onClose) { Icon(Icons.Rounded.KeyboardArrowDown, "Cerrar", modifier = Modifier.size(30.dp)) }
            }

            val synced = lyrics.synced
            if (synced.isNullOrEmpty()) {
                Text(
                    lyrics.plain.orEmpty(),
                    style = lineStyle().copy(fontSize = 22.sp, lineHeight = 32.sp, fontWeight = FontWeight.SemiBold),
                    color = LyraColors.TextPrimary.copy(alpha = 0.85f),
                    modifier = Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .padding(horizontal = 24.dp, vertical = 20.dp),
                )
            } else {
                SyncedLyrics(synced, progress.positionMs, onSeek = { player.seekTo(it) }, modifier = Modifier.weight(1f))
            }

            // Abajo: por dónde va y play/pausa.
            Row(
                Modifier.padding(start = 24.dp, end = 12.dp, top = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
                Box(
                    Modifier
                        .weight(1f)
                        .height(3.dp)
                        .clip(RoundedCornerShape(50))
                        .background(LyraColors.TextPrimary.copy(alpha = 0.2f)),
                ) {
                    Box(
                        Modifier
                            .fillMaxWidth(fraction)
                            .fillMaxHeight()
                            .background(LyraColors.TextPrimary),
                    )
                }
                Spacer(Modifier.width(8.dp))
                IconButton(onClick = player::togglePlay) {
                    PlayPauseIcon(state.isPlaying, LyraColors.TextPrimary, 30.dp)
                }
            }
            Text(
                "Letra: ${lyrics.source}",
                style = MaterialTheme.typography.labelSmall,
                color = LyraColors.TextPrimary.copy(alpha = 0.4f),
                modifier = Modifier.padding(start = 24.dp, bottom = 12.dp),
            )
        }
    }
}

/** Letra del tamaño grande, en la letra de Lyra (Outfit) y en negrita, como en Apple Music. */
@Composable
private fun lineStyle(): TextStyle = TextStyle(
    fontFamily = MaterialTheme.typography.bodyLarge.fontFamily,
    fontWeight = FontWeight.Bold,
    fontSize = 30.sp,
    lineHeight = 38.sp,
)

@Composable
private fun SyncedLyrics(lines: List<LyricLine>, positionMs: Long, onSeek: (Long) -> Unit, modifier: Modifier = Modifier) {
    val current = lines.indexOfLast { it.timeMs <= positionMs + 300 }
    val listState = rememberLazyListState()
    // Si bajas tú con el dedo, la letra deja de seguirse sola unos segundos.
    var userScrollAt by remember { mutableLongStateOf(0L) }
    var autoScrolling by remember { mutableLongStateOf(0L) }
    LaunchedEffect(listState) {
        snapshotFlow { listState.isScrollInProgress }.collect { scrolling ->
            if (scrolling && autoScrolling == 0L) userScrollAt = System.currentTimeMillis()
        }
    }
    LaunchedEffect(current) {
        if (current < 0 || System.currentTimeMillis() - userScrollAt < 3_000) return@LaunchedEffect
        val viewport = listState.layoutInfo.viewportSize.height
        // La línea que suena, a un tercio de la pantalla.
        val anchor = (viewport * 0.3f).toInt()
        autoScrolling = System.currentTimeMillis()
        try {
            val visible = listState.layoutInfo.visibleItemsInfo.firstOrNull { it.index == current }
            if (visible != null) {
                listState.animateScrollBy((visible.offset - anchor).toFloat(), tween(700, easing = FastOutSlowInEasing))
            } else {
                listState.animateScrollToItem(current, -anchor)
            }
        } finally {
            autoScrolling = 0L
        }
    }
    val style = lineStyle()
    val blurs = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(start = 20.dp, end = 20.dp, top = 24.dp, bottom = 320.dp),
    ) {
        itemsIndexed(lines) { index, line ->
            val active = index == current
            val alpha by animateFloatAsState(
                when {
                    active -> 1f
                    index < current -> 0.32f
                    else -> 0.45f
                },
                tween(450),
                label = "brillo",
            )
            val scale by animateFloatAsState(
                if (active) 1f else 0.93f,
                spring(dampingRatio = 0.8f, stiffness = Spring.StiffnessLow),
                label = "tamaño",
            )
            val blur by animateDpAsState(
                if (!blurs || active || current < 0) 0.dp else (abs(index - current).coerceAtMost(4) * 0.9f).dp,
                tween(450),
                label = "desenfoque",
            )
            Text(
                line.text.ifBlank { "• • •" },
                style = style,
                color = LyraColors.TextPrimary,
                modifier = Modifier
                    .fillMaxWidth()
                    .graphicsLayer {
                        this.alpha = alpha
                        scaleX = scale
                        scaleY = scale
                        transformOrigin = TransformOrigin(0f, 0.5f)
                    }
                    .blur(blur, BlurredEdgeTreatment.Unbounded)
                    .clip(RoundedCornerShape(10.dp))
                    .clickable { onSeek(line.timeMs) }
                    .padding(horizontal = 4.dp, vertical = 10.dp),
            )
        }
    }
}

/** Fondo: la portada enorme y muy difuminada, balanceándose y desplazándose despacio. */
@Composable
private fun MovingBackdrop(cover: Any?) {
    val transition = rememberInfiniteTransition(label = "fondo")
    val drift = transition.animateFloat(0f, 1f, infiniteRepeatable(tween(22_000, easing = LinearEasing), RepeatMode.Reverse), label = "deriva")
    // Vaivén de unos grados (girar entera dejaba ver las esquinas de la imagen).
    val turn = transition.animateFloat(-14f, 14f, infiniteRepeatable(tween(17_000, easing = LinearEasing), RepeatMode.Reverse), label = "giro")
    val canBlur = Build.VERSION.SDK_INT >= Build.VERSION_CODES.S
    if (cover != null) {
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.9f
                    scaleY = 1.9f
                    rotationZ = turn.value
                    translationX = (drift.value - 0.5f) * size.width * 0.25f
                }
                .blur(90.dp)
                .alpha(if (canBlur) 0.9f else 0.25f),
        )
        AsyncImage(
            model = cover,
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .fillMaxSize()
                .graphicsLayer {
                    scaleX = 1.7f
                    scaleY = 1.7f
                    rotationZ = -turn.value * 0.7f
                    translationY = (0.5f - drift.value) * size.height * 0.2f
                    alpha = if (canBlur) 0.5f else 0f
                }
                .blur(110.dp),
        )
    }
    // Velo oscuro para que la letra se lea sobre cualquier portada.
    Box(
        Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    0f to LyraColors.Background.copy(alpha = 0.5f),
                    0.5f to LyraColors.Background.copy(alpha = 0.35f),
                    1f to LyraColors.Background.copy(alpha = 0.75f),
                ),
            ),
    )
}
