package com.lyra.music.ui.components

import androidx.compose.foundation.background
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
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowCircleDown
import androidx.compose.material.icons.rounded.CheckCircle
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.model.Song
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.LocalLibraryState
import com.lyra.music.ui.theme.LyraColors

/** Barra superior transparente con botón atrás (encima del contenido). */
@UnstableApi
@Composable
fun BackBar(title: String? = null, trailing: @Composable RowScope.() -> Unit = {}) {
    val actions = LocalActions.current
    Row(
        Modifier
            .fillMaxWidth()
            .statusBarsPadding()
            .padding(horizontal = 4.dp, vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconButton(onClick = { actions.nav.popBackStack() }) {
            Icon(Icons.AutoMirrored.Rounded.ArrowBack, "Atrás")
        }
        if (title != null) {
            Text(title, style = MaterialTheme.typography.titleLarge, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f))
        } else {
            Spacer(Modifier.weight(1f))
        }
        trailing()
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
    actionsRow: @Composable RowScope.() -> Unit = {},
) {
    Column(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(Color(0xFF3A3A3A), LyraColors.Background))),
    ) {
        BackBar()
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            cover(Modifier.size(236.dp))
        }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 20.dp)) {
            Text(title, style = MaterialTheme.typography.headlineMedium, maxLines = 3, overflow = TextOverflow.Ellipsis)
            if (!subtitle.isNullOrBlank()) {
                Text(
                    subtitle,
                    style = MaterialTheme.typography.titleSmall,
                    modifier = Modifier
                        .padding(top = 8.dp)
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
                .padding(start = 4.dp, end = 16.dp, top = 4.dp, bottom = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            actionsRow()
            Spacer(Modifier.weight(1f))
            ShuffleIconButton(onShuffle)
            Spacer(Modifier.width(8.dp))
            PlayCircleButton(onPlay)
        }
    }
}

/** Descargar todo: muestra si ya está todo, parte o nada descargado. */
@UnstableApi
@Composable
fun DownloadAllButton(songs: List<Song>) {
    val actions = LocalActions.current
    val downloads = LocalLibraryState.current.downloads
    val states = songs.map { downloads[it.id]?.state }
    val completed = states.count { it == DownloadState.COMPLETED }
    val pending = states.count { it == DownloadState.QUEUED || it == DownloadState.DOWNLOADING }
    IconButton(onClick = {
        when {
            songs.isEmpty() -> Unit
            completed == songs.size -> actions.message("Ya está todo descargado")
            else -> actions.download(songs.filter { downloads[it.id]?.state != DownloadState.COMPLETED })
        }
    }) {
        when {
            songs.isNotEmpty() && completed == songs.size ->
                Icon(Icons.Rounded.CheckCircle, "Descargado", tint = Color.White, modifier = Modifier.size(28.dp))
            pending > 0 -> CircularProgressIndicator(
                progress = { completed.toFloat() / songs.size },
                modifier = Modifier.size(24.dp),
                color = Color.White,
                trackColor = LyraColors.SurfaceHigher,
                strokeWidth = 3.dp,
            )
            else -> Icon(Icons.Rounded.ArrowCircleDown, "Descargar todo", tint = LyraColors.TextSecondary, modifier = Modifier.size(28.dp))
        }
    }
}

fun totalDurationText(songs: List<Song>): String {
    val totalMinutes = songs.sumOf { it.durationMs ?: 0L } / 60_000
    val hours = totalMinutes / 60
    val minutes = totalMinutes % 60
    val duration = if (hours > 0) "$hours h $minutes min" else "$minutes min"
    return "${songs.size} canciones • $duration"
}
