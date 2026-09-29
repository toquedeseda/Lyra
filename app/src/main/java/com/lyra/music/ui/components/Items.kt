package com.lyra.music.ui.components

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyHorizontalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.MoreVert
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shape
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
import com.lyra.music.ui.theme.LyraColors

// ------------------------------------------------------------------ carátulas

@UnstableApi
@Composable
fun artworkFor(song: Song): Any? =
    LocalActions.current.container.downloads.localCover(song.id) ?: song.thumbnailUrl

@Composable
fun Artwork(
    model: Any?,
    modifier: Modifier = Modifier,
    shape: Shape = RoundedCornerShape(4.dp),
    placeholder: ImageVector = Icons.Rounded.MusicNote,
) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(LyraColors.SurfaceHigher),
        contentAlignment = Alignment.Center,
    ) {
        Icon(placeholder, null, tint = LyraColors.TextTertiary, modifier = Modifier.fillMaxSize(0.4f))
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}

/** Portada especial de "Canciones que te gustan" y "Descargas". */
@Composable
fun SpecialCover(icon: ImageVector, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
    Box(
        modifier = modifier
            .clip(shape)
            .background(Brush.linearGradient(listOf(Color(0xFF5A5A5A), Color(0xFF1A1A1A)))),
        contentAlignment = Alignment.Center,
    ) {
        Icon(icon, null, tint = Color.White, modifier = Modifier.fillMaxSize(0.42f))
    }
}

/** Mosaico 2×2 para playlists locales. */
@Composable
fun Mosaic(urls: List<String>, modifier: Modifier = Modifier, shape: Shape = RoundedCornerShape(4.dp)) {
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
private fun ItemArtwork(item: MusicItem, modifier: Modifier) {
    when {
        item.id == LIKED_SONGS_ID -> SpecialCover(Icons.Rounded.Favorite, modifier)
        item.id == DOWNLOADS_ID -> SpecialCover(Icons.Rounded.ArrowDownward, modifier)
        item is ArtistItem -> Artwork(item.thumbnailUrl, modifier, CircleShape, Icons.Rounded.Person)
        item is RadioItem -> Box(modifier) {
            Artwork(item.thumbnailUrl, Modifier.fillMaxSize())
            Box(
                Modifier
                    .fillMaxSize()
                    .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xCC000000)))),
            )
            Row(
                Modifier
                    .align(Alignment.BottomStart)
                    .padding(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Icon(Icons.Rounded.Radio, null, tint = Color.White, modifier = Modifier.size(16.dp))
                Spacer(Modifier.width(4.dp))
                Text(item.title, color = Color.White, fontWeight = FontWeight.Black, fontSize = 16.sp)
            }
        }
        else -> Artwork(item.thumbnailUrl, modifier)
    }
}

// ------------------------------------------------------------------ filas

