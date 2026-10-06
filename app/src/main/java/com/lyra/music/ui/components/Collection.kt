package com.lyra.music.ui.components

import android.os.Build
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.animation.togetherWith
import androidx.compose.animation.scaleOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.fadeIn
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.spring
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.AnimatedContent
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import coil3.compose.AsyncImage
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.model.Song
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.theme.LyraColors

/** Fila superior con botón atrás (título pequeño opcional). */
@UnstableApi
@Composable
fun BackBar(title: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val actions = LocalActions.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 8.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás", tint = LyraColors.TextPrimary)
        }
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing()
    }
}

/** Cabecera de página con título grande en serif, como en la web ("Tu biblioteca"). */
@UnstableApi
@Composable
fun PageHeader(
    title: String,
    subtitle: String? = null,
    showBack: Boolean = true,
    trailing: @Composable RowScope.() -> Unit = {},
) {
    Column(Modifier.fillMaxWidth()) {
        if (showBack) BackBar(trailing = trailing) else Spacer(Modifier.statusBarsPadding())
        Column(Modifier.padding(start = 20.dp, end = 20.dp, top = if (showBack) 4.dp else 20.dp, bottom = 8.dp)) {
            Text(title, style = MaterialTheme.typography.displaySmall)
            if (subtitle != null) {
                Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary, modifier = Modifier.padding(top = 6.dp))
            }
        }
    }
}

/** Portada muy difuminada de fondo que se funde en el negro. */
@Composable
fun AmbientBackdrop(model: Any?, modifier: Modifier = Modifier, strength: Float = 0.55f) {
    Box(modifier) {
        if (model != null) {
            AsyncImage(
                model = model,
                contentDescription = null,
                contentScale = ContentScale.Crop,
                modifier = Modifier
                    .matchParentSize()
                    .blur(90.dp)
                    // Sin desenfoque (Android < 12) se oscurece más para que no moleste.
                    .alpha(if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) strength else strength * 0.4f),
            )
        }
        Box(
            Modifier
                .matchParentSize()
                .background(
                    Brush.verticalGradient(
                        0f to LyraColors.Background.copy(alpha = 0.25f),
                        0.6f to LyraColors.Background.copy(alpha = 0.75f),
                        1f to LyraColors.Background,
                    ),
                ),
        )
    }
}

@UnstableApi
@Composable
fun CollectionHeader(
    title: String,
    subtitle: String?,
    meta: String?,
    cover: @Composable (Modifier) -> Unit,
    onPlay: () -> Unit,
    onShuffle: () -> Unit,
    onSubtitleClick: (() -> Unit)? = null,
    description: String? = null,
    eyebrow: String? = null,
    backdrop: Any? = null,
    /** Id de la lista en el reproductor: si es la que suena, play hace de pausa y el aleatorio se enciende sin reiniciar. */
    contextId: String? = null,
    actionsRow: @Composable RowScope.() -> Unit = {},
) {
    Box(Modifier.fillMaxWidth()) {
        AmbientBackdrop(backdrop, Modifier.matchParentSize())
        Column(Modifier.fillMaxWidth()) {
            BackBar()
            Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
                cover(
                    Modifier
                        .size(232.dp)
                        .shadow(28.dp, RoundedCornerShape(16.dp)),
                )
            }
            Column(Modifier.padding(start = 20.dp, end = 20.dp, top = 24.dp)) {
                if (!eyebrow.isNullOrBlank()) Eyebrow(eyebrow, Modifier.padding(bottom = 8.dp))
                Text(title, style = MaterialTheme.typography.displaySmall, maxLines = 3, overflow = TextOverflow.Ellipsis)
                if (!subtitle.isNullOrBlank()) {
                    Text(
                        subtitle,
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier
                            .padding(top = 10.dp)
                            .then(if (onSubtitleClick != null) Modifier.clickable(onClick = onSubtitleClick) else Modifier),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                }
                if (!meta.isNullOrBlank()) {
                    Text(meta, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextSecondary, modifier = Modifier.padding(top = 4.dp))
                }
                if (!description.isNullOrBlank()) {
                    Text(
                        description,
                        style = MaterialTheme.typography.bodySmall,
                        color = LyraColors.TextSecondary,
                        maxLines = 3,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.padding(top = 8.dp),
                    )
                }
            }
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(start = 20.dp, end = 20.dp, top = 16.dp, bottom = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                actionsRow()
                Spacer(Modifier.weight(1f))
                ContextPlayControls(contextId, onPlay, onShuffle)
            }
        }
    }
}

