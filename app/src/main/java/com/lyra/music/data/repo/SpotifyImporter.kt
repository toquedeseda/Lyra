package com.lyra.music.data.repo

import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.innertube.SearchFilter
import com.lyra.music.data.source.innertube.findFirst
import com.lyra.music.data.source.innertube.str
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

/**
 * Importa playlists, álbumes y canciones de Spotify sin cuenta ni claves: lee la
 * página pública para insertar ("embed") y busca cada canción en YouTube Music.
 */
class SpotifyImporter(private val http: OkHttpClient, private val innerTube: InnerTube) {

    data class Track(val title: String, val artists: List<String>, val durationMs: Long?, val uri: String)
    data class Collection(val kind: String, val id: String, val name: String, val cover: String?, val tracks: List<Track>)

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun fetch(kind: String, id: String): Collection = withContext(Dispatchers.IO) {
        val request = Request.Builder()
            .url("https://open.spotify.com/embed/$kind/$id")
            .header("User-Agent", InnerTube.USER_AGENT)
            .header("Accept-Language", "es-ES,es;q=0.9")
            .build()
        val html = http.newCall(request).execute().use { response ->
            if (response.code == 404) throw IOException("Esa lista no existe o es privada")
            if (!response.isSuccessful) throw IOException("Spotify respondió ${response.code}")
            response.body.string()
        }
        val data = Regex("<script id=\"__NEXT_DATA__\" type=\"application/json\">(.*?)</script>", RegexOption.DOT_MATCHES_ALL)
            .find(html)?.groupValues?.get(1) ?: throw IOException("No se pudo leer la lista de Spotify")
        val root = json.parseToJsonElement(data).jsonObject
        val entity = root.findFirst("entity") ?: throw IOException("La lista de Spotify está vacía o es privada")
        val name = entity.str("name") ?: entity.str("title") ?: "Playlist de Spotify"
        val cover = (entity["coverArt"] as? JsonObject)?.let { art ->
            (art["sources"] as? JsonArray)?.maxByOrNull { it.str("width")?.toIntOrNull() ?: 0 }?.str("url")
        }
        val tracks = when (kind) {
            "track" -> listOf(trackFrom(entity) ?: throw IOException("No se pudo leer la canción"))
            else -> (entity["trackList"] as? JsonArray)?.mapNotNull { trackFrom(it as? JsonObject ?: return@mapNotNull null) } ?: emptyList()
        }
        if (tracks.isEmpty()) throw IOException("La lista de Spotify no tiene canciones")
        Collection(kind, id, name, cover, tracks)
    }

    private fun trackFrom(obj: JsonObject): Track? {
        val title = obj.str("title") ?: obj.str("name") ?: return null
        val subtitle = obj.str("subtitle").orEmpty()
        val artists = subtitle.split(',').map { it.trim() }.filter { it.isNotEmpty() }
        val uri = obj.str("uri") ?: return null
        return Track(title, artists, obj.str("duration")?.toLongOrNull(), uri)
    }

    /** Busca la canción en YouTube Music y elige la que mejor encaja (título, artista y duración). */
    suspend fun match(track: Track): Song? {
        val query = listOf(track.title, track.artists.firstOrNull().orEmpty()).joinToString(" ").trim()
        val candidates = runCatching { innerTube.search(query, SearchFilter.SONGS).items.filterIsInstance<Song>().take(6) }
            .getOrDefault(emptyList())
        if (candidates.isEmpty()) return null
        val best = candidates.maxBy { score(track, it) }
        return best.takeIf { score(track, it) >= 2.5 } ?: candidates.first().takeIf { score(track, it) >= 1.0 }
    }

    private fun score(track: Track, song: Song): Double = SongMatcher.score(track.title, track.artists, track.durationMs, song)

    companion object {
        /** Tipo e id de un enlace de Spotify (playlist, álbum o canción), o null si no lo es. */
        fun parseLink(text: String): Pair<String, String>? {
            Regex("open\\.spotify\\.com/(?:intl-[a-z-]+/)?(playlist|album|track)/([A-Za-z0-9]+)").find(text)?.let {
                return it.groupValues[1] to it.groupValues[2]
            }
            Regex("spotify:(playlist|album|track):([A-Za-z0-9]+)").find(text)?.let { return it.groupValues[1] to it.groupValues[2] }
            return null
        }

        /** Minúsculas, sin tildes, sin "(feat. …)" ni "- Remastered". */
        fun normalize(text: String): String = SongMatcher.normalize(text)
    }
}

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
