package com.lyra.music.island

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.SizeTransform
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.StartOffset
import androidx.compose.animation.core.animateDpAsState
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.snapshotFlow
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.max
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.playback.AudioLevels
import com.lyra.music.ui.theme.LyraColors
import com.lyra.music.ui.theme.Outfit
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.isActive
import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sin

private val IslandBlack = Color.Black
private val SmoothOut = CubicBezierEasing(0.2f, 0.9f, 0.25f, 1f)

private fun text(size: Float, weight: FontWeight = FontWeight.Normal, color: Color = LyraColors.TextPrimary, spacing: Float = 0f) =
    TextStyle(fontFamily = Outfit, fontSize = size.sp, fontWeight = weight, color = color, letterSpacing = spacing.sp)

@Composable
fun IslandContent(controller: IslandController, geometry: IslandGeometry, onWindowSize: (DpSize) -> Unit) {
    val state by controller.state.collectAsState()
    val shape by controller.shape.collectAsState()

    val pill = DpSize(geometry.widthDp.dp, geometry.heightDp.dp)
    val notice = DpSize(max(IslandMetrics.NOTICE_WIDTH.dp, pill.width), max(IslandMetrics.NOTICE_HEIGHT.dp, pill.height))
    val expanded = DpSize(IslandMetrics.EXPANDED_WIDTH.dp, IslandMetrics.EXPANDED_HEIGHT.dp)
    val target = when (shape) {
        IslandShape.Pill -> pill
        is IslandShape.Notice -> notice
        IslandShape.Expanded -> expanded
    }

    // Muelle con un poco de rebote: la píldora "se estira" hasta su nueva forma.
    val morph = spring<Dp>(dampingRatio = 0.72f, stiffness = 340f)
    val width by animateDpAsState(target.width, morph, label = "ancho")
    val height by animateDpAsState(target.height, morph, label = "alto")
    val corner by animateDpAsState(
        when (shape) {
            IslandShape.Pill -> pill.height / 2
            is IslandShape.Notice -> notice.height / 2
            IslandShape.Expanded -> 42.dp
        },
        morph,
        label = "esquinas",
    )

    // La ventana crece de una vez antes de animar y encoge de una vez al terminar.
    LaunchedEffect(target) {
        onWindowSize(DpSize(max(width, target.width) + 8.dp, max(height, target.height) + 8.dp))
        snapshotFlow { abs(width.value - target.width.value) < 0.6f && abs(height.value - target.height.value) < 0.6f }
            .first { it }
        onWindowSize(target)
    }

    // Pequeño rebote al cambiar de canción.
    val bounce = remember { Animatable(1f) }
    LaunchedEffect(state.song?.id) {
        if (controller.shape.value == IslandShape.Pill && state.song != null) {
            bounce.animateTo(1.09f, spring(dampingRatio = 0.5f, stiffness = 900f))
            bounce.animateTo(1f, spring(dampingRatio = 0.42f, stiffness = 360f))
        }
    }

    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.TopCenter) {
        Box(
            Modifier
                .size(width, height)
                .graphicsLayer {
                    scaleX = bounce.value
                    scaleY = bounce.value
                }
                .clip(RoundedCornerShape(corner))
                .background(IslandBlack),
            contentAlignment = Alignment.Center,
        ) {
            AnimatedContent(
                targetState = shape,
                contentKey = { it::class },
                transitionSpec = {
                    (fadeIn(tween(220, delayMillis = 110)) + scaleIn(tween(360, delayMillis = 60, easing = SmoothOut), initialScale = 0.9f)) togetherWith
                        fadeOut(tween(90)) using SizeTransform(clip = false) { _, _ -> snap() }
                },
                contentAlignment = Alignment.Center,
                label = "contenido",
            ) { current ->
                when (current) {
                    IslandShape.Pill -> PillContent(state, pill, controller)
                    is IslandShape.Notice -> NoticeContent(current.notice, state, notice, controller)
                    IslandShape.Expanded -> ExpandedContent(state, expanded, controller)
                }
            }
        }
    }
}

