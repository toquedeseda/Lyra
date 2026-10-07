package com.lyra.desktop.ui.components

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import coil3.compose.AsyncImage
import com.lyra.desktop.ui.LyraColors
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

// ---------------------------------------------------------------------- portadas

/** Portada con fondo mientras carga (o si no hay). Acepta URLs y rutas de archivo. */
@Composable
fun Cover(url: String?, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp), icon: ImageVector = Icons.Rounded.MusicNote) {
    Box(modifier.clip(shape).background(LyraColors.SurfaceHigher), contentAlignment = Alignment.Center) {
        if (url == null) {
            Icon(icon, null, tint = LyraColors.TextTertiary, modifier = Modifier.fillMaxSize(0.4f))
        } else {
            val model: Any = if (url.startsWith("http")) url else File(url)
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/** Portada especial (Me gusta, Descargas…): un icono sobre el color de Lyra o gris. */
@Composable
fun SpecialCover(icon: ImageVector, modifier: Modifier = Modifier, filled: Boolean = true, shape: Shape = RoundedCornerShape(6.dp)) {
    val accent = LyraColors.Accent
    Box(
        modifier
            .clip(shape)
            .background(
                if (filled) Brush.linearGradient(listOf(accent, accent.copy(alpha = 0.72f)))
                else Brush.linearGradient(listOf(LyraColors.SurfaceHigher, LyraColors.SurfaceHigh)),
            ),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = if (filled) LyraColors.OnAccent else LyraColors.TextPrimary, modifier = Modifier.fillMaxSize(0.42f))
    }
}

/** Portada de playlist sin carátula: las de sus primeras canciones, de cuatro en cuatro. */
@Composable
fun Mosaic(urls: List<String?>, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(6.dp)) {
    val distinct = urls.filterNotNull().distinct()
    if (distinct.size < 4) {
        Cover(distinct.firstOrNull(), modifier, shape)
        return
    }
    Column(modifier.clip(shape)) {
        Row(Modifier.weight(1f)) {
            Cover(distinct[0], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
            Cover(distinct[1], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
        }
        Row(Modifier.weight(1f)) {
            Cover(distinct[2], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
            Cover(distinct[3], Modifier.weight(1f).fillMaxSize(), RoundedCornerShape(0.dp))
        }
    }
}

/** La carátula en el tamaño que haga falta (las de YouTube Music llegan pequeñas). */
fun art(url: String?, size: Int = 400): String? = url?.let { com.lyra.music.data.source.innertube.hiResArtwork(it, size) ?: it }

// ---------------------------------------------------------------------- botones

/** Botón redondo grande del color de Lyra (reproducir), con un pequeño crecimiento al pasar. */
@Composable
fun PlayButton(playing: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 52.dp) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.94f else if (hovered) 1.05f else 1f, tween(120))
    Box(
        modifier
            .size(size)
            .scale(scale)
            .clip(CircleShape)
            .background(LyraColors.Accent)
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
            if (playing) "Pausa" else "Reproducir",
            tint = LyraColors.OnAccent,
            modifier = Modifier.size(size * 0.5f),
        )
    }
}

/** Botón de icono, con círculo suave al pasar el ratón. */
@Composable
fun IconBtn(
    icon: ImageVector,
    description: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    size: Dp = 36.dp,
    iconSize: Dp = 20.dp,
    tint: Color = LyraColors.TextSecondary,
    hoverTint: Color = LyraColors.TextPrimary,
    enabled: Boolean = true,
    active: Boolean = false,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(if (pressed) 0.9f else 1f, tween(90))
    val color = when {
        !enabled -> LyraColors.TextTertiary.copy(alpha = 0.5f)
        active -> LyraColors.Accent
        hovered -> hoverTint
        else -> tint
    }
    Tooltip(description) {
        Box(
            modifier
                .size(size)
                .scale(scale)
                .clip(CircleShape)
                .background(if (hovered && enabled) LyraColors.Hover else Color.Transparent)
                .hoverable(interaction)
                .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
                .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default),
            contentAlignment = Alignment.Center,
        ) {
            Icon(icon, description, tint = color, modifier = Modifier.size(iconSize))
            if (active) {
                Box(Modifier.align(Alignment.BottomCenter).padding(bottom = 2.dp).size(4.dp).clip(CircleShape).background(LyraColors.Accent))
            }
        }
    }
}

/** Botón con texto y borde (Seguir, Guardar…). */
@Composable
fun OutlinePill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, selected: Boolean = false) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .clip(RoundedCornerShape(50))
            .border(1.dp, if (hovered || selected) LyraColors.TextPrimary else LyraColors.TextTertiary, RoundedCornerShape(50))
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = LyraColors.TextPrimary, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = LyraColors.TextPrimary)
    }
}

