package com.lyra.music.ui.player

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.artworkFor
import com.lyra.music.ui.components.pressable
import com.lyra.music.ui.theme.LyraColors

@OptIn(ExperimentalSharedTransitionApi::class)
@UnstableApi
@Composable
fun MiniPlayer(modifier: Modifier = Modifier, sharedScope: SharedTransitionScope? = null) {
    val actions = LocalActions.current
    val player = actions.container.player
    val state by player.state.collectAsState()
    val progress by player.progress.collectAsState()
    val liked = LocalLibraryState.current.likedIds
    val song = state.song ?: return

    var dragX by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(dragX, label = "arrastre")
    val shape = RoundedCornerShape(16.dp)

    Box(
        modifier
            .padding(horizontal = 10.dp, vertical = 6.dp)
            .fillMaxWidth()
            .height(62.dp)
            .shadow(18.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(shape)
            .background(LyraColors.SurfaceHigh.copy(alpha = 0.97f))
            .border(1.dp, LyraColors.Border, shape)
            .pressable(pressedScale = 0.985f) { actions.nowPlayingOpen = true }
            .pointerInput(Unit) {
                // Deslizar a los lados cambia de canción.
                detectHorizontalDragGestures(
                    onDragEnd = {
                        when {
                            dragX < -120 -> player.next()
                            dragX > 120 -> player.previous()
                        }
                        dragX = 0f
                    },
                    onDragCancel = { dragX = 0f },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragX = (dragX + amount).coerceIn(-300f, 300f)
                    },
                )
            },
    ) {
        Row(
            Modifier
                .fillMaxHeight()
                .padding(start = 9.dp, end = 8.dp)
                .graphicsLayer { translationX = offset * 0.5f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            val artModifier = Modifier.size(44.dp)
            if (sharedScope != null) {
                // La carátula "vuela" hasta la pantalla de reproducción.
                with(sharedScope) {
                    AnimatedVisibility(visible = !actions.nowPlayingOpen, enter = fadeIn(), exit = fadeOut()) {
                        Artwork(
                            artworkFor(song),
                            artModifier.sharedElement(
                                rememberSharedContentState(NOW_PLAYING_ART_KEY),
                                animatedVisibilityScope = this,
                                clipInOverlayDuringTransition = OverlayClip(RoundedCornerShape(10.dp)),
                            ),
                            RoundedCornerShape(10.dp),
                        )
                    }
                    if (actions.nowPlayingOpen) Spacer(artModifier)
                }
            } else {
                Artwork(artworkFor(song), artModifier, RoundedCornerShape(10.dp))
            }
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistsText, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            val isLiked = song.id in liked
            IconButton(onClick = { actions.toggleLike(song) }) {
                Icon(
                    if (isLiked) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
                    "Me gusta",
                    tint = if (isLiked) LyraColors.Like else LyraColors.TextSecondary,
                    modifier = Modifier.size(22.dp),
                )
            }
            Box(
                Modifier
                    .size(38.dp)
                    .clip(CircleShape)
                    .background(LyraColors.Accent)
                    .pressable(pressedScale = 0.9f, onClick = player::togglePlay),
                contentAlignment = Alignment.Center,
            ) {
                if (state.isBuffering && !state.isPlaying) {
                    CircularProgressIndicator(Modifier.size(18.dp), color = LyraColors.OnAccent, strokeWidth = 2.dp)
                } else {
                    Icon(
                        if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                        if (state.isPlaying) "Pausa" else "Reproducir",
                        tint = LyraColors.OnAccent,
                        modifier = Modifier.size(24.dp),
                    )
                }
            }
        }
        // Línea de progreso fina, dentro de la píldora.
        val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 14.dp)
                .fillMaxWidth()
                .height(2.dp)
                .clip(RoundedCornerShape(1.dp))
                .background(LyraColors.TextPrimary.copy(alpha = 0.12f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(LyraColors.Accent),
            )
        }
    }
}
