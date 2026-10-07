package com.lyra.desktop.ui.components

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.PlaylistAdd
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.rounded.Album
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.DownloadDone
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.MoreHoriz
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.Person
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.tween
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.isSecondaryPressed
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.lyra.desktop.data.DownloadStatus
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.RadioItem
import com.lyra.music.data.model.Song

/** Lo que suena ahora (para marcar su fila en las listas). */
data class NowPlaying(val songId: String? = null, val playing: Boolean = false)

val LocalNowPlaying = staticCompositionLocalOf { NowPlaying() }

/** Estado de un menú que se abre con el clic derecho donde esté el ratón. */
class MenuState {
    var open by mutableStateOf(false)
    var offset by mutableStateOf(DpOffset.Zero)
}

/** Clic derecho: abre el menú justo donde está el ratón. */
@Composable
fun Modifier.contextMenu(state: MenuState): Modifier {
    val density = LocalDensity.current
    var height by remember { mutableIntStateOf(0) }
    return this
        .onSizeChanged { height = it.height }
        .onPointerEvent(PointerEventType.Press) { event ->
            if (event.buttons.isSecondaryPressed) {
                val position = event.changes.first().position
                with(density) { state.offset = DpOffset(position.x.toDp(), (position.y - height).toDp()) }
                state.open = true
            }
        }
}

@Composable
fun LyraMenu(state: MenuState, content: @Composable ColumnScope.(close: () -> Unit) -> Unit) {
    DropdownMenu(
        expanded = state.open,
        onDismissRequest = { state.open = false },
        offset = state.offset,
        containerColor = LyraColors.SurfaceHigher,
        shape = RoundedCornerShape(8.dp),
        modifier = Modifier.width(260.dp),
    ) {
        content { state.open = false }
    }
}

@Composable
fun MenuEntry(text: String, icon: ImageVector?, onClick: () -> Unit, enabled: Boolean = true, danger: Boolean = false) {
    DropdownMenuItem(
        text = { Text(text, style = MaterialTheme.typography.bodyMedium, color = if (danger) LyraColors.Like else LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis) },
        leadingIcon = icon?.let { { Icon(it, null, tint = LyraColors.TextSecondary, modifier = Modifier.size(18.dp)) } },
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.height(38.dp),
    )
}

@Composable
fun MenuDivider() = HorizontalDivider(color = LyraColors.Border, modifier = Modifier.padding(vertical = 4.dp))

/** Las opciones de una canción (clic derecho o «⋯»). [extra]: opciones de la pantalla. */
@Composable
fun ColumnScope.SongMenuEntries(song: Song, close: () -> Unit, extra: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null) {
    val actions = LocalActions.current
    val app = actions.app
    val liked = song.id in app.library.likedIds.collectAsState().value
    val download = app.downloads.entries.collectAsState().value[song.id]
    MenuEntry("Reproducir a continuación", Icons.Rounded.SkipNext, { close(); app.player.playNext(listOf(song)) })
    MenuEntry("Añadir a la cola", Icons.AutoMirrored.Rounded.QueueMusic, { close(); app.player.addToQueue(listOf(song)) })
    MenuEntry("Añadir a una playlist…", Icons.AutoMirrored.Rounded.PlaylistAdd, { close(); actions.addToPlaylist = listOf(song) })
    MenuEntry(if (liked) "Quitar de Me gusta" else "Añadir a Me gusta", if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder, { close(); actions.toggleLike(song) })
    MenuDivider()
    MenuEntry("Iniciar radio", Icons.Rounded.Radio, { close(); app.player.startRadio(song) })
    if (song.album?.id != null) MenuEntry("Ir al álbum", Icons.Rounded.Album, { close(); actions.goToAlbum(song) })
    song.artists.filter { it.id != null }.take(3).forEach { artist ->
        MenuEntry("Ir a ${artist.name}", Icons.Rounded.Person, {
            close()
            actions.nav.navigate(com.lyra.desktop.ui.Screen.Artist(artist.id!!, ArtistItem(artist.id!!, artist.name)))
        })
    }
    MenuDivider()
    when (download?.status) {
        DownloadStatus.DONE -> MenuEntry("Quitar descarga", Icons.Rounded.DownloadDone, { close(); app.downloads.remove(song.id) })
        DownloadStatus.QUEUED, DownloadStatus.DOWNLOADING -> MenuEntry("Descargando…", Icons.Rounded.ArrowDownward, {}, enabled = false)
        else -> MenuEntry("Descargar", Icons.Rounded.ArrowDownward, { close(); actions.download(listOf(song)) })
    }
    MenuEntry("Copiar enlace", Icons.Rounded.Link, { close(); actions.copyLink(song) })
    if (extra != null) {
        MenuDivider()
        extra(close)
    }
}

/**
 * Fila de canción, como en Spotify: número (o barritas si suena), portada, título y artistas,
 * álbum, corazón, duración y «⋯». Clic para reproducir, clic derecho para el menú.
 */
