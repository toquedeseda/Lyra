package com.lyra.desktop.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AddCircleOutline
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material.icons.rounded.Link
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import coil3.compose.AsyncImage
import com.lyra.desktop.player.PlayContext
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LinkText
import com.lyra.desktop.ui.components.LoadingView
import com.lyra.desktop.ui.components.LocalNowPlaying
import com.lyra.desktop.ui.components.OutlinePill
import com.lyra.desktop.ui.components.PlayButton
import com.lyra.desktop.ui.components.SectionHeader
import com.lyra.desktop.ui.components.SongRow
import com.lyra.desktop.ui.components.art
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.PlaylistItem

// ---------------------------------------------------------------------- álbum

@Composable
fun AlbumScreen(id: String, preview: AlbumItem?) {
    val actions = LocalActions.current
    val app = actions.app
    val (load, retry) = rememberLoad("album:$id") { app.music.album(id) }
    val data by app.library.data.collectAsState()
    val now = LocalNowPlaying.current
    val playerState by app.player.state.collectAsState()
    ScreenList {
        val page = (load as? Load.Ready)?.value
        val album = page?.album ?: preview
        item {
            CollectionHeader(
                type = album?.kind ?: "Álbum",
                title = album?.title ?: "",
                cover = { Cover(art(album?.thumbnailUrl, 600), it, RoundedCornerShape(8.dp)) },
                description = page?.description,
            ) {
                album?.artists?.forEachIndexed { index, artist ->
                    if (index > 0) MetaText(", ")
                    if (artist.id != null) {
                        LinkText(artist.name, onClick = { actions.nav.navigate(Screen.Artist(artist.id!!, ArtistItem(artist.id!!, artist.name))) }, color = LyraColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
                    } else {
                        MetaText(artist.name, strong = true)
                    }
                }
                album?.year?.let { MetaDot(); MetaText(it) }
                if (page != null) {
                    MetaDot()
                    MetaText(songCount(page.songs.size))
                    totalDuration(page.songs).takeIf { it.isNotEmpty() }?.let { MetaText(", $it") }
                }
            }
        }
        when (load) {
            is Load.Loading -> item { LoadingView() }
            is Load.Failed -> item { ErrorView(load.message, retry) }
            is Load.Ready -> {
                val loaded = load.value
                val context = PlayContext(loaded.album.title, loaded.album.id)
                val playingHere = playerState.context?.id == loaded.album.id
                item {
                    ActionBar {
                        PlayButton(playingHere && now.playing, onClick = {
                            if (playingHere) app.player.togglePlay() else actions.play(loaded.songs, 0, context, loaded.album)
                        }, size = 56.dp)
                        IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(loaded.songs, context, loaded.album) }, size = 44.dp, iconSize = 26.dp)
                        val saved = data.albums.any { it.album.id == loaded.album.id }
                        IconBtn(
                            if (saved) Icons.Rounded.CheckCircle else Icons.Rounded.AddCircleOutline,
                            if (saved) "Quitar de tu biblioteca" else "Guardar en tu biblioteca",
                            onClick = {
                                app.library.setAlbumSaved(loaded.album, !saved)
                                actions.message(if (saved) "Quitado de tu biblioteca" else "Guardado en tu biblioteca")
                            },
                            size = 44.dp,
                            iconSize = 28.dp,
                            active = saved,
                        )
                        IconBtn(Icons.Rounded.ArrowDownward, "Descargar el álbum", onClick = { actions.download(loaded.songs) }, size = 44.dp, iconSize = 24.dp)
                    }
                }
                item { SongTableHeader(showAlbum = false, showCover = false) }
                songItems(loaded.songs, "album", showAlbum = false, showCover = false, onPlay = { index -> actions.play(loaded.songs, index, context, loaded.album) })
                items(loaded.sections, key = { "sec-" + it.title }) { section -> SectionView(section) }
            }
        }
    }
}

// ---------------------------------------------------------------------- artista