/** Botón relleno (del color de Lyra). */
@Composable
fun FilledPill(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null, enabled: Boolean = true) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    val scale by animateFloatAsState(if (hovered && enabled) 1.03f else 1f, tween(120))
    Row(
        modifier
            .scale(scale)
            .clip(RoundedCornerShape(50))
            .background(if (enabled) LyraColors.Accent else LyraColors.SurfaceHigher)
            .hoverable(interaction)
            .clickable(interaction, indication = null, enabled = enabled, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 18.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = LyraColors.OnAccent, modifier = Modifier.size(16.dp))
            Spacer(Modifier.width(6.dp))
        }
        Text(text, style = MaterialTheme.typography.labelLarge, color = if (enabled) LyraColors.OnAccent else LyraColors.TextTertiary)
    }
}

/** Pastilla de filtro (Playlists, Álbumes…), como en Spotify. */
@Composable
fun FilterChip(label: String, selected: Boolean, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .clip(RoundedCornerShape(50))
            .background(
                when {
                    selected -> LyraColors.Accent
                    hovered -> LyraColors.SurfaceHigher
                    else -> LyraColors.SurfaceHigh
                },
            )
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand)
            .padding(horizontal = 14.dp, vertical = 6.dp),
    ) {
        Text(label, style = MaterialTheme.typography.labelMedium, color = if (selected) LyraColors.OnAccent else LyraColors.TextPrimary, maxLines = 1, softWrap = false)
    }
}

// ---------------------------------------------------------------------- campos de texto

/** Campo redondeado con lupa (buscador). */
@Composable
fun SearchBox(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    onSubmit: () -> Unit = {},
    onEscape: () -> Unit = {},
    leading: ImageVector = Icons.Rounded.Search,
    height: Dp = 44.dp,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Row(
        modifier
            .height(height)
            .clip(RoundedCornerShape(50))
            .background(if (hovered) LyraColors.SurfaceHigher else LyraColors.SurfaceHigh)
            .border(1.dp, if (hovered) LyraColors.TextTertiary.copy(alpha = 0.5f) else Color.Transparent, RoundedCornerShape(50))
            .hoverable(interaction)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Icon(leading, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(10.dp))
        Box(Modifier.weight(1f), contentAlignment = Alignment.CenterStart) {
            if (value.isEmpty()) {
                Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextTertiary, maxLines = 1)
            }
            BasicTextField(
                value = value,
                onValueChange = onValueChange,
                singleLine = true,
                textStyle = MaterialTheme.typography.bodyMedium.copy(color = LyraColors.TextPrimary),
                cursorBrush = SolidColor(LyraColors.Accent),
                modifier = Modifier
                    .fillMaxWidth()
                    .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                    .then(rememberTypingFocus())
                    .onPreviewKeyEvent { event ->
                        when {
                            event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter) -> {
                                onSubmit()
                                true
                            }
                            event.type == KeyEventType.KeyDown && event.key == Key.Escape -> {
                                onEscape()
                                true
                            }
                            else -> false
                        }
                    },
            )
        }
        if (value.isNotEmpty()) {
            IconBtn(Icons.Rounded.Close, "Borrar", onClick = { onValueChange("") }, size = 28.dp, iconSize = 16.dp)
        }
    }
}

