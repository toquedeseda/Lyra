package com.lyra.desktop.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.unit.dp
import com.lyra.desktop.ErrorLog
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraColors
import com.lyra.desktop.ui.OverlayDialog
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.ui.components.FilledPill
import com.lyra.desktop.ui.components.LyraTextField
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.SpotifyImporter
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.atomic.AtomicInteger

private sealed interface ImportStep {
    data object Waiting : ImportStep
    data object Loading : ImportStep
    data class Matching(val name: String, val done: Int, val total: Int) : ImportStep
    data class Done(val playlistId: String, val name: String, val found: Int, val missing: List<String>) : ImportStep
    data class Failed(val reason: String) : ImportStep
}

/**
 * Importar de Spotify sin cuenta (como en el móvil): lee la página pública de la playlist,
 * álbum o canción y busca cada canción en YouTube Music.
 */
@Composable
fun SpotifyImportDialog(initial: String) {
    val actions = LocalActions.current
    val app = actions.app
    var url by remember { mutableStateOf(initial) }
    var step by remember { mutableStateOf<ImportStep>(ImportStep.Waiting) }
    val focus = remember { FocusRequester() }
    val busy = step is ImportStep.Loading || step is ImportStep.Matching

    fun start() {
        val (kind, id) = SpotifyImporter.parseLink(url) ?: run {
            step = ImportStep.Failed("Eso no parece un enlace de Spotify")
            return
        }
        step = ImportStep.Loading
        actions.scope.launch {
            try {
                val collection = app.spotify.fetch(kind, id)
                step = ImportStep.Matching(collection.name, 0, collection.tracks.size)
                val done = AtomicInteger(0)
                val matches: List<Song?> = coroutineScope {
                    val semaphore = Semaphore(4)
                    collection.tracks.map { track ->
                        async {
                            semaphore.withPermit {
                                val song = runCatching { app.spotify.match(track) }.getOrNull()
                                step = ImportStep.Matching(collection.name, done.incrementAndGet(), collection.tracks.size)
                                song
                            }
                        }
                    }.awaitAll()
                }
                val found = matches.filterNotNull()
                val playlistId = app.library.createPlaylist(collection.name, found, remoteId = "spotify:$kind:$id", coverUrl = collection.cover)
                val missing = collection.tracks.zip(matches).filter { it.second == null }.map { (t, _) -> "${t.title} · ${t.artists.joinToString(", ")}" }
                step = ImportStep.Done(playlistId, collection.name, found.size, missing)
            } catch (e: Exception) {
                step = ImportStep.Failed(e.message ?: "No se pudo importar")
                ErrorLog.record("Importar de Spotify", e.message ?: "No se pudo importar", e, extra = url)
            }
        }
    }

    OverlayDialog(onDismiss = { if (!busy) actions.spotifyImport = null }, width = 480.dp) {
        Column {
            Text("Importar de Spotify", style = MaterialTheme.typography.headlineMedium, color = LyraColors.TextPrimary)
            Spacer(Modifier.height(8.dp))
            when (val s = step) {
                ImportStep.Waiting, is ImportStep.Failed -> {
                    Text(
                        "Pega el enlace de una playlist, álbum o canción de Spotify (Compartir → Copiar enlace). No hace falta cuenta.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = LyraColors.TextSecondary,
                    )
                    Spacer(Modifier.height(14.dp))
                    LyraTextField(url, { url = it }, "https://open.spotify.com/playlist/…", Modifier.fillMaxWidth(), focus, onSubmit = ::start)
                    if (s is ImportStep.Failed) {
                        Spacer(Modifier.height(8.dp))
                        Text(s.reason, style = MaterialTheme.typography.bodySmall, color = LyraColors.Like)
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End, verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { actions.spotifyImport = null }) { Text("Cancelar", color = LyraColors.TextSecondary) }
                        Spacer(Modifier.width(8.dp))
                        FilledPill("Importar", onClick = ::start, enabled = url.isNotBlank())
                    }
                    LaunchedEffect(Unit) { runCatching { focus.requestFocus() } }
                }
                ImportStep.Loading -> {
                    Text("Leyendo el enlace…", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(color = LyraColors.Accent, trackColor = LyraColors.SurfaceHigher, modifier = Modifier.fillMaxWidth())
                }
                is ImportStep.Matching -> {
                    Text("«${s.name}»: buscando en YouTube Music (${s.done} de ${s.total})", style = MaterialTheme.typography.bodyMedium, color = LyraColors.TextSecondary)
                    Spacer(Modifier.height(14.dp))
                    LinearProgressIndicator(
                        progress = { if (s.total == 0) 0f else s.done.toFloat() / s.total },
                        color = LyraColors.Accent,
                        trackColor = LyraColors.SurfaceHigher,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
                is ImportStep.Done -> {
                    Text(
                        "«${s.name}» importada: ${s.found} " + if (s.found == 1) "canción." else "canciones.",
                        style = MaterialTheme.typography.bodyLarge,
                        color = LyraColors.TextPrimary,
                    )
                    if (s.missing.isNotEmpty()) {
                        Spacer(Modifier.height(10.dp))
                        Text("No encontradas (${s.missing.size}):", style = MaterialTheme.typography.labelLarge, color = LyraColors.TextSecondary)
                        Column(Modifier.heightIn(max = 160.dp).verticalScroll(rememberScrollState())) {
                            s.missing.forEach { Text(it, style = MaterialTheme.typography.bodySmall, color = LyraColors.TextTertiary) }
                        }
                    }
                    Spacer(Modifier.height(20.dp))
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                        TextButton(onClick = { actions.spotifyImport = null }) { Text("Cerrar", color = LyraColors.TextSecondary) }
                        Spacer(Modifier.width(8.dp))
                        FilledPill("Abrir", onClick = {
                            actions.spotifyImport = null
                            actions.nav.navigate(Screen.LocalPlaylist(s.playlistId))
                        })
                    }
                }
            }
        }
    }
}
