package com.lyra.desktop.ui

import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.FullscreenExit
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.FilterQuality
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import coil3.compose.LocalPlatformContext
import coil3.request.ImageRequest
import coil3.request.transformations
import coil3.size.Size
import coil3.transform.Transformation
import com.lyra.desktop.data.RepeatMode
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LocalNowPlaying
import com.lyra.desktop.ui.components.NowPlaying
import com.lyra.desktop.ui.components.PlayButton
import com.lyra.desktop.ui.components.ThinSlider
import com.lyra.desktop.ui.components.formatDuration
import kotlinx.coroutines.delay
import org.jetbrains.skia.Bitmap
import org.jetbrains.skia.FilterMipmap
import org.jetbrains.skia.FilterMode
import org.jetbrains.skia.FilterTileMode
import org.jetbrains.skia.Image
import org.jetbrains.skia.ImageFilter
import org.jetbrains.skia.MipmapMode
import org.jetbrains.skia.Paint
import org.jetbrains.skia.Rect
import org.jetbrains.skia.Surface
import java.awt.Point
import java.awt.Toolkit
import java.awt.image.BufferedImage

/** Lado de la portada que se pide para el fondo (en píxeles): difuminada, una pequeña basta. */
private const val BLURRED_ART_PX = 96

/**
 * El fondo difuminado se prepara una sola vez: la portada encogida a un cuadradito y algo borrosa.
 * Estirada a toda la pantalla con suavizado se ve igual de difuminada, sin tener que difuminar la
 * pantalla entera en cada cuadro (eso gastaba mucha memoria y procesador).
 */
private object BlurredBackdrop : Transformation() {
    private const val SIDE = 32
    private const val SIGMA = 1.2f

    override val cacheKey = "lyra-fondo-difuminado-$SIDE"

    override suspend fun transform(input: Bitmap, size: Size): Bitmap =
        Surface.makeRasterN32Premul(SIDE, SIDE).use { surface ->
            Image.makeFromBitmap(input).use { image ->
                val side = minOf(image.width, image.height).toFloat()
                val center = Rect.makeXYWH((image.width - side) / 2f, (image.height - side) / 2f, side, side)
                Paint().use { paint ->
                    paint.imageFilter = ImageFilter.makeBlur(SIGMA, SIGMA, FilterTileMode.CLAMP)
                    surface.canvas.drawImageRect(
                        image, center, Rect.makeWH(SIDE.toFloat(), SIDE.toFloat()),
                        FilterMipmap(FilterMode.LINEAR, MipmapMode.LINEAR), paint, true,
                    )
                }
            }
            surface.makeImageSnapshot().use { Bitmap.makeFromImage(it) }
        }
}

/** Un cursor invisible: con el ratón quieto en pantalla completa, no tapa nada. */
private val HiddenPointer: PointerIcon by lazy {
    PointerIcon(Toolkit.getDefaultToolkit().createCustomCursor(BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB), Point(0, 0), "lyra-oculto"))
}

/**
 * Pantalla completa, como en Spotify: la portada en grande sobre ella misma difuminada, el título y,
 * si quieres, la letra a la derecha. Con el ratón quieto unos segundos, los controles y el cursor
 * se esconden. Esc (o F11) para salir.
 */