/** Descargar todo: muestra si ya está todo, parte o nada descargado. */
@UnstableApi
@Composable
fun DownloadAllButton(songs: List<Song>, collectionTitle: String? = null) {
    val actions = LocalActions.current
    val downloads = LocalLibraryState.current.downloads
    val states = songs.map { downloads[it.id]?.state }
    val completed = states.count { it == DownloadState.COMPLETED }
    val pending = states.count { it == DownloadState.QUEUED || it == DownloadState.DOWNLOADING }
    val done = songs.isNotEmpty() && completed == songs.size
    val fill by animateColorAsState(if (done) LyraColors.Accent else Color.Transparent, tween(300), label = "descargado")
    Box(
        Modifier
            .padding(end = 10.dp)
            .size(40.dp)
            .clip(CircleShape)
            .background(fill)
            .border(1.dp, if (done) Color.Transparent else LyraColors.Border, CircleShape)
            .pressable(pressedScale = 0.9f) {
                when {
                    songs.isEmpty() -> Unit
                    done -> actions.message("Ya está todo descargado")
                    // Se pasan todas: las ya descargadas se saltan, y la .m3u8 las incluye a todas.
                    else -> actions.download(songs, collectionTitle)
                }
            },
        contentAlignment = Alignment.Center,
    ) {
        // La flecha da paso al progreso y, al acabar, la marca aparece con un rebote.
        val phase = when {
            done -> 2
            pending > 0 -> 1
            else -> 0
        }
        AnimatedContent(
            targetState = phase,
            transitionSpec = {
                (scaleIn(initialScale = 0.3f, animationSpec = spring(dampingRatio = 0.42f)) + fadeIn()) togetherWith
                    (scaleOut(targetScale = 0.3f) + fadeOut())
            },
            label = "descargar todo",
        ) { current ->
            when (current) {
                2 -> Icon(Icons.Rounded.Check, "Descargado", tint = LyraColors.OnAccent, modifier = Modifier.size(20.dp))
                1 -> CircularProgressIndicator(
                    progress = { if (songs.isEmpty()) 0f else completed.toFloat() / songs.size },
                    modifier = Modifier.size(22.dp),
                    color = LyraColors.Accent,
                    trackColor = LyraColors.SurfaceHigher,
                    strokeWidth = 2.5.dp,
                )
                else -> Icon(Icons.Rounded.ArrowDownward, "Descargar todo", tint = LyraColors.TextPrimary, modifier = Modifier.size(20.dp))
            }
        }
    }
}

fun totalDurationText(songs: List<Song>): String {
    val totalMinutes = songs.sumOf { it.durationMs ?: 0L } / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val duration = if (hours > 0) "$hours h $minutes min" else "$minutes min"
    return "${songs.size} canciones · $duration"
}

/**
 * Aleatorio y play de las cabeceras. Si esta lista es la que suena, play hace de pausa
 * y el aleatorio se enciende o apaga sin volver a empezar; si no, la ponen (en orden o
 * barajada) y el botón se enciende con su animación.
 */
@UnstableApi
@Composable
fun ContextPlayControls(contextId: String?, onPlay: () -> Unit, onShuffle: () -> Unit) {
    val player = LocalActions.current.container.player
    val state by player.state.collectAsState()
    val current = contextId != null && state.song != null && state.contextId == contextId
    ShuffleToggle(
        active = current && state.shuffle,
        onClick = { if (current) player.setShuffle(!state.shuffle) else onShuffle() },
    )
    Spacer(Modifier.width(4.dp))
    PlayCircleButton(
        onClick = { if (current) player.togglePlay() else onPlay() },
        playing = current && state.isPlaying,
    )
}