/**
 * Hay un cuadro de texto con el cursor dentro: la barra espaciadora escribe un espacio en vez de
 * pausar la música (los atajos de la ventana lo miran).
 */
object Typing {
    private val focused = AtomicInteger()
    val active: Boolean get() = focused.get() > 0

    internal fun changed(gained: Boolean) {
        if (gained) focused.incrementAndGet() else focused.updateAndGet { (it - 1).coerceAtLeast(0) }
    }
}

/** Avisa a [Typing] cuando este cuadro de texto gana o pierde el cursor (y si desaparece con él). */
@Composable
private fun rememberTypingFocus(): Modifier {
    val focused = remember { booleanArrayOf(false) }
    DisposableEffect(Unit) {
        onDispose { if (focused[0]) Typing.changed(gained = false) }
    }
    return Modifier.onFocusChanged { state ->
        if (state.isFocused != focused[0]) {
            focused[0] = state.isFocused
            Typing.changed(state.isFocused)
        }
    }
}

/** Campo de texto con borde (en los cuadros de diálogo). */
@Composable
fun LyraTextField(
    value: String,
    onValueChange: (String) -> Unit,
    placeholder: String,
    modifier: Modifier = Modifier,
    focusRequester: FocusRequester? = null,
    singleLine: Boolean = true,
    onSubmit: () -> Unit = {},
) {
    Box(
        modifier
            .clip(RoundedCornerShape(8.dp))
            .background(LyraColors.SurfaceHigher)
            .padding(horizontal = 12.dp, vertical = 11.dp),
    ) {
        if (value.isEmpty()) Text(placeholder, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextTertiary)
        BasicTextField(
            value = value,
            onValueChange = onValueChange,
            singleLine = singleLine,
            textStyle = MaterialTheme.typography.bodyMedium.copy(color = LyraColors.TextPrimary),
            cursorBrush = SolidColor(LyraColors.Accent),
            modifier = Modifier
                .fillMaxWidth()
                .then(if (focusRequester != null) Modifier.focusRequester(focusRequester) else Modifier)
                .then(rememberTypingFocus())
                .onPreviewKeyEvent { event ->
                    if (singleLine && event.type == KeyEventType.KeyDown && (event.key == Key.Enter || event.key == Key.NumPadEnter)) {
                        onSubmit()
                        true
                    } else {
                        false
                    }
                },
        )
    }
}