@Composable
fun FullScreenPlayer(actions: LyraActions, onExit: () -> Unit) {
    val app = actions.app
    val state by app.player.state.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val song = state.current?.song
    var lastMove by remember { mutableLongStateOf(System.currentTimeMillis()) }
    var idle by remember { mutableStateOf(false) }
    LaunchedEffect(lastMove) {
        idle = false
        delay(3_000)
        idle = true
    }
    val chrome by animateFloatAsState(if (idle) 0f else 1f, tween(if (idle) 900 else 180))

    CompositionLocalProvider(LocalActions provides actions, LocalNowPlaying provides NowPlaying(song?.id, state.isPlaying)) {
        LyraTheme {
            Box(
                Modifier
                    .fillMaxSize()
                    .background(LyraColors.Background)
                    // Cualquier movimiento, clic o rueda despierta los controles (antes que nadie los use).
                    .pointerInput(Unit) {
                        awaitPointerEventScope {
                            while (true) {
                                val event = awaitPointerEvent(PointerEventPass.Initial)
                                if (event.type == PointerEventType.Move || event.type == PointerEventType.Enter ||
                                    event.type == PointerEventType.Press || event.type == PointerEventType.Scroll
                                ) {
                                    lastMove = System.currentTimeMillis()
                                }
                            }
                        }
                    }
                    .pointerHoverIcon(if (idle) HiddenPointer else PointerIcon.Default),
            ) {
                val art = song?.thumbnailUrl?.let(::bigArtwork)
                // Fondo: la propia portada, enorme, muy difuminada y oscurecida (ver BlurredBackdrop).
                val context = LocalPlatformContext.current
                Crossfade(art, animationSpec = tween(900), label = "fondo") { url ->
                    if (url != null) {
                        AsyncImage(
                            model = remember(url) {
                                ImageRequest.Builder(context).data(url).size(BLURRED_ART_PX).transformations(BlurredBackdrop).build()
                            },
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            alpha = 0.5f,
                            filterQuality = FilterQuality.High,
                            modifier = Modifier.fillMaxSize(),
                        )
                    }
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color(0x260A0A0B), Color(0x990A0A0B), Color(0xF20A0A0B)))))

                if (song == null) {
                    Column(Modifier.align(Alignment.Center), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("No suena nada", style = MaterialTheme.typography.displaySmall, color = LyraColors.TextPrimary)
                        Spacer(Modifier.height(10.dp))
                        Text("Pulsa Esc para volver.", style = MaterialTheme.typography.bodyLarge, color = LyraColors.TextSecondary)
                    }
                    Box(Modifier.align(Alignment.TopEnd).padding(24.dp)) {
                        IconBtn(Icons.Rounded.FullscreenExit, "Salir de pantalla completa (Esc)", onClick = onExit, size = 44.dp, iconSize = 26.dp)
                    }
                    return@Box
                }

                BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal = 72.dp, vertical = 48.dp)) {
                    val lyricsOn = settings.fullScreenLyrics
                    val coverSize = minOf(maxHeight * 0.42f, maxWidth * (if (lyricsOn) 0.24f else 0.34f), 520.dp)

                    // Arriba: de dónde viene lo que suena, y salir.
                    Row(Modifier.align(Alignment.TopStart).fillMaxWidth().alpha(chrome), verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("REPRODUCIENDO DESDE", style = MaterialTheme.typography.labelSmall, color = LyraColors.TextTertiary)
                            Text(
                                state.context?.label ?: "Tu cola",
                                style = MaterialTheme.typography.titleMedium,
                                color = LyraColors.TextPrimary,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis,
                            )
                        }
                        IconBtn(Icons.Rounded.FullscreenExit, "Salir de pantalla completa (Esc)", onClick = onExit, size = 44.dp, iconSize = 26.dp)
                    }

                    // La letra, a la derecha.
                    if (lyricsOn) {
                        Box(Modifier.align(Alignment.TopEnd).padding(top = 76.dp, bottom = 150.dp).fillMaxWidth(0.46f).fillMaxHeight()) {
                            LyricsPanel(big = true)
                        }
                    }

                    // Abajo a la izquierda: portada, título y artistas.
                    Row(
                        Modifier.align(Alignment.BottomStart).padding(bottom = 124.dp).fillMaxWidth(if (lyricsOn) 0.5f else 1f),
                        verticalAlignment = Alignment.Bottom,
                    ) {
                        Crossfade(art, animationSpec = tween(500), label = "portada") { url ->
                            Cover(url, Modifier.size(coverSize).shadow(36.dp, RoundedCornerShape(12.dp)), RoundedCornerShape(12.dp))
                        }
                        Spacer(Modifier.width(36.dp))
                        Column(Modifier.weight(1f)) {
                            Text(
                                song.title,
                                style = MaterialTheme.typography.displayLarge.copy(
                                    fontSize = if (lyricsOn) 52.sp else 72.sp,
                                    lineHeight = if (lyricsOn) 54.sp else 74.sp,
                                ),
                                color = LyraColors.TextPrimary,
                                maxLines = 3,
                                overflow = TextOverflow.Ellipsis,
                            )
                            Spacer(Modifier.height(10.dp))
                            Text(song.artistsText, style = MaterialTheme.typography.headlineSmall, color = LyraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis)
                            val next = state.queue.getOrNull(state.index + 1)?.song
                            if (next != null) {
                                Spacer(Modifier.height(18.dp))
                                Text(
                                    "A continuación: ${next.title} · ${next.artistsText}",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = LyraColors.TextTertiary,
                                    maxLines = 1,
                                    overflow = TextOverflow.Ellipsis,
                                    modifier = Modifier.alpha(chrome),
                                )
                            }
                        }
                    }

                    // Abajo: el progreso y los controles (se esconden con el ratón quieto).
                    FullScreenControls(lyricsOn, Modifier.align(Alignment.BottomCenter).fillMaxWidth().alpha(chrome))
                }
            }
        }
    }
}

