package com.lyra.music.ui.components

import androidx.compose.animation.animateContentSize
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.MoreVert
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.RadioItem
import com.lyra.music.data.model.Section
import com.lyra.music.data.model.SectionStyle
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.repo.DOWNLOADS_ID
import com.lyra.music.data.repo.LIKED_SONGS_ID
import com.lyra.music.island.EqualizerBars
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.SongMenuRequest
import com.lyra.music.ui.theme.LyraColors

// ------------------------------------------------------------------ interacción

/** Pulsación con un pequeño encogimiento (como en iOS), sin onda de Material. */
@OptIn(ExperimentalFoundationApi::class)
fun Modifier.pressable(
    onLongClick: (() -> Unit)? = null,
    pressedScale: Float = 0.97f,
    onClick: () -> Unit,
): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        if (pressed) pressedScale else 1f,
        spring(dampingRatio = 0.6f, stiffness = Spring.StiffnessMediumLow),
        label = "pulsación",
    )
    this
        .graphicsLayer {
            scaleX = scale
            scaleY = scale
        }
        .combinedClickable(interactionSource = interaction, indication = null, onClick = onClick, onLongClick = onLongClick)
}

fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

// ------------------------------------------------------------------ textos y botones

/** Etiqueta pequeña en mayúsculas espaciadas ("LYRA", "ÁLBUM · 2025"…). */
@Composable
fun Eyebrow(text: String, modifier: Modifier = Modifier, color: Color = LyraColors.TextSecondary) {
    Text(text.uppercase(), style = MaterialTheme.typography.labelSmall, color = color, modifier = modifier, maxLines = 1)
}

/** Botón píldora color hueso (acción principal). */
@Composable
fun PillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .height(40.dp)
            .clip(RoundedCornerShape(50))
            .background(LyraColors.Accent)
            .pressable(onClick = onClick)
            .padding(horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = LyraColors.OnAccent, modifier = Modifier.size(18.dp))
            Spacer(Modifier.width(8.dp))
        }
        Text(text, color = LyraColors.OnAccent, style = MaterialTheme.typography.labelLarge)
    }
}

/** Botón píldora oscuro (acción secundaria). */
@Composable
fun GhostPillButton(text: String, onClick: () -> Unit, modifier: Modifier = Modifier, icon: ImageVector? = null) {
    Row(
        modifier = modifier
            .height(38.dp)
            .clip(RoundedCornerShape(50))
            .background(LyraColors.SurfaceHigh)
            .border(1.dp, LyraColors.Border, RoundedCornerShape(50))
            .pressable(onClick = onClick)
            .padding(horizontal = 14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) {
            Icon(icon, null, tint = LyraColors.TextPrimary, modifier = Modifier.size(17.dp))
            Spacer(Modifier.width(7.dp))
        }
        Text(text, color = LyraColors.TextPrimary, style = MaterialTheme.typography.labelLarge)
    }
}

/** Tarjeta de aviso como la de "Instalar Lira'": título, texto y acciones. */
@Composable
fun InfoCard(
    title: String,
    text: String,
    modifier: Modifier = Modifier,
    actions: @Composable RowScope.() -> Unit = {},
) {
    Column(
        modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(LyraColors.SurfaceHigh)
            .border(1.dp, LyraColors.Border, RoundedCornerShape(16.dp))
            .padding(16.dp),
    ) {
        Text(title, style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        Text(text, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
        Spacer(Modifier.height(14.dp))
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp), content = actions)
    }
}

// ------------------------------------------------------------------ carátulas

@UnstableApi
@Composable
fun artworkFor(song: Song): Any? =
    LocalActions.current.container.downloads.localCover(song.id) ?: song.thumbnailUrl

@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(8.dp),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(LyraColors.SurfaceHigh),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = LyraColors.TextTertiary, modifier = Modifier.fillMaxSize(0.36f))
        if (model != null) {
            AsyncImage(model = model, contentDescription = null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
        }
    }
}

/**
 * Portadas de "Canciones que te gustan" (baldosa hueso con corazón oscuro, como
 * en la web) y "Descargas" (baldosa oscura con borde).
 */
@Composable
fun SpecialCover(
    icon: ImageVector,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(12.dp),
    filled: Boolean = icon == Icons.Rounded.Favorite,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (filled) LyraColors.Accent else LyraColors.Background)
            .then(if (filled) Modifier else Modifier.border(1.dp, LyraColors.Border, shape)),
        contentAlignment = Alignment.Center,
    ) {
        Icon(
            icon,
            null,
            tint = if (filled) LyraColors.OnAccent else LyraColors.TextPrimary,
            modifier = Modifier.fillMaxSize(0.38f),
        )
    }
}

