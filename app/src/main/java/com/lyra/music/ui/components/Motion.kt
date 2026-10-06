package com.lyra.music.ui.components

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.FavoriteBorder
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.Icon
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Pequeñas animaciones de la interfaz: corazón que late al dar "Me gusta",
 * play/pausa que se transforman, rebotes al cambiar de estado y empujoncitos
 * en los botones de saltar.
 */

/** Rebote breve cada vez que [key] cambia (no al aparecer). Con [onlyWhen], solo si vale true. */
fun Modifier.bounceOnChange(key: Any?, onlyWhen: Boolean = true, peak: Float = 1.22f): Modifier = composed {
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(key) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (!onlyWhen) return@LaunchedEffect
        scale.animateTo(peak, tween(110, easing = FastOutSlowInEasing))
        scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessMediumLow))
    }
    graphicsLayer {
        scaleX = scale.value
        scaleY = scale.value
    }
}

/** Corazón de "Me gusta": al marcarlo late y suelta unas chispas; al quitarlo se encoge un poco. */
@Composable
fun LikeButton(
    liked: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 24.dp,
    inactiveTint: Color = LyraColors.TextSecondary,
) {
    val scale = remember { Animatable(1f) }
    val burst = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    val tint by animateColorAsState(if (liked) LyraColors.Like else inactiveTint, tween(220), label = "corazón")
    LaunchedEffect(liked) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (liked) {
            launch {
                burst.snapTo(0f)
                burst.animateTo(1f, tween(520, easing = FastOutSlowInEasing))
            }
            scale.snapTo(0.55f)
            scale.animateTo(1f, spring(dampingRatio = 0.32f, stiffness = Spring.StiffnessMedium))
        } else {
            scale.animateTo(0.78f, tween(90))
            scale.animateTo(1f, spring(dampingRatio = 0.5f))
        }
    }
    Box(
        modifier
            .size(size * 2)
            .pressable(pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        // Chispas: ocho puntitos que salen del corazón y se apagan.
        if (burst.value < 1f) {
            Canvas(Modifier.size(size * 2)) {
                val progress = burst.value
                val center = Offset(this.size.width / 2, this.size.height / 2)
                val radius = this.size.minDimension * (0.22f + 0.30f * progress)
                for (i in 0 until 8) {
                    val angle = (i / 8f) * 2 * PI.toFloat() - PI.toFloat() / 2
                    val point = Offset(center.x + cos(angle) * radius, center.y + sin(angle) * radius)
                    drawCircle(
                        color = LyraColors.Like.copy(alpha = (1f - progress).coerceIn(0f, 1f)),
                        radius = this.size.minDimension * 0.045f * (1f - progress * 0.6f),
                        center = point,
                    )
                }
            }
        }
        Icon(
            if (liked) Icons.Rounded.Favorite else Icons.Outlined.FavoriteBorder,
            if (liked) "Quitar de Me gusta" else "Me gusta",
            tint = tint,
            modifier = Modifier
                .size(size)
                .graphicsLayer {
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
    }
}

/** Play y pausa que se transforman el uno en el otro (giro, escala y fundido). */
@Composable
fun PlayPauseIcon(isPlaying: Boolean, tint: Color, size: Dp, modifier: Modifier = Modifier) {
    AnimatedContent(
        targetState = isPlaying,
        transitionSpec = {
            (scaleIn(initialScale = 0.55f, animationSpec = spring(dampingRatio = 0.55f)) + fadeIn(tween(140))) togetherWith
                (scaleOut(targetScale = 0.55f, animationSpec = tween(120)) + fadeOut(tween(100)))
        },
        contentAlignment = Alignment.Center,
        modifier = modifier,
        label = "play/pausa",
    ) { playing ->
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pausa" else "Reproducir",
            tint = tint,
            modifier = Modifier.size(size),
        )
    }
}

/** Botón de anterior/siguiente que da un empujoncito hacia donde va. */
@Composable
fun NudgeButton(icon: ImageVector, description: String, direction: Int, size: Dp, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val offset = remember { Animatable(0f) }
    val scope = rememberCoroutineScope()
    Box(
        modifier
            .size(size + 18.dp)
            .pressable(pressedScale = 0.88f) {
                onClick()
                scope.launch {
                    offset.animateTo(direction * 10f, tween(90))
                    offset.animateTo(0f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMediumLow))
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            description,
            modifier = Modifier
                .size(size)
                .graphicsLayer { translationX = offset.value * density },
        )
    }
}

/**
 * Aleatorio de las cabeceras de listas: al encenderse "baraja" (vaivén), crece un poco
 * y le sale un puntito debajo, como en Spotify; al apagarse se encoge y el punto se va.
 */
@Composable
fun ShuffleToggle(active: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val tint by animateColorAsState(if (active) LyraColors.Accent else LyraColors.TextSecondary, tween(220), label = "aleatorio")
    val dot by animateFloatAsState(
        if (active) 1f else 0f,
        spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "punto",
    )
    val wiggle = remember { Animatable(1f) }
    val scale = remember { Animatable(1f) }
    val first = remember { booleanArrayOf(true) }
    LaunchedEffect(active) {
        if (first[0]) {
            first[0] = false
            return@LaunchedEffect
        }
        if (active) {
            launch {
                wiggle.snapTo(0f)
                wiggle.animateTo(1f, tween(520, easing = LinearEasing))
            }
            scale.animateTo(1.28f, tween(120, easing = FastOutSlowInEasing))
        } else {
            scale.animateTo(0.82f, tween(100))
        }
        scale.animateTo(1f, spring(dampingRatio = 0.38f, stiffness = Spring.StiffnessMediumLow))
    }
    Box(
        modifier
            .size(48.dp)
            .pressable(pressedScale = 0.85f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            Icons.Rounded.Shuffle,
            if (active) "Quitar aleatorio" else "Aleatorio",
            tint = tint,
            modifier = Modifier
                .size(26.dp)
                .graphicsLayer {
                    // Vaivén que se apaga: las flechas se mueven a un lado y a otro un par de veces.
                    val t = wiggle.value
                    rotationZ = sin(t * 4f * PI.toFloat()) * 16f * (1f - t)
                    scaleX = scale.value
                    scaleY = scale.value
                },
        )
        Box(
            Modifier
                .align(Alignment.BottomCenter)
                .padding(bottom = 5.dp)
                .size(4.dp)
                .graphicsLayer {
                    scaleX = dot
                    scaleY = dot
                    alpha = dot.coerceIn(0f, 1f)
                }
                .clip(CircleShape)
                .background(LyraColors.Accent),
        )
    }
}
