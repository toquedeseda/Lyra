package com.lyra.desktop.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.window.WindowDraggableArea
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.OpenInFull
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.WindowScope
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LocalNowPlaying
import com.lyra.desktop.ui.components.NowPlaying
import com.lyra.desktop.ui.components.PlayButton
import com.lyra.desktop.ui.components.art

/**
 * Mini reproductor: una ventanita siempre encima de todo, con la portada, la canción y los
 * botones, para usar Lyra mientras haces otras cosas (como la isla del móvil). Se arrastra
 * desde cualquier sitio.
 */
@Composable
fun WindowScope.MiniPlayerContent(actions: LyraActions, onClose: () -> Unit, onExpand: () -> Unit) {
    val app = actions.app
    val state by app.player.state.collectAsState()
    val liked by app.library.likedIds.collectAsState()
    val song = state.current?.song
    CompositionLocalProvider(LocalActions provides actions, LocalNowPlaying provides NowPlaying(song?.id, state.isPlaying)) {
        LyraTheme {
            WindowDraggableArea {
                Box(
                    Modifier
                        .fillMaxSize()
                        .clip(RoundedCornerShape(14.dp))
                        .background(LyraColors.SurfaceHigh)
                        .border(1.dp, LyraColors.Border, RoundedCornerShape(14.dp)),
                ) {
                    Column(Modifier.fillMaxSize()) {
                        Row(Modifier.weight(1f).padding(10.dp), verticalAlignment = Alignment.CenterVertically) {
                            Cover(art(song?.thumbnailUrl), Modifier.size(76.dp), RoundedCornerShape(8.dp))
                            Spacer(Modifier.width(12.dp))
                            Column(Modifier.weight(1f)) {
                                Text(song?.title ?: "Nada sonando", style = MaterialTheme.typography.titleSmall, color = LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Text(song?.artistsText.orEmpty(), style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                                Spacer(Modifier.height(4.dp))
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    IconBtn(Icons.Rounded.SkipPrevious, "Anterior", onClick = { app.player.previous() }, size = 32.dp, iconSize = 22.dp, tint = LyraColors.TextPrimary)
                                    PlayButton(state.isPlaying, onClick = { app.player.togglePlay() }, size = 34.dp)
                                    IconBtn(Icons.Rounded.SkipNext, "Siguiente", onClick = { app.player.next() }, size = 32.dp, iconSize = 22.dp, tint = LyraColors.TextPrimary)
                                    if (song != null) {
                                        val isLiked = song.id in liked
                                        IconBtn(
                                            if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                                            if (isLiked) "Quitar de Me gusta" else "Añadir a Me gusta",
                                            onClick = { app.library.toggleLike(song) },
                                            size = 32.dp,
                                            iconSize = 18.dp,
                                            tint = if (isLiked) LyraColors.Like else LyraColors.TextSecondary,
                                        )
                                    }
                                }
                            }
                            Column {
                                IconBtn(Icons.Rounded.Close, "Cerrar", onClick = onClose, size = 28.dp, iconSize = 16.dp)
                                IconBtn(Icons.Rounded.OpenInFull, "Abrir Lyra", onClick = onExpand, size = 28.dp, iconSize = 15.dp)
                            }
                        }
                        // Progreso: una línea finísima abajo.
                        val (position, duration) = rememberProgress()
                        Box(Modifier.fillMaxWidth().height(3.dp).background(LyraColors.SurfaceHigher)) {
                            Box(Modifier.fillMaxWidth(if (duration > 0) (position.toFloat() / duration).coerceIn(0f, 1f) else 0f).height(3.dp).background(LyraColors.Accent))
                        }
                    }
                }
            }
        }
    }
}