/** Mosaico 2×2 para playlists locales. */
@Composable
fun Mosaic(urls: List<String>, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    if (urls.size < 4) {
        Artwork(urls.firstOrNull(), modifier, shape)
        return
    }
    Column(modifier.clip(shape)) {
        Row(Modifier.weight(1f)) {
            AsyncImage(urls[0], null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Crop)
            AsyncImage(urls[1], null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Crop)
        }
        Row(Modifier.weight(1f)) {
            AsyncImage(urls[2], null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Crop)
            AsyncImage(urls[3], null, Modifier.weight(1f).fillMaxSize(), contentScale = ContentScale.Crop)
        }
    }
}

@Composable
private fun ItemArtwork(item: MusicItem, modifier: Modifier, shape: Shape = RoundedCornerShape(12.dp)) {
    when {
        item.id == LIKED_SONGS_ID -> SpecialCover(Icons.Rounded.Favorite, modifier, shape, filled = true)
        item.id == DOWNLOADS_ID -> SpecialCover(Icons.Rounded.ArrowDownward, modifier, shape, filled = false)
        item is ArtistItem -> Artwork(item.thumbnailUrl, modifier, CircleShape, Icons.Rounded.Person)
        item is RadioItem -> Box(modifier.clip(shape)) {
            Artwork(item.thumbnailUrl, Modifier.fillMaxSize(), shape)
            // Velo para que el nombre del mix se lea sobre cualquier portada.
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(0f to Color(0x33000000), 0.55f to Color.Transparent, 1f to Color(0xB3000000))),
            )
            Text(
                item.title,
                color = LyraColors.TextPrimary,
                style = MaterialTheme.typography.headlineMedium,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            )
        }
        else -> Artwork(item.thumbnailUrl, modifier, shape)
    }
}

// ------------------------------------------------------------------ filas

