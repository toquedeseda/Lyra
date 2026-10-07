package com.lyra.desktop.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.QueueMusic
import androidx.compose.material.icons.automirrored.rounded.VolumeDown
import androidx.compose.material.icons.automirrored.rounded.VolumeOff
import androidx.compose.material.icons.automirrored.rounded.VolumeUp
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.FavoriteBorder
import androidx.compose.material.icons.rounded.Lyrics
import androidx.compose.material.icons.rounded.PictureInPictureAlt
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material.icons.rounded.RepeatOne
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.material.icons.rounded.SkipNext
import androidx.compose.material.icons.rounded.SkipPrevious
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.lyra.desktop.data.RepeatMode
import com.lyra.desktop.data.RightPanel
import com.lyra.desktop.ui.components.Cover
import com.lyra.desktop.ui.components.HoverBox
import com.lyra.desktop.ui.components.IconBtn
import com.lyra.desktop.ui.components.LinkText
import com.lyra.desktop.ui.components.PlayButton
import com.lyra.desktop.ui.components.ThinSlider
import com.lyra.desktop.ui.components.formatDuration
import com.lyra.music.data.model.ArtistItem
import kotlinx.coroutines.delay

/** Posición y duración de lo que suena, actualizadas cuatro veces por segundo. */
@Composable
fun rememberProgress(): Pair<Long, Long> {
    val app = LocalActions.current.app
    val value by produceState(0L to 0L) {
        while (true) {
            value = app.player.positionMs to app.player.durationMs
            delay(250)
        }
    }
    return value
}

/** Si está esperando al audio (el circulito del botón). Se mira solo: no avisa al cambiar. */
@Composable
private fun rememberBuffering(): Boolean {
    val app = LocalActions.current.app
    val value by produceState(false) {
        while (true) {
            value = app.player.buffering
            delay(150)
        }
    }
    return value
}

/** La barra de abajo: lo que suena, los controles con el progreso y el volumen. */
@Composable
fun PlayerBar(onToggleMini: () -> Unit, modifier: Modifier = Modifier) {
    val actions = LocalActions.current
    val app = actions.app
    val state by app.player.state.collectAsState()
    val settings by app.settings.flow.collectAsState()
    val liked by app.library.likedIds.collectAsState()
    val current = state.current?.song
    val buffering = rememberBuffering()
    Row(modifier.padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
        // Izquierda: portada, título, artistas y corazón.
        Row(Modifier.weight(0.3f), verticalAlignment = Alignment.CenterVertically) {
            if (current != null) {
                HoverBox(onClick = {
                    app.settings.update { it.copy(rightPanel = if (it.rightPanel == RightPanel.NOW_PLAYING) RightPanel.NONE else RightPanel.NOW_PLAYING) }
                }, shape = RoundedCornerShape(6.dp)) {
                    Cover(current.thumbnailUrl, Modifier.size(56.dp), RoundedCornerShape(6.dp))
                }
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f, fill = false).widthIn(max = 260.dp)) {
                    AnimatedContent(current.id, transitionSpec = { fadeIn() togetherWith fadeOut() }) {
                        Column {
                            if (current.album?.id != null) {
                                LinkText(current.title, onClick = { actions.goToAlbum(current) }, color = LyraColors.TextPrimary, style = MaterialTheme.typography.titleSmall)
                            } else {
                                Text(current.title, style = MaterialTheme.typography.titleSmall, color = LyraColors.TextPrimary, maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            Row {
                                current.artists.forEachIndexed { index, artist ->
                                    if (index > 0) Text(", ", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary)
                                    if (artist.id != null) {
                                        LinkText(artist.name, onClick = { actions.nav.navigate(Screen.Artist(artist.id!!, ArtistItem(artist.id!!, artist.name))) }, style = MaterialTheme.typography.bodySmall)
                                    } else {
                                        Text(artist.name, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, maxLines = 1)
                                    }
                                }
                            }
                        }
                    }
                }
                Spacer(Modifier.width(8.dp))
                val isLiked = current.id in liked
                IconBtn(
                    if (isLiked) Icons.Rounded.Favorite else Icons.Rounded.FavoriteBorder,
                    if (isLiked) "Quitar de Me gusta" else "Añadir a Me gusta",
                    onClick = { actions.toggleLike(current) },
                    tint = if (isLiked) LyraColors.Like else LyraColors.TextSecondary,
                    hoverTint = if (isLiked) LyraColors.Like else LyraColors.TextPrimary,
                )
            }
        }

        // Centro: controles y progreso.
        Column(Modifier.weight(0.4f).widthIn(max = 720.dp), horizontalAlignment = Alignment.CenterHorizontally) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                IconBtn(Icons.Rounded.Shuffle, if (state.shuffle) "Quitar aleatorio" else "Aleatorio", onClick = { app.player.setShuffle(!state.shuffle) }, active = state.shuffle, enabled = current != null)
                IconBtn(Icons.Rounded.SkipPrevious, "Anterior", onClick = { app.player.previous() }, iconSize = 26.dp, enabled = current != null, tint = LyraColors.TextPrimary)
                Box(contentAlignment = Alignment.Center) {
                    PlayButton(state.isPlaying, onClick = { app.player.togglePlay() }, size = 38.dp)
                    if (state.isPlaying && buffering) {
                        CircularProgressIndicator(color = LyraColors.OnAccent, strokeWidth = 2.dp, modifier = Modifier.size(38.dp))
                    }
                }
                IconBtn(Icons.Rounded.SkipNext, "Siguiente", onClick = { app.player.next() }, iconSize = 26.dp, enabled = current != null, tint = LyraColors.TextPrimary)
                IconBtn(
                    if (state.repeat == RepeatMode.ONE) Icons.Rounded.RepeatOne else Icons.Rounded.Repeat,
                    when (state.repeat) {
                        RepeatMode.OFF -> "Repetir"
                        RepeatMode.ALL -> "Repetir una"
                        RepeatMode.ONE -> "No repetir"
                    },
                    onClick = { app.player.cycleRepeat() },
                    active = state.repeat != RepeatMode.OFF,
                    enabled = current != null,
                )
            }
            ProgressRow(enabled = current != null)
        }

        // Derecha: letra, cola, mini reproductor y volumen.
        Row(Modifier.weight(0.3f), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
            IconBtn(Icons.Rounded.Lyrics, "Letra", onClick = {
                app.settings.update { it.copy(rightPanel = if (it.rightPanel == RightPanel.LYRICS) RightPanel.NONE else RightPanel.LYRICS) }
            }, active = settings.rightPanel == RightPanel.LYRICS)
            IconBtn(Icons.AutoMirrored.Rounded.QueueMusic, "Cola", onClick = {
                app.settings.update { it.copy(rightPanel = if (it.rightPanel == RightPanel.QUEUE) RightPanel.NONE else RightPanel.QUEUE) }
            }, active = settings.rightPanel == RightPanel.QUEUE)
            IconBtn(Icons.Rounded.PictureInPictureAlt, "Mini reproductor", onClick = onToggleMini)
            Spacer(Modifier.width(4.dp))
            VolumeControl()
        }
    }
}

