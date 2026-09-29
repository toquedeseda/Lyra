package com.lyra.music.ui.player

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.components.Artwork
import com.lyra.music.ui.components.artworkFor
import com.lyra.music.ui.theme.LyraColors

@UnstableApi
@Composable
fun MiniPlayer(modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val player = actions.container.player
    val state by player.state.collectAsState()
    val progress by player.progress.collectAsState()
    val liked = LocalLibraryState.current.likedIds
    val song = state.song ?: return

    var dragX by remember { mutableFloatStateOf(0f) }
    val offset by animateFloatAsState(dragX, label = "arrastre")

    Box(
        modifier
            .padding(horizontal = 8.dp)
            .fillMaxWidth()
            .height(58.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(Color(0xFF2E2E2E))
            .clickable { actions.nowPlayingOpen = true }
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
                .padding(horizontal = 8.dp)
                .graphicsLayer { translationX = offset * 0.5f },
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Artwork(artworkFor(song), Modifier.size(42.dp))
            Spacer(Modifier.width(10.dp))
            Column(Modifier.weight(1f)) {
                Text(song.title, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(song.artistsText, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            IconButton(onClick = { actions.toggleLike(song) }) {
                val isLiked = song.id in liked
                Icon(if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, "Me gusta", tint = if (isLiked) Color.White else LyraColors.TextSecondary)
            }
            IconButton(onClick = player::togglePlay) {
                if (state.isBuffering && !state.isPlaying) {
                    CircularProgressIndicator(Modifier.size(22.dp), color = Color.White, strokeWidth = 2.dp)
                } else {
                    Icon(if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, if (state.isPlaying) "Pausa" else "Reproducir", modifier = Modifier.size(30.dp))
                }
            }
        }
        val fraction = if (progress.durationMs > 0) (progress.positionMs.toFloat() / progress.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .align(Alignment.BottomStart)
                .padding(horizontal = 8.dp)
                .fillMaxWidth()
                .height(2.dp)
                .background(Color(0xFF555555)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
    }
}