@Composable
fun ArtistScreen(id: String, preview: ArtistItem?) {
    val actions = LocalActions.current
    val app = actions.app
    val (load, retry) = rememberLoad("artist:$id") { app.music.artist(id) }
    val data by app.library.data.collectAsState()
    val now = LocalNowPlaying.current
    val playerState by app.player.state.collectAsState()
    var showAll by rememberSaveable { mutableStateOf(false) }
    ScreenList {
        val page = (load as? Load.Ready)?.value
        val artist = page?.artist ?: preview
        item {
            // Cabecera con la foto grande del artista y su nombre encima.
            Box(Modifier.fillMaxWidth().height(320.dp).background(LyraColors.SurfaceHigher)) {
                val banner = page?.bannerUrl ?: artist?.thumbnailUrl
                if (banner != null) {
                    AsyncImage(art(banner, 1440), null, contentScale = ContentScale.Crop, modifier = Modifier.fillMaxSize())
                }
                Box(Modifier.fillMaxSize().background(Brush.verticalGradient(listOf(Color.Transparent, Color(0x33000000), LyraColors.Surface))))
                Column(Modifier.align(Alignment.BottomStart).padding(start = PagePadding, end = PagePadding, bottom = 22.dp)) {
                    Text(
                        artist?.title ?: "",
                        style = MaterialTheme.typography.displayLarge.copy(fontSize = if ((artist?.title?.length ?: 0) > 22) 56.sp else 84.sp, lineHeight = 86.sp),
                        color = LyraColors.TextPrimary,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                    )
                    page?.listeners?.let { Text(it, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextPrimary) }
                }
            }
        }
        when (load) {
            is Load.Loading -> item { LoadingView() }
            is Load.Failed -> item { ErrorView(load.message, retry) }
            is Load.Ready -> {
                val loaded = load.value
                val context = PlayContext(loaded.artist.title, loaded.artist.id)
                val playingHere = playerState.context?.id == loaded.artist.id
                item {
                    ActionBar {
                        PlayButton(playingHere && now.playing, onClick = {
                            if (playingHere) app.player.togglePlay() else actions.play(loaded.topSongs, 0, context, loaded.artist)
                        }, size = 56.dp)
                        IconBtn(Icons.Rounded.Shuffle, "Aleatorio", onClick = { actions.shuffle(loaded.topSongs, context, loaded.artist) }, size = 44.dp, iconSize = 26.dp)
                        val following = data.artists.any { it.artist.id == loaded.artist.id }
                        OutlinePill(if (following) "Siguiendo" else "Seguir", onClick = {
                            app.library.setFollowing(loaded.artist, !following)
                        }, selected = following)
                        loaded.radioPlaylistId?.let { radio ->
                            IconBtn(Icons.Rounded.Radio, "Radio del artista", onClick = { app.player.startPlaylistRadio(radio, "Radio de ${loaded.artist.title}") }, size = 44.dp, iconSize = 24.dp)
                        }
                        IconBtn(Icons.Rounded.Link, "Copiar enlace", onClick = {
                            actions.copy(if (loaded.artist.id.startsWith("sc:")) "https://soundcloud.com/" + loaded.artist.id.removePrefix("sc:") else "https://music.youtube.com/channel/" + loaded.artist.id.removePrefix("yt:"))
                            actions.message("Enlace copiado")
                        }, size = 44.dp, iconSize = 22.dp)
                    }
                }
                if (loaded.topSongs.isNotEmpty()) {
                    item { SectionHeader("Populares", Modifier.padding(horizontal = PagePadding)) }
                    val visible = if (showAll) loaded.topSongs else loaded.topSongs.take(5)
                    items(visible.size, key = { "top-${visible[it].id}" }) { index ->
                        SongRow(
                            visible[index],
                            onPlay = { actions.play(loaded.topSongs, index, context, loaded.artist) },
                            modifier = Modifier.padding(horizontal = PagePadding - 12.dp),
                            index = index + 1,
                            showAlbum = true,
                        )
                    }
                    item {
                        Row(Modifier.padding(horizontal = PagePadding, vertical = 8.dp)) {
                            val more = loaded.topSongsMore
                            when {
                                loaded.topSongs.size > 5 -> LinkText(if (showAll) "Ver menos" else "Ver más", onClick = { showAll = !showAll })
                                more != null -> LinkText("Ver todas las canciones", onClick = { actions.nav.navigate(Screen.RemotePlaylist("yt:" + more.browseId, PlaylistItem("yt:" + more.browseId, "Canciones de ${loaded.artist.title}"))) })
                            }
                            if (loaded.topSongs.size > 5 && more != null) {
                                Spacer(Modifier.width(18.dp))
                                LinkText("Ver todas las canciones", onClick = { actions.nav.navigate(Screen.RemotePlaylist("yt:" + more.browseId, PlaylistItem("yt:" + more.browseId, "Canciones de ${loaded.artist.title}"))) })
                            }
                        }
                    }
                }
                items(loaded.sections, key = { "sec-" + it.title }) { section -> SectionView(section) }
                loaded.description?.let { description ->
                    item {
                        Column(Modifier.padding(horizontal = PagePadding, vertical = 20.dp)) {
                            SectionHeader("Información")
                            Row {
                                Cover(art(loaded.artist.thumbnailUrl), Modifier.width(140.dp).height(140.dp), CircleShape)
                                Spacer(Modifier.width(20.dp))
                                Text(description, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, maxLines = 8, overflow = TextOverflow.Ellipsis)
                            }
                        }
                    }
                }
            }
        }
    }
}