@Composable
private fun ProgressRow(enabled: Boolean) {
    val app = LocalActions.current.app
    val (position, duration) = rememberProgress()
    var seeking by remember { mutableStateOf(false) }
    var seekValue by remember { mutableFloatStateOf(0f) }
    val fraction = if (duration > 0) position.toFloat() / duration else 0f
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth().padding(top = 2.dp)) {
        Text(
            formatDuration(if (seeking) (seekValue * duration).toLong() else position).ifEmpty { "0:00" },
            style = MaterialTheme.typography.labelMedium,
            color = LyraColors.TextSecondary,
            modifier = Modifier.width(44.dp),
            textAlign = androidx.compose.ui.text.style.TextAlign.End,
        )
        Spacer(Modifier.width(8.dp))
        ThinSlider(
            value = if (seeking) seekValue else fraction,
            onChange = {
                seeking = true
                seekValue = it
            },
            onCommit = {
                seeking = false
                if (duration > 0) app.player.seek((it * duration).toLong())
            },
            enabled = enabled && duration > 0,
            modifier = Modifier.weight(1f),
        )
        Spacer(Modifier.width(8.dp))
        Text(formatDuration(duration).ifEmpty { "0:00" }, style = MaterialTheme.typography.labelMedium, color = LyraColors.TextSecondary, modifier = Modifier.width(44.dp))
    }
}

@Composable
private fun VolumeControl() {
    val app = LocalActions.current.app
    val settings by app.settings.flow.collectAsState()
    val volume = if (settings.muted) 0f else settings.volume
    IconBtn(
        when {
            volume <= 0f -> Icons.AutoMirrored.Rounded.VolumeOff
            volume < 0.5f -> Icons.AutoMirrored.Rounded.VolumeDown
            else -> Icons.AutoMirrored.Rounded.VolumeUp
        },
        if (settings.muted) "Activar sonido" else "Silenciar",
        onClick = { app.settings.update { it.copy(muted = !it.muted) } },
    )
    ThinSlider(
        value = volume,
        onChange = { v -> app.settings.update { it.copy(volume = v, muted = false) } },
        wheelStep = 0.05f,
        modifier = Modifier.width(110.dp).fillMaxHeight(0.5f),
    )
}
