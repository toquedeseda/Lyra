package com.lyra.music.data.share

import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.model.remoteId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.URI
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.Deflater
import java.util.zip.Inflater

/** Una playlist compartida por enlace: su nombre y sus canciones (id, título y artista). */
@Serializable
data class SharedPlaylist(
    @SerialName("n") val name: String,
    @SerialName("s") val songs: List<SharedSong>,
)

@Serializable
data class SharedSong(
    @SerialName("i") val id: String,
    @SerialName("t") val title: String,
    @SerialName("a") val artist: String = "",
) {
    /** La canción con lo que se sabe; la carátula de verdad llega después (ver MusicRepository.songsByIds). */
    fun toSong(): Song = Song(
        id = id,
        title = title,
        artists = artist.split(", ").filter { it.isNotBlank() }.map { ArtistRef(it) },
        thumbnailUrl = if (Source.of(id) == Source.YOUTUBE) "https://i.ytimg.com/vi/${id.remoteId()}/hqdefault.jpg" else null,
    )
}

/**
 * Enlaces para compartir playlists: `https://<web>/p/<código>` (la playlist se guarda en el
 * servidor de la web) o, si el servidor no responde, `https://<web>/p#<datos>` con la playlist
 * dentro del propio enlace (comprimida). Los dos abren Lyra si está instalada (App Links) y, si
 * no, la web enseña las canciones y cómo descargarla.
 */
class PlaylistSharing(private val http: OkHttpClient) {

    private val json = Json { ignoreUnknownKeys = true }

    /** Enlace para compartir: corto si el servidor responde; si no, con la playlist dentro. */
    suspend fun linkFor(name: String, songs: List<Song>): String {
        val playlist = shareable(name, songs)
        return runCatching { upload(playlist) }.getOrElse { longLink(playlist) }
    }

    /** La playlist de un enlace (corto o largo). */
    suspend fun load(url: String): SharedPlaylist {
        val uri = runCatching { URI(url.trim()) }.getOrElse { throw IOException("El enlace no es válido") }
        uri.rawFragment?.takeIf { it.isNotBlank() }?.let { return decode(it) }
        val code = pathSegments(uri).getOrNull(1) ?: throw IOException("El enlace no lleva ninguna playlist")
        if (!CODE.matches(code)) throw IOException("El enlace no es válido")
        return withContext(Dispatchers.IO) {
            val request = Request.Builder().url("$BASE/api/listas/$code").header("X-Lyra", "1").build()
            http.newCall(request).execute().use { response ->
                when (response.code) {
                    200 -> json.decodeFromString(SharedPlaylist.serializer(), response.body.string())
                    404 -> throw IOException("Esta playlist ya no existe")
                    else -> throw IOException("No se pudo abrir la playlist (${response.code})")
                }
            }
        }
    }

    private suspend fun upload(playlist: SharedPlaylist): String = withContext(Dispatchers.IO) {
        val body = json.encodeToString(SharedPlaylist.serializer(), playlist).toRequestBody(JSON_TYPE)
        val request = Request.Builder().url("$BASE/api/listas").post(body).header("X-Lyra", "1").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) throw IOException("El servidor respondió ${response.code}")
            val code = json.decodeFromString(Created.serializer(), response.body.string()).id
            if (!CODE.matches(code)) throw IOException("Respuesta rara del servidor")
            "$BASE/p/$code"
        }
    }

    @Serializable
    private data class Created(val id: String)

    companion object {
        const val HOST = "lyra.shopxcenter.duckdns.org"
        const val BASE = "https://$HOST"

        /** Lo mismo que acepta el servidor (y no más, para que siempre quepa). */
        const val MAX_SONGS = 1000
        private const val MAX_TEXT = 120
        private val CODE = Regex("[A-Za-z0-9_-]{6,16}")
        private val JSON_TYPE = "application/json".toMediaType()
        private val codec = Json { ignoreUnknownKeys = true }

        fun isPlaylistLink(url: String): Boolean {
            val uri = runCatching { URI(url.trim()) }.getOrNull() ?: return false
            return uri.host.equals(HOST, ignoreCase = true) && pathSegments(uri).firstOrNull() == "p"
        }

        private fun pathSegments(uri: URI): List<String> = uri.path.orEmpty().split('/').filter { it.isNotEmpty() }

        fun shareable(name: String, songs: List<Song>) = SharedPlaylist(
            name = name.trim().take(MAX_TEXT).ifBlank { "Playlist" },
            songs = songs.distinctBy { it.id }.take(MAX_SONGS).map { song ->
                SharedSong(song.id, song.title.take(MAX_TEXT), song.artistsText.take(MAX_TEXT))
            },
        )

        /** Enlace con la playlist dentro (no pasa por el servidor: el fragmento no se envía). */
        fun longLink(playlist: SharedPlaylist): String = "$BASE/p#" + encode(playlist)

        fun encode(playlist: SharedPlaylist): String {
            val raw = codec.encodeToString(SharedPlaylist.serializer(), playlist).toByteArray()
            val deflater = Deflater(Deflater.BEST_COMPRESSION, true).apply {
                setInput(raw)
                finish()
            }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!deflater.finished()) out.write(buffer, 0, deflater.deflate(buffer))
            deflater.end()
            return Base64.getUrlEncoder().withoutPadding().encodeToString(out.toByteArray())
        }

        fun decode(data: String): SharedPlaylist {
            val bytes = Base64.getUrlDecoder().decode(data)
            val inflater = Inflater(true).apply { setInput(bytes) }
            val out = ByteArrayOutputStream()
            val buffer = ByteArray(8192)
            while (!inflater.finished()) {
                val n = inflater.inflate(buffer)
                if (n == 0 && (inflater.needsInput() || inflater.needsDictionary())) break
                out.write(buffer, 0, n)
                // Un enlace no puede traer más de lo que cabe en una playlist compartida.
                if (out.size() > 2_000_000) throw IOException("El enlace es demasiado grande")
            }
            inflater.end()
            val playlist = codec.decodeFromString(SharedPlaylist.serializer(), out.toString(Charsets.UTF_8.name()))
            return playlist.copy(songs = playlist.songs.take(MAX_SONGS))
        }
    }
}