@Composable
fun SongRow(
    song: Song,
    onPlay: () -> Unit,
    modifier: Modifier = Modifier,
    index: Int? = null,
    showCover: Boolean = true,
    showAlbum: Boolean = false,
    subtitle: String? = null,
    menuExtra: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    val actions = LocalActions.current
    val now = LocalNowPlaying.current
    val isCurrent = now.songId == song.id
    val liked = song.id in actions.app.library.likedIds.collectAsState().value
    val downloaded = actions.app.downloads.entries.collectAsState().value[song.id]?.status == DownloadStatus.DONE
    val menu = remember { MenuState() }
    Box(modifier.contextMenu(menu)) {
        HoverBox(onClick = { if (isCurrent) actions.app.player.togglePlay() else onPlay() }, selected = menu.open) { hovered ->
            Row(
                Modifier.fillMaxWidth().height(56.dp).padding(horizontal = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                if (index != null || !showCover) {
                    Box(Modifier.width(28.dp), contentAlignment = Alignment.Center) {
                        when {
                            hovered -> Icon(
                                if (isCurrent && now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow,
                                null,
                                tint = LyraColors.TextPrimary,
                                modifier = Modifier.size(18.dp),
                            )
                            isCurrent -> PlayingBars(now.playing, Modifier.size(14.dp))
                            index != null -> Text("$index", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                if (showCover) {
                    Box {
                        Cover(song.thumbnailUrl, Modifier.size(40.dp), RoundedCornerShape(4.dp))
                        if (index == null && (hovered || isCurrent)) {
                            Box(Modifier.size(40.dp).clip(RoundedCornerShape(4.dp)).background(Color(0x99000000)), contentAlignment = Alignment.Center) {
                                if (hovered) {
                                    Icon(if (isCurrent && now.playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = LyraColors.TextPrimary, modifier = Modifier.size(20.dp))
                                } else {
                                    PlayingBars(now.playing, Modifier.size(14.dp))
                                }
                            }
                        }
                    }
                    Spacer(Modifier.width(12.dp))
                }
                Column(Modifier.weight(1f)) {
                    Text(
                        song.title,
                        style = MaterialTheme.typography.bodyLarge,
                        color = if (isCurrent) LyraColors.Accent else LyraColors.TextPrimary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        if (song.explicit) {
                            Box(Modifier.padding(end = 6.dp).clip(RoundedCornerShape(2.dp)).background(LyraColors.TextSecondary).padding(horizontal = 4.dp)) {
                                Text("E", style = MaterialTheme.typography.labelSmall.copy(letterSpacing = 0.sp), color = LyraColors.Background)
                            }
                        }
                        if (downloaded) {
                            Icon(Icons.Rounded.ArrowDownward, "Descargada", tint = LyraColors.Accent, modifier = Modifier.size(14.dp).padding(end = 2.dp))
                        }
                        Text(
                            subtitle ?: song.artistsText,
                            style = MaterialTheme.typography.bodySmall,
                            color = LyraColors.TextSecondary,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
                if (showAlbum) {
                    Box(Modifier.weight(0.7f).padding(horizontal = 12.dp)) {
                        val album = song.album
                        if (album != null) {
                            if (album.id != null) {
                                LinkText(album.title, onClick = { actions.goToAlbum(song) }, style = MaterialTheme.typography.bodySmall)
                            } else {
                                Text(album.title, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
                Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                    if (hovered || liked) {
                        IconBtn(
                            if (liked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                            if (liked) "Quitar de Me gusta" else "Añadir a Me gusta",
                            onClick = { actions.toggleLike(song) },
                            size = 32.dp,
                            iconSize = 18.dp,
                            tint = if (liked) LyraColors.Like else LyraColors.TextSecondary,
                            hoverTint = if (liked) LyraColors.Like else LyraColors.TextPrimary,
                        )
                    }
                }
                Text(
                    formatDuration(song.durationMs),
                    style = MaterialTheme.typography.bodySmall,
                    color = LyraColors.TextSecondary,
                    modifier = Modifier.width(48.dp).padding(start = 6.dp),
                )
                Box(Modifier.width(36.dp), contentAlignment = Alignment.Center) {
                    if (hovered || menu.open) {
                        IconBtn(Icons.Rounded.MoreHoriz, "Más opciones", onClick = {
                            menu.offset = DpOffset((-200).dp, 0.dp)
                            menu.open = true
                        }, size = 32.dp, iconSize = 20.dp)
                    }
                }
            }
        }
        LyraMenu(menu) { close -> SongMenuEntries(song, close, menuExtra) }
    }
}


/** Subtítulo de una tarjeta, según lo que sea. */
fun MusicItem.cardSubtitle(): String = when (this) {
    is Song -> artistsText
    is AlbumItem -> listOfNotNull(kind ?: "Álbum", year, artistsText.takeIf { it.isNotBlank() }).joinToString(" · ")
    is ArtistItem -> subtitle ?: "Artista"
    is PlaylistItem -> listOfNotNull(author?.let { "De $it" }, songCountText).joinToString(" · ").ifBlank { "Playlist" }
    is RadioItem -> subtitle
}

/**
 * Tarjeta de álbum, artista, playlist o canción (portada grande y textos). Al pasar el ratón
 * aparece el botón de reproducir, como en Spotify.
 */
@Composable
fun ItemCard(item: MusicItem, onOpen: () -> Unit, modifier: Modifier = Modifier, onPlay: (() -> Unit)? = null, width: Dp = 176.dp) {
    val round = item is ArtistItem
    val menu = remember { MenuState() }
    Box(modifier.width(width).contextMenu(menu)) {
        HoverBox(onClick = onOpen, shape = RoundedCornerShape(10.dp), selected = menu.open) { hovered ->
            Column(Modifier.fillMaxWidth().padding(10.dp)) {
                Box {
                    Cover(
                        art(item.thumbnailUrl),
                        Modifier.fillMaxWidth().aspectRatio(1f).shadow(if (hovered) 10.dp else 4.dp, if (round) CircleShape else RoundedCornerShape(8.dp)),
                        if (round) CircleShape else RoundedCornerShape(8.dp),
                        icon = if (item is ArtistItem) Icons.Rounded.Person else Icons.Rounded.Album,
                    )
                    if (onPlay != null) {
                        // El botón sube un poco y aparece al pasar el ratón (como en Spotify).
                        val shown by animateFloatAsState(if (hovered) 1f else 0f, tween(180))
                        if (shown > 0f) {
                            PlayButton(
                                playing = false,
                                onClick = onPlay,
                                size = 44.dp,
                                modifier = Modifier
                                    .align(Alignment.BottomEnd)
                                    .padding(8.dp)
                                    .graphicsLayer {
                                        alpha = shown
                                        translationY = (1f - shown) * 10.dp.toPx()
                                    }
                                    .shadow(8.dp, CircleShape),
                            )
                        }
                    }
                }
                Spacer(Modifier.height(10.dp))
                Text(item.title, style = MaterialTheme.typography.titleSmall, color = LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                Spacer(Modifier.height(2.dp))
                Text(item.cardSubtitle(), style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 2, overflow = TextOverflow.Ellipsis, minLines = 2)
            }
        }
        LyraMenu(menu) { close ->
            if (item is Song) {
                SongMenuEntries(item, close)
            } else {
                MenuEntry("Abrir", Icons.AutoMirrored.Rounded.QueueMusic, { close(); onOpen() })
                if (onPlay != null) MenuEntry("Reproducir", Icons.Rounded.PlayArrow, { close(); onPlay() })
            }
        }
    }
}

/** Acceso rápido del Inicio: portada pequeña y nombre, en una pastilla ancha. */
@Composable
fun QuickTile(item: MusicItem, cover: @Composable (Modifier) -> Unit, onOpen: () -> Unit, onPlay: () -> Unit, modifier: Modifier = Modifier) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    HoverBox(onClick = onOpen, modifier = modifier.hoverable(interaction), shape = RoundedCornerShape(6.dp)) {
        Row(
            Modifier.fillMaxWidth().height(56.dp).background(if (hovered) LyraColors.SurfaceHigher else LyraColors.SurfaceHigh),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            cover(Modifier.size(56.dp))
            Text(
                item.title,
                style = MaterialTheme.typography.titleSmall,
                color = LyraColors.TextPrimary,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f).padding(horizontal = 12.dp),
            )
            AnimatedVisibility(hovered, enter = fadeIn(), exit = fadeOut()) {
                PlayButton(false, onPlay, size = 34.dp, modifier = Modifier.padding(end = 10.dp))
            }
        }
    }
}

/** Fila compacta de la biblioteca (barra de la izquierda). */
@Composable
fun LibraryRow(
    title: String,
    subtitle: String,
    cover: @Composable (Modifier) -> Unit,
    onClick: () -> Unit,
    selected: Boolean,
    playing: Boolean,
    modifier: Modifier = Modifier,
    menu: MenuState? = null,
    menuContent: (@Composable ColumnScope.(close: () -> Unit) -> Unit)? = null,
) {
    Box(modifier.then(if (menu != null) Modifier.contextMenu(menu) else Modifier)) {
        HoverBox(onClick = onClick, selected = selected || menu?.open == true) {
            Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp, vertical = 6.dp), verticalAlignment = Alignment.CenterVertically) {
                cover(Modifier.size(48.dp))
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(title, style = MaterialTheme.typography.bodyLarge, color = if (playing) LyraColors.Accent else LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(subtitle, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                if (playing) {
                    Spacer(Modifier.width(8.dp))
                    PlayingBars(true, Modifier.size(12.dp))
                }
            }
        }
        if (menu != null && menuContent != null) LyraMenu(menu, menuContent)
    }
}