@Composable
private fun PillContent(state: IslandState, size: DpSize, controller: IslandController) {
    var dragX = 0f
    Row(
        Modifier
            .requiredSize(size)
            .pointerInput(Unit) {
                detectTapGestures(onTap = { controller.expand() }, onLongPress = { controller.openApp() })
            }
            .pointerInput(Unit) {
                // Deslizar la píldora cambia de canción.
                detectHorizontalDragGestures(
                    onDragStart = { dragX = 0f },
                    onDragEnd = {
                        when {
                            dragX < -60 -> controller.next()
                            dragX > 60 -> controller.previous()
                        }
                    },
                    onHorizontalDrag = { change, amount ->
                        change.consume()
                        dragX += amount
                    },
                )
            }
            .padding(horizontal = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IslandArt(state.song?.thumbnailUrl, Modifier.size((size.height - 12.dp).coerceAtLeast(16.dp)), CircleShape)
        // El centro queda libre para la cámara.
        Spacer(Modifier.weight(1f))
        ReactiveBars(
            playing = state.isPlaying,
            modifier = Modifier
                .padding(end = 5.dp)
                .width(17.dp)
                .height(size.height * 0.46f),
        )
    }
}

@Composable
private fun NoticeContent(notice: IslandNotice, state: IslandState, size: DpSize, controller: IslandController) {
    Row(
        Modifier
            .requiredSize(size)
            .pointerInput(Unit) { detectTapGestures(onTap = { controller.expand() }, onLongPress = { controller.openApp() }) }
            .padding(start = 11.dp, end = 18.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when {
            notice.artwork != null -> IslandArt(notice.artwork, Modifier.size(size.height - 20.dp), RoundedCornerShape(10.dp))
            else -> Box(
                Modifier
                    .size(size.height - 20.dp)
                    .clip(CircleShape)
                    .background(LyraColors.SurfaceHigh),
                contentAlignment = Alignment.Center,
            ) { Icon(Icons.Rounded.Check, null, tint = LyraColors.Accent, modifier = Modifier.size(20.dp)) }
        }
        Column(
            Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
        ) {
            Text(notice.label.uppercase(), style = text(10.5f, FontWeight.SemiBold, LyraColors.TextSecondary, 1.2f), maxLines = 1)
            Text(notice.title, style = text(14f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        when (notice.kind) {
            IslandNotice.Kind.NOW_PLAYING -> ReactiveBars(state.isPlaying, Modifier.width(18.dp).height(20.dp))
            IslandNotice.Kind.DOWNLOAD -> Icon(Icons.Rounded.Check, "Descargada", tint = LyraColors.Accent, modifier = Modifier.size(20.dp))
            IslandNotice.Kind.LIKED -> Icon(Icons.Rounded.Favorite, "Me gusta", tint = LyraColors.Like, modifier = Modifier.size(20.dp))
        }
    }
}

@Composable
private fun ExpandedContent(state: IslandState, size: DpSize, controller: IslandController) {
    var dragY = 0f
    Column(
        Modifier
            .requiredSize(size)
            .pointerInput(Unit) {
                // Deslizar hacia arriba la recoge.
                detectVerticalDragGestures(
                    onDragStart = { dragY = 0f },
                    onDragEnd = { if (dragY < -40) controller.collapse() },
                    onVerticalDrag = { change, amount ->
                        change.consume()
                        dragY += amount
                    },
                )
            }
            .padding(start = 20.dp, end = 20.dp, top = 18.dp, bottom = 12.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IslandArt(
                hiResArtwork(state.song?.thumbnailUrl, 240),
                Modifier
                    .size(58.dp)
                    .pointerInput(Unit) { detectTapGestures { controller.openApp() } },
                RoundedCornerShape(14.dp),
            )
            Column(
                Modifier
                    .weight(1f)
                    .padding(horizontal = 14.dp),
            ) {
                Text(state.song?.title.orEmpty(), style = text(16f, FontWeight.SemiBold), maxLines = 1, overflow = TextOverflow.Ellipsis)
                Text(state.song?.artistsText.orEmpty(), style = text(13f, color = LyraColors.TextSecondary), maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
            ReactiveBars(state.isPlaying, Modifier.width(22.dp).height(22.dp))
        }
        Spacer(Modifier.height(16.dp))
        val fraction = if (state.durationMs > 0) (state.positionMs.toFloat() / state.durationMs).coerceIn(0f, 1f) else 0f
        Box(
            Modifier
                .fillMaxWidth()
                .height(4.dp)
                .clip(RoundedCornerShape(2.dp))
                .background(LyraColors.TextPrimary.copy(alpha = 0.16f)),
        ) {
            Box(
                Modifier
                    .fillMaxWidth(fraction)
                    .fillMaxHeight()
                    .background(LyraColors.Accent),
            )
        }
        Row(
            Modifier
                .fillMaxWidth()
                .padding(top = 6.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
        ) {
            Text(formatTime(state.positionMs), style = text(11f, color = LyraColors.TextTertiary))
            Text("-" + formatTime((state.durationMs - state.positionMs).coerceAtLeast(0)), style = text(11f, color = LyraColors.TextTertiary))
        }
        Row(
            Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.Center,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            IslandButton(onClick = controller::previous) {
                Icon(Icons.Rounded.SkipPrevious, "Anterior", tint = LyraColors.TextPrimary, modifier = Modifier.size(30.dp))
            }
            Spacer(Modifier.width(22.dp))
            IslandButton(
                onClick = controller::togglePlay,
                modifier = Modifier
                    .size(50.dp)
                    .clip(CircleShape)
                    .background(LyraColors.Accent),
            ) {
                Icon(
                    if (state.isPlaying) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                    if (state.isPlaying) "Pausa" else "Reproducir",
                    tint = LyraColors.OnAccent,
                    modifier = Modifier.size(30.dp),
                )
            }
            Spacer(Modifier.width(22.dp))
            IslandButton(onClick = controller::next) {
                Icon(Icons.Rounded.SkipNext, "Siguiente", tint = LyraColors.TextPrimary, modifier = Modifier.size(30.dp))
            }
        }
    }
}

@Composable
private fun IslandButton(onClick: () -> Unit, modifier: Modifier = Modifier.size(44.dp), content: @Composable () -> Unit) {
    val press = remember { Animatable(1f) }
    Box(
        modifier
            .graphicsLayer {
                scaleX = press.value
                scaleY = press.value
            }
            .pointerInput(Unit) { detectTapGestures(onTap = { onClick() }) },
        contentAlignment = Alignment.Center,
    ) { content() }
}

@Composable
private fun IslandArt(model: Any?, modifier: Modifier, shape: androidx.compose.ui.graphics.Shape) {
    AsyncImage(
        model = model?.let { if (it is String) hiResArtwork(it, 160) else it },
        contentDescription = null,
        contentScale = ContentScale.Crop,
        modifier = modifier
            .clip(shape)
            .background(LyraColors.SurfaceHigh),
    )
}

/**
 * Barras que siguen la música de verdad: graves, medios y agudos del audio que
 * sale del procesador de Lyra, leídos con el retraso con el que suenan. Si no
 * hay datos (p. ej. durante un crossfade) usan una animación suave de reserva.
 */
@Composable
fun ReactiveBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = LyraColors.Accent, bars: Int = 4) {
    val levels = remember(bars) { mutableStateListOf<Float>().apply { repeat(bars) { add(0.15f) } } }
    LaunchedEffect(playing, bars) {
        val raw = FloatArray(3)
        val smooth = FloatArray(bars) { levels[it] }
        val start = System.nanoTime()
        while (isActive) {
            withFrameNanos { }
            val now = System.nanoTime()
            val live = playing && AudioLevels.sample(now - AudioLevels.LATENCY_NANOS, raw)
            for (i in 0 until bars) {
                val target = when {
                    live -> {
                        // Niveles ya normalizados por el analizador (graves, medios, agudos).
                        val n0 = raw[0].pow(1.1f)
                        val n1 = raw[1].pow(1.1f)
                        val n2 = raw[2].pow(1.1f)
                        when (i) {
                            0 -> n0
                            1 -> if (bars == 3) n1 else n0 * 0.35f + n1 * 0.65f
                            2 -> if (bars == 3) n2 else n1
                            else -> n2
                        }
                    }
                    playing -> {
                        val t = (now - start) / 1_000_000_000f
                        0.4f + 0.3f * sin(t * (4.3f + i * 1.9f) + i * 1.3f)
                    }
                    else -> 0.1f
                }
                // Sube rápido y baja despacio, como un vúmetro.
                val k = if (target > smooth[i]) 0.6f else 0.16f
                smooth[i] += (target - smooth[i]) * k
                levels[i] = smooth[i]
            }
        }
    }
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(2.5.dp), verticalAlignment = Alignment.CenterVertically) {
        levels.forEach { level ->
            Box(
                Modifier
                    .weight(1f)
                    .fillMaxHeight((0.16f + 0.84f * level).coerceIn(0.14f, 1f))
                    .clip(RoundedCornerShape(50))
                    .background(if (playing) color else color.copy(alpha = 0.5f)),
            )
        }
    }
}

/** Tres barras sencillas que bailan (para las listas de la app). */
@Composable
fun EqualizerBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = LyraColors.Accent) {
    val transition = rememberInfiniteTransition(label = "barras")
    val durations = listOf(520, 380, 610)
    val heights = durations.mapIndexed { index, duration ->
        transition.animateFloat(
            initialValue = 0.25f,
            targetValue = 1f,
            animationSpec = infiniteRepeatable(tween(duration), RepeatMode.Reverse, initialStartOffset = StartOffset(index * 140)),
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