@Composable
private fun FullScreenControls(lyricsOn: Boolean, modifier: Modifier) {
    val actions = LocalActions.current
    val app = actions.app
    val state by app.player.state.collectAsState()
    val liked by app.library.likedIds.collectAsState()
    val song = state.current?.song
    val (position, duration) = rememberProgress()
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(
                formatDuration(if (seeking) (seekValue * duration).toLong() else position).ifEmpty { "0:00" },
                style = MaterialTheme.typography.labelMedium,
                color = LyraColors.TextSecondary,
                modifier = Modifier.width(52.dp),
            )
            ThinSlider(
                value = if (seeking) seekValue else if (duration > 0) position.toFloat() / duration else 0f,
                onChange = {
                    seeking = true
                    seekValue = it
                },
                onCommit = {
                    seeking = false
                    if (duration > 0) app.player.seek((it * duration).toLong())
                },
                enabled = duration > 0,
                modifier = Modifier.weight(1f),
            )
            Text(
                formatDuration(duration).ifEmpty { "0:00" },
                style = MaterialTheme.typography.labelMedium,
                color = LyraColors.TextSecondary,
                modifier = Modifier.width(52.dp).padding(start = 12.dp),
            )
        }
        Spacer(Modifier.height(12.dp))
        Box(Modifier.fillMaxWidth()) {
            if (song != null) {
                val isLiked = song.id in liked
                // En una caja propia: IconBtn no lleva el «align» a su capa de fuera.
                Box(Modifier.align(Alignment.CenterStart)) {
                    IconBtn(
                        if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                        if (isLiked) "Quitar de Me gusta" else "Añadir a Me gusta",
                        onClick = { actions.toggleLike(song) },
                        size = 44.dp,
                        iconSize = 24.dp,
                        tint = if (isLiked) LyraColors.Like else LyraColors.TextSecondary,
                        hoverTint = if (isLiked) LyraColors.Like else LyraColors.TextPrimary,
                    )
                }
            }
            Row(Modifier.align(Alignment.Center), horizontalArrangement = Arrangement.spacedBy(18.dp), verticalAlignment = Alignment.CenterVertically) {
                IconBtn(Icons.Rounded.Shuffle, if (state.shuffle) "Quitar aleatorio" else "Aleatorio", onClick = { app.player.setShuffle(!state.shuffle) }, size = 44.dp, iconSize = 24.dp, active = state.shuffle)
                IconBtn(Icons.Rounded.SkipPrevious, "Anterior", onClick = { app.player.previous() }, size = 52.dp, iconSize = 34.dp, tint = LyraColors.TextPrimary)
                PlayButton(state.isPlaying, onClick = { app.player.togglePlay() }, size = 64.dp)
                IconBtn(Icons.Rounded.SkipNext, "Siguiente", onClick = { app.player.next() }, size = 52.dp, iconSize = 34.dp, tint = LyraColors.TextPrimary)
                IconBtn(
                    if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (state.repeat) {
                        RepeatMode.OFF -> "Repetir"
                        RepeatMode.ALL -> "Repetir una"
                        RepeatMode.ONE -> "No repetir"
                    },
                    onClick = { app.player.cycleRepeat() },
                    size = 44.dp,
                    iconSize = 24.dp,
                    active = state.repeat != RepeatMode.OFF,
                )
            }
            Box(Modifier.align(Alignment.CenterEnd)) {
                IconBtn(
                    Icons.Rounded.Lyrics,
                    if (lyricsOn) "Ocultar la letra" else "Ver la letra",
                    onClick = { app.settings.update { it.copy(fullScreenLyrics = !it.fullScreenLyrics) } },
                    size = 44.dp,
                    iconSize = 24.dp,
                    active = lyricsOn,
                )
            }
        }
    }
}
