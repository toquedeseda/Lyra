package com.lyra.music.ui.components

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.data.repo.ImportState
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.theme.LyraColors

/** Importar una playlist, álbum o canción de Spotify pegando su enlace. */
@UnstableApi
@Composable
fun SpotifyImportDialog(initialUrl: String, onDismiss: () -> Unit) {
    val actions = LocalActions.current
    val manager = actions.container.spotifyImport
    val state by manager.state.collectAsState()
    var url by remember { mutableStateOf(initialUrl) }

    LaunchedEffect(initialUrl) {
        if (initialUrl.isNotBlank() && state is ImportState.Idle) manager.start(initialUrl)
    }

    fun close() {
        manager.dismiss()
        onDismiss()
    }

    AlertDialog(
        onDismissRequest = ::close,
        containerColor = LyraColors.Surface,
        title = { Text("Importar de Spotify", style = MaterialTheme.typography.headlineMedium) },
        text = {
            Column {
                when (val s = state) {
                    ImportState.Idle, is ImportState.Failed -> {
                        Text(
                            "Pega el enlace de una playlist, álbum o canción de Spotify (Compartir → Copiar enlace). Cada canción se busca en YouTube Music.",
                            style = MaterialTheme.typography.bodyMedium,
                            color = LyraColors.TextSecondary,
                        )
                        Spacer(Modifier.height(14.dp))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            singleLine = true,
                            placeholder = { Text("https://open.spotify.com/playlist/…") },
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = LyraColors.Accent,
                                unfocusedBorderColor = LyraColors.Border,
                                cursorColor = LyraColors.Accent,
                            ),
                            modifier = Modifier.fillMaxWidth(),
                        )
                        if (s is ImportState.Failed) {
                            Text(s.reason, color = LyraColors.Like, style = MaterialTheme.typography.bodySmall, modifier = Modifier.padding(top = 8.dp))
                        }
                    }
                    is ImportState.Loading -> {
                        Text("Leyendo la lista de Spotify…", color = LyraColors.TextSecondary)
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)), color = LyraColors.Accent, trackColor = LyraColors.SurfaceHigher)
                    }
                    is ImportState.Matching -> {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text("Buscando ${s.done} de ${s.total}…", color = LyraColors.TextSecondary, style = MaterialTheme.typography.bodyMedium)
                        Spacer(Modifier.height(14.dp))
                        LinearProgressIndicator(
                            progress = { if (s.total > 0) s.done.toFloat() / s.total else 0f },
                            modifier = Modifier.fillMaxWidth().clip(RoundedCornerShape(2.dp)),
                            color = LyraColors.Accent,
                            trackColor = LyraColors.SurfaceHigher,
                        )
                        Text(
                            "Puedes cerrar esto: sigue en segundo plano.",
                            style = MaterialTheme.typography.bodySmall,
                            color = LyraColors.TextTertiary,
                            modifier = Modifier.padding(top = 10.dp),
                        )
                    }
                    is ImportState.Done -> {
                        Text(s.name, style = MaterialTheme.typography.titleMedium)
                        Text(
                            "${s.found} canciones importadas" + if (s.missing.isNotEmpty()) " · ${s.missing.size} no encontradas" else "",
                            color = LyraColors.TextSecondary,
                            style = MaterialTheme.typography.bodyMedium,
                        )
                        s.missing.take(5).forEach {
                            Text("· $it", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary, maxLines = 1)
                        }
                        if (s.missing.size > 5) {
                            Text("y ${s.missing.size - 5} más", style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary)
                        }
                    }
                }
            }
        },
        confirmButton = {
            when (val s = state) {
                ImportState.Idle, is ImportState.Failed -> TextButton(onClick = { manager.start(url.trim()) }, enabled = url.isNotBlank()) {
                    Text("Importar", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge)
                }
                is ImportState.Done -> TextButton(onClick = {
                    close()
                    actions.nav.navigate(LocalPlaylistRoute(s.playlistId))
                }) { Text("Abrir playlist", color = LyraColors.Accent, style = MaterialTheme.typography.labelLarge) }
                else -> TextButton(onClick = onDismiss) { Text("Seguir en segundo plano", color = LyraColors.Accent) }
            }
        },
        dismissButton = {
            if (state is ImportState.Idle || state is ImportState.Failed) {
                TextButton(onClick = ::close) { Text("Cancelar", color = LyraColors.TextSecondary) }
            }
        },
    )
}
