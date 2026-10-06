package com.lyra.music.data.source.innertube

import com.lyra.music.data.model.AlbumPage
import com.lyra.music.data.model.ArtistPage
import com.lyra.music.data.model.BrowseEndpoint
import com.lyra.music.data.model.BrowsePage
import com.lyra.music.data.model.HomePage
import com.lyra.music.data.model.MoodGroup
import com.lyra.music.data.model.PlaylistPage
import com.lyra.music.data.model.RadioPage
import com.lyra.music.data.model.SearchPage
import com.lyra.music.data.model.SearchSuggestions
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.remoteId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonObjectBuilder
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.put
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.net.URLEncoder
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonPrimitive

/** Filtros de búsqueda de YouTube Music (parámetro `params`). */
enum class SearchFilter(val params: String?) {
    ALL(null),
    SONGS("EgWKAQIIAWoSEAUQChAJEBAQAxAEEA4QFRAR"),
    VIDEOS("EgWKAQIQAWoSEAUQChAJEBAQAxAEEA4QFRAR"),
    ALBUMS("EgWKAQIYAWoSEAUQChAJEBAQAxAEEA4QFRAR"),
    ARTISTS("EgWKAQIgAWoSEAUQChAJEBAQAxAEEA4QFRAR"),
    FEATURED_PLAYLISTS("EgeKAQQoADgBahIQBRAKEAkQEBADEAQQDhAVEBE="),
    COMMUNITY_PLAYLISTS("EgeKAQQoAEABahIQBRAKEAkQEBADEAQQDhAVEBE="),
}

class InnerTubeException(message: String, val code: Int = 0) : IOException(message)

/**
 * Cliente mínimo de la API interna de YouTube Music (la que usa music.youtube.com).
 * No necesita cuenta. Guarda el `visitorData` que devuelve YouTube porque sin él
 * las páginas siguientes del inicio llegan vacías.
 */
