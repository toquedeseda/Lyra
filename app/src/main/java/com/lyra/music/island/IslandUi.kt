package com.lyra.music.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lyra.music.data.source.innertube.hiResArtwork

@Composable
fun IslandContent(controller: IslandController, geometry: IslandGeometry) {
    val state by controller.state.collectAsState()
    val expanded by controller.expanded.collectAsState()

    AnimatedContent(
        targetState = expanded,
        transitionSpec = {
            fadeIn(tween(180)) togetherWith fadeOut(tween(120)) using
                SizeTransform(clip = true) { _, _ -> spring(dampingRatio = 0.78f, stiffness = Spring.StiffnessMediumLow) }
        },
        label = "isla",
    ) { isExpanded ->
        if (isExpanded) {
            ExpandedIsland(state, controller)
        } else {
            CollapsedIsland(state, geometry, onTap = controller::expand, onLongPress = controller::openApp)
        }
    }
}

@Composable
private fun CollapsedIsland(state: IslandState, geometry: IslandGeometry, onTap: () -> Unit, onLongPress: () -> Unit) {
    Row(
        modifier = Modifier
            .width(geometry.widthDp.dp)
            .height(geometry.heightDp.dp)
            .clip(RoundedCornerShape(50))
            .background(Color.Black)
            .pointerInput(Unit) { detectTapGestures(onTap = { onTap() }, onLongPress = { onLongPress() }) }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AsyncImage(
            model = hiResArtwork(state.song?.thumbnailUrl, 120),
            contentDescription = null,
            contentScale = ContentScale.Crop,
            modifier = Modifier
                .size((geometry.heightDp - 12).coerceAtLeast(16).dp)
                .clip(CircleShape)
                .background(Color(0xFF2A2A2A)),
        )
        // El centro queda libre para la cámara.
        Spacer(Modifier.weight(1f))
        EqualizerBars(playing = state.isPlaying, modifier = Modifier.height((geometry.heightDp / 2).dp).width(14.dp))
        Spacer(Modifier.width(4.dp))
    }
}

@Composable
private fun ExpandedIsland(state: IslandState, controller: IslandController) {
    Column(
        modifier = Modifier
            .width(340.dp)
            .clip(RoundedCornerShape(30.dp))
            .background(Color.Black)
            .padding(16.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            AsyncImage(
                model = hiResArtwork(state.song?.thumbnailUrl, 240),
                contentDescription = "Abrir Lyra",
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .size(54.dp)
                    .clip(RoundedCornerShape(12.dp))
                    .background(Color(0xFF2A2A2A))
                    .pointerInput(Unit) { detectTapGestures { controller.openApp() } },
            )
            Column(
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 12.dp),
            ) {
                Text(
                    state.song?.title.orEmpty(),
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    state.song?.artistsText.orEmpty(),
                    color = Color(0xFFB3B3B3),
                    fontSize = 13.sp,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            EqualizerBars(playing = state.isPlaying, modifier = Modifier.height(18.dp).width(18.dp))
        }
        Spacer(Modifier.height(14.dp))
        val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(Color(0xFF3A3A3A)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(Color.White),
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(state.positionMs), color = Color(0xFF8A8A8A), fontSize = 11.sp)
            Text("-" + formatTime((state.durationMs - state.positionMs).coerceAtLeast(0)), color = Color(0xFF8A8A8A), fontSize = 11.sp)
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IconButton(onClick = controller::previous) {
                Icon(Icons.Rounded.SkipPrevious, "Anterior", tint = Color.White, modifier = Modifier.size(32.dp))
            }
            Spacer(Modifier.width(18.dp))
            IconButton(
                onClick = controller::togglePlay,
                modifier = Modifier
                    .size(52.dp)
                    .clip(CircleShape)
                    .background(Color.White),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.isPlaying) "Pausa" else "Reproducir",
                    tint = Color.Black,
                    modifier = Modifier.size(32.dp),
                )
            }
            Spacer(Modifier.width(18.dp))
            IconButton(onClick = controller::next) {
                Icon(Icons.Rounded.SkipNext, "Siguiente", tint = Color.White, modifier = Modifier.size(32.dp))
            }
        }
    }
}

/** Tres barras que bailan mientras suena la música. */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = Color.White) {
    val transition = rememberInfiniteTransition(label = "barras")
    val durations = listOf(520, 380, 610)
    val heights = durations.mapIndexed { index, duration ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(duration), RepeatMode.Reverse, initialStartOffset = androidx.compose.animation.core.StartOffset(index * 140)),
            label = "barra$index",
        )
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.dp), verticalAlignment = Alignment.Bottom) {
        heights.forEach { height ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight(if (playing) height.value else 0.25f)
                    .clip(RoundedCornerShape(1.dp))
                    .background(color),
            )
        }
    }
}

fun formatTime(ms: Long): String {
    val totalSeconds = (ms / 1000).coerceAtLeast(0)
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}
