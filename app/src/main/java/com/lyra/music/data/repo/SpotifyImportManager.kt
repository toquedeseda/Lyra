package com.lyra.music.data.repo

import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.innertube.SearchFilter
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

/** Estado de una importación de Spotify (se muestra en un diálogo con progreso). */
sealed interface ImportState {
    data object Idle : ImportState
    data class Loading(val url: String) : ImportState
    data class Matching(val name: String, val done: Int, val total: Int) : ImportState
    data class Done(val playlistId: Long, val name: String, val found: Int, val missing: List<String>) : ImportState
    data class Failed(val reason: String) : ImportState
}

/** Lleva la importación en segundo plano (sigue aunque cierres el diálogo). */
class SpotifyImportManager(
    private val importer: SpotifyImporter,
    private val library: LibraryRepository,
    private val scope: CoroutineScope,
    private val filesDir: File,
) {
    private val _state = MutableStateFlow<ImportState>(ImportState.Idle)
    val state: StateFlow<ImportState> = _state.asStateFlow()

    private val json = Json { ignoreUnknownKeys = true }
    private val mapSerializer = MapSerializer(String.serializer(), String.serializer())

    fun isSpotifyLink(text: String) = SpotifyImporter.parseLink(text) != null

    fun dismiss() {
        if (_state.value is ImportState.Done || _state.value is ImportState.Failed) _state.value = ImportState.Idle
    }

    fun start(url: String) {
        val (kind, id) = SpotifyImporter.parseLink(url) ?: run {
            _state.value = ImportState.Failed("Eso no parece un enlace de Spotify")
            return
        }
        _state.value = ImportState.Loading(url)
        scope.launch {
            try {
                val collection = importer.fetch(kind, id)
                _state.value = ImportState.Matching(collection.name, 0, collection.tracks.size)
                val matches = matchAll(collection.tracks) { done ->
                    _state.value = ImportState.Matching(collection.name, done, collection.tracks.size)
                }
                val found = collection.tracks.zip(matches).filter { it.second != null }
                val remoteId = "spotify:$kind:$id"
                val playlistId = library.createPlaylist(collection.name, found.mapNotNull { it.second }, remoteId, collection.cover)
                // Las playlists se mantienen sincronizadas; los álbumes y canciones sueltas no cambian.
                if (kind == "playlist") library.setPlaylistSync(playlistId, sync = true, autoDownload = false)
                saveMap(playlistId, found.associate { it.first.uri to it.second!!.id })
                val missing = collection.tracks.zip(matches).filter { it.second == null }
                    .map { (t, _) -> "${t.title} · ${t.artists.joinToString(", ")}" }
                _state.value = ImportState.Done(playlistId, collection.name, found.size, missing)
            } catch (e: Exception) {
                _state.value = ImportState.Failed(e.message ?: "No se pudo importar")
                com.lyra.music.core.ErrorLog.record("Importar de Spotify", e.message ?: "No se pudo importar", e, extra = url)
            }
        }
    }

    /** Busca varias a la vez (4) pero conserva el orden de la lista. */
    suspend fun matchAll(tracks: List<SpotifyImporter.Track>, onProgress: (Int) -> Unit): List<Song?> = coroutineScope {
        val semaphore = Semaphore(4)
        var done = 0
        tracks.map { track ->
            async {
                semaphore.withPermit {
                    val song = importer.match(track)
                    synchronized(this@SpotifyImportManager) { done++ }
                    onProgress(done)
                    song
                }
            }
        }.awaitAll()
    }

    /** Qué canción de Spotify se emparejó con cuál (para sincronizar solo las nuevas). */
    fun loadMap(playlistId: Long): Map<String, String> =
        runCatching { json.decodeFromString(mapSerializer, mapFile(playlistId).readText()) }.getOrDefault(emptyMap())

    fun saveMap(playlistId: Long, map: Map<String, String>) {
        runCatching { mapFile(playlistId).writeText(json.encodeToString(mapSerializer, map)) }
    }

    private fun mapFile(playlistId: Long) = File(File(filesDir, "spotify").apply { mkdirs() }, "$playlistId.json")

    val spotify: SpotifyImporter get() = importer
}