@OptIn(ExperimentalFoundationApi::class)
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
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickable(
                onClick = onClick,
                onLongClick = { actions.songMenu = com.lyra.music.ui.SongMenuRequest(song, localPlaylistId, queueIndex) },
            )
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (index != null) {
            Box(Modifier.width(32.dp), contentAlignment = Alignment.CenterStart) {
                if (isCurrent) {
                    EqualizerBars(state.isPlaying, Modifier.size(14.dp))
                } else {
                    Text("$index", color = LyraColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (showArtwork) {
            Box {
                Artwork(artworkFor(song), Modifier.size(50.dp))
                if (isCurrent && index == null) {
                    Box(
                        Modifier
                            .size(50.dp)
                            .clip(RoundedCornerShape(4.dp))
                            .background(Color(0x99000000)),
                        contentAlignment = Alignment.Center,
                    ) { EqualizerBars(state.isPlaying, Modifier.size(18.dp)) }
                }
            }
            Spacer(Modifier.width(12.dp))
        }
        Column(Modifier.weight(1f)) {
            Text(
                song.title,
                color = LyraColors.TextPrimary,
                fontWeight = if (isCurrent) FontWeight.Black else FontWeight.Medium,
                style = MaterialTheme.typography.bodyLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
            Row(verticalAlignment = Alignment.CenterVertically) {
                if (song.id in state.likedIds) {
                    Icon(Icons.Rounded.Favorite, "Me gusta", tint = Color.White, modifier = Modifier.size(13.dp))
                    Spacer(Modifier.width(4.dp))
                }
                DownloadIndicator(download?.state, download?.progress)
                if (song.explicit) ExplicitBadge()
                if (song.source == Source.SOUNDCLOUD) SourceBadge("SC")
                Text(
                    listOfNotNull(if (song.isVideo && song.album == null) "Vídeo" else null, song.artistsText.ifEmpty { null }).joinToString(" • "),
                    color = LyraColors.TextSecondary,
                    style = MaterialTheme.typography.bodyMedium,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
        if (trailing != null) {
            trailing()
        } else {
            IconButton(onClick = { actions.songMenu = com.lyra.music.ui.SongMenuRequest(song, localPlaylistId, queueIndex) }) {
                Icon(Icons.Rounded.MoreVert, "Más opciones", tint = LyraColors.TextSecondary)
            }
        }
    }
}

@Composable
fun DownloadIndicator(state: Int?, progress: Float?) {
    when (state) {
        DownloadState.COMPLETED -> {
            Icon(Icons.Rounded.CheckCircle, "Descargada", tint = Color.White, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        DownloadState.DOWNLOADING -> {
            CircularProgressIndicator(
                progress = { progress ?: 0f },
                modifier = Modifier.size(12.dp),
                strokeWidth = 2.dp,
                color = Color.White,
                trackColor = LyraColors.SurfaceHigher,
            )
            Spacer(Modifier.width(5.dp))
        }
        DownloadState.QUEUED -> {
            Icon(Icons.Rounded.Download, "En cola", tint = LyraColors.TextTertiary, modifier = Modifier.size(13.dp))
            Spacer(Modifier.width(4.dp))
        }
        else -> Unit
    }
}

@Composable
fun ExplicitBadge() {
    Box(
        Modifier
            .padding(end = 5.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(LyraColors.TextSecondary)
            .padding(horizontal = 4.dp, vertical = 0.dp),
    ) {
        Text("E", color = Color.Black, fontSize = 10.sp, fontWeight = FontWeight.Black)
    }
}

@Composable
fun SourceBadge(text: String) {
    Box(
        Modifier
            .padding(end = 5.dp)
            .clip(RoundedCornerShape(2.dp))
            .background(LyraColors.SurfaceHigher)
            .padding(horizontal = 4.dp),
    ) {
        Text(text, color = LyraColors.TextSecondary, fontSize = 10.sp, fontWeight = FontWeight.Bold)
    }
}

fun subtitleOf(item: MusicItem): String = when (item) {
    is Song -> listOfNotNull("Canción", item.artistsText.ifEmpty { null }).joinToString(" • ")
    is AlbumItem -> listOfNotNull(item.kind ?: "Álbum", item.artistsText.ifEmpty { null }, item.year).joinToString(" • ")
    is ArtistItem -> item.subtitle ?: "Artista"
    is PlaylistItem -> listOfNotNull("Playlist", item.author, item.songCountText).joinToString(" • ")
    is RadioItem -> item.subtitle
}

/** Fila para álbumes, artistas y playlists (búsqueda, biblioteca…). */
@UnstableApi
@Composable
fun ItemRow(item: MusicItem, modifier: Modifier = Modifier, subtitle: String = subtitleOf(item), onClick: (() -> Unit)? = null) {
    if (item is Song) {
        val actions = LocalActions.current
        SongRow(item, onClick = { onClick?.invoke() ?: actions.open(item) }, modifier = modifier)
        return
    }
    val actions = LocalActions.current
    Row(
        modifier = modifier
            .fillMaxWidth()
            .combinedClickableCompat { onClick?.invoke() ?: actions.open(item) }
            .padding(horizontal = 16.dp, vertical = 7.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ItemArtwork(item, Modifier.size(56.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

fun Modifier.combinedClickableCompat(onClick: () -> Unit): Modifier = this.clickable(onClick = onClick)

// ------------------------------------------------------------------ tarjetas

@UnstableApi
@Composable
fun ItemCard(item: MusicItem, modifier: Modifier = Modifier, width: Dp = 150.dp) {
    val actions = LocalActions.current
    Column(
        modifier = modifier
            .width(width)
            .clip(RoundedCornerShape(6.dp))
            .combinedClickableCompat { actions.open(item) }
            .padding(bottom = 4.dp),
    ) {
        val artModifier = Modifier
            .fillMaxWidth()
            .aspectRatio(if (item is Song && item.isVideo) 16f / 9f else 1f)
        if (item is Song) Artwork(artworkFor(item), artModifier) else ItemArtwork(item, artModifier)
        Spacer(Modifier.height(8.dp))
        if (item !is RadioItem) {
            Text(
                item.title,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Text(
            if (item is RadioItem) item.subtitle else subtitleOf(item),
            style = MaterialTheme.typography.bodySmall,
            color = LyraColors.TextSecondary,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

/** Acceso rápido del Inicio (rejilla de 2 columnas, como en Spotify). */
@UnstableApi
@Composable
fun QuickTile(item: MusicItem, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    Row(
        modifier = modifier
            .height(56.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(LyraColors.SurfaceHigher)
            .combinedClickableCompat { actions.open(item) },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (item is Song) Artwork(artworkFor(item), Modifier.size(56.dp), RoundedCornerShape(0.dp))
        else ItemArtwork(item, Modifier.size(56.dp))
        Text(
            if (item is RadioItem) "${item.title} · ${item.subtitle}" else item.title,
            modifier = Modifier
                .weight(1f)
                .padding(horizontal = 10.dp),
            style = MaterialTheme.typography.labelLarge,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis,
        )
    }
}

// ------------------------------------------------------------------ secciones

@Composable
fun SectionHeader(title: String, subtitle: String? = null, onMore: (() -> Unit)? = null) {
    Row(
        Modifier
            .fillMaxWidth()
            .padding(start = 16.dp, end = 8.dp, top = 22.dp, bottom = 10.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(Modifier.weight(1f)) {
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
            }
            Text(title, style = MaterialTheme.typography.headlineSmall, maxLines = 2, overflow = TextOverflow.Ellipsis)
        }
        if (onMore != null) {
            Text(
                "Mostrar todo",
                style = MaterialTheme.typography.labelMedium,
                color = LyraColors.TextSecondary,
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .combinedClickableCompat(onMore)
                    .padding(8.dp),
            )
        }
    }
}

@UnstableApi
@Composable
fun SectionView(section: Section, onMore: (() -> Unit)? = null) {
    val actions = LocalActions.current
    SectionHeader(section.title, section.subtitle, onMore)
    when (section.style) {
        SectionStyle.SONG_GRID -> {
            val songs = section.items.filterIsInstance<Song>()
            val rows = minOf(4, songs.size.coerceAtLeast(1))
            LazyHorizontalGrid(
                rows = GridCells.Fixed(rows),
                modifier = Modifier.height((rows * 64).dp),
                contentPadding = PaddingValues(end = 16.dp),
            ) {
                items(songs, key = { it.id }) { song ->
                    SongRow(
                        song,
                        onClick = { actions.play(songs, songs.indexOf(song), fromLabel = section.title) },
                        modifier = Modifier.width(310.dp),
                    )
                }
            }
        }
        SectionStyle.LIST -> Column {
            section.items.take(5).forEach { item ->
                if (item is Song) {
                    val songs = section.items.filterIsInstance<Song>()
                    SongRow(item, onClick = { actions.play(songs, songs.indexOf(item), fromLabel = section.title) })
                } else {
                    ItemRow(item)
                }
            }
        }
        SectionStyle.CAROUSEL -> LazyRow(
            contentPadding = PaddingValues(horizontal = 16.dp),
            horizontalArrangement = Arrangement.spacedBy(14.dp),
        ) {
            items(section.items, key = { it.id }) { item ->
                ItemCard(item, width = if (item is Song && item.isVideo) 240.dp else 150.dp)
            }
        }
    }
}

// ------------------------------------------------------------------ botones

@Composable
fun PlayCircleButton(onClick: () -> Unit, modifier: Modifier = Modifier, size: Dp = 56.dp) {
    Box(
        modifier = modifier
            .size(size)
            .clip(CircleShape)
            .background(Color.White)
            .combinedClickableCompat(onClick),
        contentAlignment = Alignment.Center,
    ) {
        Icon(Icons.Rounded.PlayArrow, "Reproducir", tint = Color.Black, modifier = Modifier.size(size * 0.6f))
    }
}

@Composable
fun ShuffleIconButton(onClick: () -> Unit) {
    IconButton(onClick = onClick) {
        Icon(Icons.Rounded.Shuffle, "Aleatorio", tint = LyraColors.TextSecondary, modifier = Modifier.size(28.dp))
    }
}