// ---------------------------------------------------------------------- textos y estados

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier, subtitle: String? = null, onMore: (() -> Unit)? = null, moreLabel: String = "Mostrar todo") {
    Row(modifier.fillMaxWidth().padding(bottom = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, color = LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (onMore != null) LinkText(moreLabel, onMore)
    }
}

/** Texto que se subraya al pasar el ratón (Mostrar todo, nombres de artista…). */
@Composable
fun LinkText(
    text: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    color: Color = LyraColors.TextSecondary,
    style: androidx.compose.ui.text.TextStyle = MaterialTheme.typography.labelLarge,
    maxLines: Int = 1,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Text(
        text,
        style = style.copy(textDecoration = if (hovered) androidx.compose.ui.text.style.TextDecoration.Underline else null),
        color = if (hovered) LyraColors.TextPrimary else color,
        maxLines = maxLines,
        overflow = TextOverflow.Ellipsis,
        modifier = modifier
            .hoverable(interaction)
            .clickable(interaction, indication = null, onClick = onClick)
            .pointerHoverIcon(PointerIcon.Hand),
    )
}

@Composable
fun LoadingView(modifier: Modifier = Modifier) {
    Box(modifier.fillMaxWidth().padding(48.dp), contentAlignment = Alignment.Center) {
        CircularProgressIndicator(color = LyraColors.Accent, strokeWidth = 2.5.dp, modifier = Modifier.size(32.dp))
    }
}

@Composable
fun MessageView(
    icon: ImageVector,
    title: String,
    text: String? = null,
    modifier: Modifier = Modifier,
    action: String? = null,
    onAction: (() -> Unit)? = null,
) {
    Column(modifier.fillMaxWidth().padding(vertical = 56.dp, horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        Icon(icon, null, tint = LyraColors.TextTertiary, modifier = Modifier.size(44.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.titleLarge, color = LyraColors.TextPrimary)
        if (text != null) {
            Spacer(Modifier.height(6.dp))
            Text(text, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        }
        if (action != null && onAction != null) {
            Spacer(Modifier.height(18.dp))
            OutlinePill(action, onAction)
        }
    }
}

/** Barritas que se mueven cuando esa canción está sonando. */
@Composable
fun PlayingBars(playing: Boolean, modifier: Modifier = Modifier, color: Color = LyraColors.Accent) {
    val transition = rememberInfiniteTransition()
    val heights = listOf(520, 380, 640).map { duration ->
        transition.animateFloat(0.25f, 1f, infiniteRepeatable(tween(duration, easing = FastOutSlowInEasing), RepeatMode.Reverse))
    }
    Canvas(modifier) {
        val barWidth = size.width / 5
        heights.forEachIndexed { index, value ->
            val h = size.height * (if (playing) value.value else 0.3f)
            drawRoundRect(
                color,
                topLeft = Offset(index * barWidth * 2, size.height - h),
                size = Size(barWidth, h),
                cornerRadius = CornerRadius(barWidth / 2),
            )
        }
    }
}

/** Fondo al pasar el ratón por encima (filas y tarjetas). */
@Composable
fun HoverBox(
    onClick: (() -> Unit)?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(6.dp),
    selected: Boolean = false,
    content: @Composable BoxScope.(hovered: Boolean) -> Unit,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    Box(
        modifier
            .clip(shape)
            .background(
                when {
                    selected -> LyraColors.SurfaceHigher
                    hovered -> LyraColors.Hover
                    else -> Color.Transparent
                },
            )
            .hoverable(interaction)
            .then(if (onClick != null) Modifier.clickable(interaction, indication = null, onClick = onClick).pointerHoverIcon(PointerIcon.Hand) else Modifier),
    ) {
        content(hovered)
    }
}

fun formatDuration(ms: Long?): String {
    if (ms == null || ms <= 0) return ""
    val total = ms / 1000
    val hours = total / 3600
    val minutes = (total % 3600) / 60
    val seconds = total % 60
    return if (hours > 0) "%d:%02d:%02d".format(hours, minutes, seconds) else "%d:%02d".format(minutes, seconds)
}

/** «1 h 23 min» o «12 min». */
fun formatLongDuration(ms: Long): String {
    val minutes = ms / 60_000
    return if (minutes >= 60) "${minutes / 60} h ${minutes % 60} min" else "$minutes min"
}

@Composable
fun Gap(width: Dp = 0.dp, height: Dp = 0.dp) = Spacer(Modifier.width(width).height(height))

/** Texto de ayuda al dejar el ratón encima. */
@Composable
fun Tooltip(text: String, content: @Composable () -> Unit) {
    androidx.compose.foundation.TooltipArea(
        tooltip = {
            Box(
                Modifier
                    .clip(RoundedCornerShape(6.dp))
                    .background(LyraColors.SurfaceHigher)
                    .border(1.dp, LyraColors.Border, RoundedCornerShape(6.dp))
                    .padding(horizontal = 10.dp, vertical = 6.dp),
            ) {
                Text(text, style = MaterialTheme.typography.labelMedium, color = LyraColors.TextPrimary)
            }
        },
        delayMillis = 650,
        tooltipPlacement = androidx.compose.foundation.TooltipPlacement.CursorPoint(offset = androidx.compose.ui.unit.DpOffset(0.dp, 18.dp)),
        content = content,
    )
}