@UnstableApi
@Composable
fun SongRow(
    song: Song,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showArtwork: Boolean = true,
    localPlaylistId: Long? = null,
    queueIndex: Int? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    val actions = LocalActions.current
    val state = LocalLibraryState.current
    val isCurrent = state.currentSongId == song.id
    val download = state.downloads[song.id]
    val openMenu = { actions.songMenu = SongMenuRequest(song, localPlaylistId, queueIndex) }
    // Sin internet, lo que no está descargado no puede sonar: se ve apagado.
    val available = state.online || download?.state == DownloadState.COMPLETED
    Row(
        modifier = modifier
            .fillMaxWidth()
            .alpha(if (available) 1f else 0.38f)
            .pressable(onLongClick = openMenu, pressedScale = 0.985f) {
                if (available) onClick() else actions.message("Sin conexión: esta canción no está descargada")
            }
            .padding(start = 20.dp, end = 8.dp, top = 6.dp, bottom = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(30.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) {
                    EqualizerBars(state.isPlaying, Modifier.size(14.dp), color = LyraColors.Accent)
                } else {
                    Text("$index", color = LyraColors.TextTertiary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (showArtwork) {
            Box {
                Artwork(artworkFor(song), Modifier.size(52.dp), RoundedCornerShape(10.dp))
                if (isCurrent && index == null) {
                    Box(
                        Modifier
                            .size(52.dp)
                            .clip(RoundedCornerShape(10.dp))
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) { EqualizerBars(state.isPlaying, Modifier.size(18.dp), color = LyraColors.Accent) }
                }
            }
            Spacer(Modifier.width(14.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = LyraColors.TextPrimary,
                fontWeight = if (isCurrent) FontWeight.Bold else FontWeight.SemiBold,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Spacer(Modifier.height(1.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                DownloadIndicator(download?.state, download?.progress)
                if (song.explicit) ExplicitBadge()
                if (song.source == Source.SOUNDCLOUD) SourceBadge("SC")
                Text(
                    listOfNotNull(if (song.isVideo && song.album == null) "Vídeo" else null, song.artistsText.ifEmpty { null }).joinToString(" · "),
                    color = LyraColors.TextSecondary,
                    style = MaterialTheme.typography.bodySmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (song.id in state.likedIds) {
            Icon(Icons.Rounded.Favorite, "Me gusta", tint = LyraColors.Like, modifier = Modifier.padding(start = 8.dp).size(16.dp))
        }
        if (trailing != null) {
            trailing()
        } else {
            IconButton(onClick = openMenu) {
                Icon(Icons.Outlined.MoreVert, "Más opciones", tint = LyraColors.TextTertiary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

@Composable
fun DownloadIndicator(state: Int?, progress: Float?) {
    AnimatedContent(
        targetState = state,
        transitionSpec = { (scaleIn(initialScale = 0.4f, animationSpec = spring(dampingRatio = 0.45f)) + fadeIn()) togetherWith fadeOut() },
        label = "estado de descarga",
    ) { current ->
        Row(verticalAlignment = Alignment.CenterVertically) { DownloadIndicatorContent(current, progress) }
    }
}

@Composable
private fun DownloadIndicatorContent(state: Int?, progress: Float?) {
    when (state) {
        DownloadState.COMPLETED -> {
            Icon(Icons.Rounded.CheckCircle, "Descargada", tint = LyraColors.Accent, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        DownloadState.DOWNLOADING -> {
            CircularProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = LyraColors.Accent,
                trackColor = LyraColors.SurfaceHigher,
            )
            Spacer(Modifier.width(6.dp))
        }
        DownloadState.QUEUED -> {
            Icon(Icons.Rounded.Download, "En cola", tint = LyraColors.TextTertiary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(5.dp))
        }
        else -> Unit
    }
}

@Composable
fun ExplicitBadge() {
    Box(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .background(LyraColors.SurfaceHigher)
            .padding(horizontal = 4.dp),
    ) {
        Text("E", color = LyraColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

@Composable
fun SourceBadge(text: String) {
    Box(
        Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(3.dp))
            .border(1.dp, LyraColors.Border, RoundedCornerShape(3.dp))
            .padding(horizontal = 4.dp),
    ) {
        Text(text, color = LyraColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.SemiBold)
    }
}

fun subtitleOf(item: MusicItem): String = when (item) {
    is Song -> listOfNotNull("Canción", item.artistsText.ifEmpty { null }).joinToString(" · ")
    is AlbumItem -> listOfNotNull(item.kind ?: "Álbum", item.artistsText.ifEmpty { null }, item.year).joinToString(" · ")
    is ArtistItem -> item.subtitle ?: "Artista"
    is PlaylistItem -> listOfNotNull("Playlist", item.author, item.songCountText).joinToString(" · ")
    is RadioItem -> item.subtitle
}

/** Fila para álbumes, artistas y playlists (búsqueda, biblioteca…). */
@UnstableApi
@Composable
fun ItemRow(item: MusicItem, modifier: Modifier = Modifier, subtitle: String = subtitleOf(item), onClick: (() -> Unit)? = null) {
    val actions = LocalActions.current
    if (item is Song) {
        SongRow(item, onClick = { onClick?.invoke() ?: actions.open(item) }, modifier = modifier)
        return
    }
    Row(
        modifier = modifier
            .fillMaxWidth()
            .pressable(pressedScale = 0.985f) { onClick?.invoke() ?: actions.open(item) }
            .padding(horizontal = 20.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemArtwork(item, Modifier.size(56.dp), RoundedCornerShape(10.dp))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

// ------------------------------------------------------------------ tarjetas

private fun MusicItem.isPlayable() = this is RadioItem || this is AlbumItem || this is PlaylistItem || this is Song

/** Tarjeta de carrusel: portada grande de 16 px con botón de play hueso encima. */
@UnstableApi
@Composable
fun ItemCard(item: MusicItem, modifier: Modifier = Modifier, width: Dp = 156.dp) {
    val actions = LocalActions.current
    Column(
        modifier = modifier
            .width(width)
            .pressable { actions.open(item) },
    ) {
        val isVideo = item is Song && item.isVideo && item.album == null
        Box(
            Modifier
                .fillMaxWidth()
                .aspectRatio(if (isVideo) 16f / 10f else 1f),
        ) {
            if (item is Song) Artwork(artworkFor(item), Modifier.fillMaxSize(), RoundedCornerShape(16.dp))
            else ItemArtwork(item, Modifier.fillMaxSize(), RoundedCornerShape(16.dp))
            if (item.isPlayable()) {
                PlayCircleButton(
                    onClick = { actions.playItem(item) },
                    size = if (width >= 170.dp) 46.dp else 40.dp,
                    modifier = Modifier
                        .align(Alignment.BottomEnd)
                        .padding(10.dp),
                )
            }
        }
        Spacer(Modifier.height(10.dp))
        if (item !is RadioItem) {
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        } else {
            Text(item.subtitle.substringBefore(",").ifEmpty { item.title }, style = MaterialTheme.typography.titleSmall, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
        Text(
            if (item is RadioItem) item.subtitle else subtitleOf(item),
            style = MaterialTheme.typography.bodySmall,
            color = LyraColors.TextSecondary,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Acceso rápido del Inicio (rejilla de 2 columnas). */
@UnstableApi
@Composable
fun QuickTile(item: MusicItem, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(LyraColors.SurfaceHigh)
            .pressable { actions.open(item) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item is Song) Artwork(artworkFor(item), Modifier.size(56.dp), RoundedCornerShape(12.dp))
        else ItemArtwork(item, Modifier.size(56.dp), RoundedCornerShape(12.dp))
        Text(
            if (item is RadioItem) item.subtitle.substringBefore(",") else item.title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 12.dp),
            style = MaterialTheme.typography.titleSmall,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ secciones

@Composable
fun SectionHeader(title: String, subtitle: String? = null, onMore: (() -> Unit)? = null, moreLabel: String = "Ver todo") {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 20.dp, end = 12.dp, top = 30.dp, bottom = 14.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, modifier = Modifier.padding(top = 2.dp))
            }
        }
        if (onMore != null) {
            Text(
                moreLabel,
                style = MaterialTheme.typography.labelMedium,
                color = LyraColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .clickable(onClick = onMore)
                    .padding(horizontal = 8.dp, vertical = 6.dp),
            )
        }
    }
}

@UnstableApi
@Composable
fun SectionView(section: Section, onMore: (() -> Unit)? = null) {
    val actions = LocalActions.current
    val songs = section.items.filterIsInstance<Song>()
    val isSongList = section.style != SectionStyle.CAROUSEL && songs.isNotEmpty() && songs.size == section.items.size
    if (isSongList) {
        // Lista vertical, como "En tendencia" en la web.
        var expanded by rememberSaveable(section.title) { mutableStateOf(false) }
        val canExpand = songs.size > 5
        SectionHeader(
            section.title,
            section.subtitle,
            onMore = when {
                canExpand -> ({ expanded = !expanded })
                else -> onMore
            },
            moreLabel = if (canExpand) (if (expanded) "Ver menos" else "Ver todo") else "Ver todo",
        )
        Column(Modifier.animateContentSize(spring(stiffness = Spring.StiffnessMediumLow))) {
            (if (expanded) songs else songs.take(5)).forEachIndexed { index, song ->
                SongRow(song, onClick = { actions.play(songs, index, fromLabel = section.title) })
            }
        }
        return
    }
    SectionHeader(section.title, section.subtitle, onMore)
    if (section.style == SectionStyle.LIST) {
        Column { section.items.take(5).forEach { ItemRow(it) } }
        return
    }
    val big = section.items.all { it is RadioItem }
    LazyRow(
        contentPadding = PaddingValues(horizontal = 20.dp),
        horizontalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        items(section.items, key = { it.id }) { item ->
            val isVideo = item is Song && item.isVideo && item.album == null
            ItemCard(item, width = when {
                big -> 176.dp
                isVideo -> 250.dp
                else -> 156.dp
            })
        }
    }
}

// ------------------------------------------------------------------ botones de reproducción

/** Botón circular color hueso con el play oscuro. */
@Composable
fun PlayCircleButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp, icon: ImageVector = Icons.Rounded.PlayArrow) {
    Box(
        modifier = modifier
            .size(size)
            .shadow(10.dp, CircleShape, ambientColor = Color.Black, spotColor = Color.Black)
            .clip(CircleShape)
            .background(LyraColors.Accent)
            .pressable(pressedScale = 0.92f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, "Reproducir", tint = LyraColors.OnAccent, modifier = Modifier.size(size * 0.56f))
    }
}

@Composable
fun ShuffleIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = LyraColors.TextSecondary, modifier = Modifier.size(26.dp))
    }
}

/** Icono redondo con borde fino (acciones secundarias de las cabeceras). */
@Composable
fun OutlineIconButton(icon: ImageVector, description: String, onClick: () -> Unit, tint: Color = LyraColors.TextPrimary) {
    Box(
        Modifier
            .padding(end = 10.dp)
            .size(40.dp)
            .clip(CircleShape)
            .border(BorderStroke(1.dp, LyraColors.Border), CircleShape)
            .pressable(pressedScale = 0.9f, onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, description, tint = tint, modifier = Modifier.size(20.dp))
    }
}