class InnerTube(
    private val http: OkHttpClient,
    private val visitorStore: VisitorStore = VisitorStore.InMemory(),
) {
    interface VisitorStore {
        fun get(): String?
        fun set(value: String)

        class InMemory : VisitorStore {
            @Volatile private var value: String? = null
            override fun get() = value
            override fun set(value: String) { this.value = value }
        }
    }

    @Volatile var language: String = "es"
    @Volatile var region: String = "ES"

    private val json = Json { ignoreUnknownKeys = true }

    private suspend fun post(
        endpoint: String,
        continuation: String? = null,
        body: JsonObjectBuilder.() -> Unit = {},
    ): JsonObject = withContext(Dispatchers.IO) {
        val visitor = visitorStore.get()
        val payload = buildJsonObject {
            put("context", buildJsonObject {
                put("client", buildJsonObject {
                    put("clientName", CLIENT_NAME)
                    put("clientVersion", CLIENT_VERSION)
                    put("hl", language)
                    put("gl", region)
                    if (visitor != null) put("visitorData", visitor)
                })
            })
            body()
        }
        val url = buildString {
            append(BASE).append(endpoint).append("?prettyPrint=false")
            if (continuation != null) {
                val token = URLEncoder.encode(continuation, "UTF-8")
                append("&ctoken=").append(token).append("&continuation=").append(token).append("&type=next")
            }
        }
        val request = Request.Builder()
            .url(url)
            .post(payload.toString().toRequestBody(JSON_TYPE))
            .header("User-Agent", USER_AGENT)
            .header("Origin", ORIGIN)
            .header("Referer", "$ORIGIN/")
            .header("X-YouTube-Client-Name", CLIENT_ID)
            .header("X-YouTube-Client-Version", CLIENT_VERSION)
            .apply { if (visitor != null) header("X-Goog-Visitor-Id", visitor) }
            .build()
        http.newCall(request).execute().use { response ->
            val text = response.body.string()
            if (!response.isSuccessful) {
                throw InnerTubeException("YouTube Music respondió ${response.code}", response.code)
            }
            val root = json.parseToJsonElement(text).jsonObject
            if (visitor == null) {
                root.atStr("responseContext", "visitorData")?.let(visitorStore::set)
            }
            root
        }
    }

    // ------------------------------------------------------------- búsqueda

    suspend fun search(query: String, filter: SearchFilter = SearchFilter.ALL): SearchPage {
        val root = post("search") {
            put("query", query)
            filter.params?.let { put("params", it) }
        }
        return InnerTubeParser.search(root)
    }

    suspend fun searchMore(continuation: String): SearchPage =
        InnerTubeParser.searchContinuation(post("search", continuation))

    suspend fun suggestions(input: String): SearchSuggestions =
        InnerTubeParser.suggestions(post("music/get_search_suggestions") { put("input", input) })

    // ------------------------------------------------------------- navegación

    suspend fun home(): HomePage = InnerTubeParser.home(post("browse") { put("browseId", "FEmusic_home") })

    suspend fun homeMore(continuation: String): HomePage = InnerTubeParser.home(post("browse", continuation))

    suspend fun browse(endpoint: BrowseEndpoint): BrowsePage =
        InnerTubeParser.browse(post("browse") {
            put("browseId", endpoint.browseId)
            endpoint.params?.let { put("params", it) }
        })

    suspend fun homeWithChip(endpoint: BrowseEndpoint): HomePage =
        InnerTubeParser.home(post("browse") {
            put("browseId", endpoint.browseId)
            endpoint.params?.let { put("params", it) }
        })

    suspend fun moods(): List<MoodGroup> =
        InnerTubeParser.moods(post("browse") { put("browseId", "FEmusic_moods_and_genres") })

    /** [id] puede venir con o sin el prefijo `yt:`. */
    suspend fun album(id: String): AlbumPage {
        val browseId = id.removePrefix("yt:")
        return InnerTubeParser.album(post("browse") { put("browseId", browseId) }, browseId)
    }

    suspend fun artist(id: String): ArtistPage {
        val browseId = id.removePrefix("yt:")
        return InnerTubeParser.artist(post("browse") { put("browseId", browseId) }, browseId)
    }

    suspend fun playlist(id: String): PlaylistPage {
        val raw = id.removePrefix("yt:")
        val browseId = if (raw.startsWith("VL") || raw.startsWith("MPRE")) raw else "VL$raw"
        return InnerTubeParser.playlist(post("browse") { put("browseId", browseId) }, browseId)
    }

    suspend fun playlistMore(continuation: String): Pair<List<Song>, String?> =
        InnerTubeParser.playlistContinuation(post("browse", continuation))

    // ------------------------------------------------------------- radio

    /** Radio de una canción: la cola infinita de "canciones parecidas". */
    suspend fun radio(songId: String, playlistId: String? = null): RadioPage {
        val videoId = songId.remoteId()
        return InnerTubeParser.radio(post("next") {
            put("videoId", videoId)
            put("playlistId", playlistId ?: "RDAMVM$videoId")
            put("params", "wAEB")
            put("isAudioOnly", true)
        })
    }

    /** Cola de una playlist o mix de YouTube (p. ej. la radio de un artista, RDEM…). */
    suspend fun radioFromPlaylist(playlistId: String): RadioPage =
        InnerTubeParser.radio(post("next") {
            put("playlistId", playlistId.removePrefix("VL"))
            put("isAudioOnly", true)
        })

    suspend fun radioMore(continuation: String): RadioPage =
        InnerTubeParser.radio(post("next") {
            put("continuation", continuation)
            put("isAudioOnly", true)
        })

    /** Título, artistas, carátula y duración de varias canciones de golpe (hasta unas 50 por vez). */
    suspend fun songs(songIds: List<String>): List<Song> =
        InnerTubeParser.radio(post("music/get_queue") {
            put("videoIds", JsonArray(songIds.map { JsonPrimitive(it.remoteId()) }))
        }).songs

    suspend fun related(relatedBrowseId: String): BrowsePage =
        InnerTubeParser.browse(post("browse") { put("browseId", relatedBrowseId) })

    suspend fun lyrics(lyricsBrowseId: String): String? =
        InnerTubeParser.lyrics(post("browse") { put("browseId", lyricsBrowseId) })

    companion object {
        private const val BASE = "https://music.youtube.com/youtubei/v1/"
        private const val ORIGIN = "https://music.youtube.com"
        private const val CLIENT_NAME = "WEB_REMIX"
        private const val CLIENT_ID = "67"
        const val CLIENT_VERSION = "1.20250915.03.00"
        const val USER_AGENT =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/140.0.0.0 Safari/537.36"
        private val JSON_TYPE = "application/json".toMediaType()
    }
}
