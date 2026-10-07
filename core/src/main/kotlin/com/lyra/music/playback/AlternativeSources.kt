package com.lyra.music.playback

import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.SongMatcher
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.innertube.SearchFilter
import com.lyra.music.data.source.soundcloud.AudioStreamInfo
import com.lyra.music.data.source.soundcloud.NewPipeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.GeographicRestrictionException
import org.schabi.newpipe.extractor.exceptions.PrivateContentException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import org.schabi.newpipe.extractor.exceptions.YoutubeMusicPremiumContentException
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Plan B cuando YouTube no deja sacar el audio de un vídeo concreto sin cuenta
 * (restricción de edad, "Please sign in", solo Premium, bloqueado en tu país…):
 * busca otra versión de la misma canción —el audio oficial, otro vídeo o
 * SoundCloud— y usa esa. Lo que encuentra se recuerda para la próxima vez.
 */
class AlternativeSources(
    private val newPipe: NewPipeSource,
    private val innerTube: InnerTube,
    private val songInfo: suspend (String) -> Song?,
    private val file: File,
) {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = MapSerializer(String.serializer(), String.serializer())
    private val known = ConcurrentHashMap<String, String>()
    private val failedAt = ConcurrentHashMap<String, Long>()
    @Volatile private var loaded = false

    /** Pistas de audio de [songId] o, si YouTube no las da, las de otra versión de la misma canción. */
    suspend fun audioStreams(songId: String, hint: Song? = null): List<AudioStreamInfo> {
        load()
        known[songId]?.let { alternative ->
            val streams = runCatching { newPipe.audioStreams(alternative) }.getOrNull()
            if (!streams.isNullOrEmpty()) return streams
            known.remove(songId)
            save()
        }
        val streams = try {
            newPipe.audioStreams(songId)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            if (!worthLookingElsewhere(e)) throw if (e is IOException) e else friendly(e)
            return fromAlternative(songId, e, hint)
        }
        return streams.ifEmpty { fromAlternative(songId, null, hint) }
    }

    /** Versión que se está usando en lugar de [songId] (null si es la original). */
    fun alternativeFor(songId: String): String? = known[songId]

    private suspend fun fromAlternative(songId: String, cause: Exception?, hint: Song?): List<AudioStreamInfo> {
        val now = System.currentTimeMillis()
        // Si hace poco que no se encontró nada, no se repite la búsqueda en cada reintento.
        if (now - (failedAt[songId] ?: 0L) < RETRY_AFTER_MS) throw friendly(cause)
        val song = songInfo(songId) ?: hint
        if (song != null) {
            for (candidate in candidates(song)) {
                val streams = runCatching { newPipe.audioStreams(candidate.id) }.getOrNull()
                if (!streams.isNullOrEmpty()) {
                    known[songId] = candidate.id
                    failedAt.remove(songId)
                    save()
                    return streams
                }
            }
        }
        failedAt[songId] = now
        throw friendly(cause)
    }

    /** Otras versiones de la canción, de más a menos fiables: audio de YouTube Music, vídeos y SoundCloud. */
    private suspend fun candidates(song: Song): List<Song> {
        val readings = readings(song)
        val (title, artists) = readings.first()
        val query = listOf(title, artists.firstOrNull().orEmpty()).joinToString(" ").trim()
        fun best(found: List<Song>) = found
            .filter { it.id != song.id }
            .map { candidate -> candidate to readings.maxOf { (t, a) -> SongMatcher.score(t, a, song.durationMs, candidate) } }
            .filter { it.second >= MIN_SCORE }
            .sortedByDescending { it.second }
            .map { it.first }
            .take(2)

        val songs = runCatching { innerTube.search(query, SearchFilter.SONGS).items.filterIsInstance<Song>().take(8) }.getOrDefault(emptyList())
        val videos = runCatching { innerTube.search(query, SearchFilter.VIDEOS).items.filterIsInstance<Song>().take(8) }.getOrDefault(emptyList())
        val soundCloud = runCatching { newPipe.searchSoundCloud(query).items.filterIsInstance<Song>().take(8) }.getOrDefault(emptyList())
        return (best(songs) + best(videos) + best(soundCloud)).distinctBy { it.id }
    }

    private suspend fun load() {
        if (loaded) return
        withContext(Dispatchers.IO) {
            runCatching { known.putAll(json.decodeFromString(serializer, file.readText())) }
        }
        loaded = true
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(serializer, HashMap(known))) }
    }

    companion object {
        private const val MIN_SCORE = 5.0
        private const val RETRY_AFTER_MS = 30 * 60_000L
        private val ARTIST_TITLE = Regex("^(.+?)\\s+[-–—]\\s+(.+)$")
        private val BRACKETS = Regex("[(\\[].*?[)\\]]")
        private val QUOTES = Regex("[\"'“”‘’«»]+")

        /**
         * Título y artistas con los que buscar. En vídeos subidos por fans, el "artista" es
         * quien lo subió y el título es "Artista - Canción (Official Video)": se prueban las dos lecturas.
         */
        fun readings(song: Song): List<Pair<String, List<String>>> {
            val own = song.title to song.artists.map { it.name }
            val parts = ARTIST_TITLE.find(song.title) ?: return listOf(own)
            val artist = parts.groupValues[1].replace(QUOTES, "").trim()
            val title = parts.groupValues[2].replace(BRACKETS, " ").replace(QUOTES, "")
                .split(Regex("\\s+[-–—|]\\s*|\\s*[-–—|]\\s+")).firstOrNull { it.isNotBlank() }?.trim().orEmpty()
            if (artist.isEmpty() || title.isEmpty()) return listOf(own)
            return listOf(title to listOf(artist), own)
        }

        /** Fallos de ese vídeo en concreto (no de la red ni de un bloqueo de YouTube a tu conexión). */
        fun worthLookingElsewhere(e: Throwable): Boolean = e is ContentNotAvailableException

        /** Error en castellano y con sentido para la pantalla de descargas o el reproductor. */
        fun friendly(e: Throwable?): IOException = IOException(
            when {
                e == null -> "Esta canción no tiene audio disponible y no he encontrado otra versión"
                e is AgeRestrictedContentException -> "YouTube solo deja escucharla con cuenta (restricción de edad) y no he encontrado otra versión"
                e is GeographicRestrictionException -> "No está disponible en tu país y no he encontrado otra versión"
                e is YoutubeMusicPremiumContentException -> "Solo está en YouTube Music Premium y no he encontrado otra versión"
                e is PrivateContentException -> "Este vídeo es privado"
                e is SignInConfirmNotBotException -> "YouTube ha frenado las peticiones desde tu conexión. Prueba en un rato o con otra red"
                e is ContentNotAvailableException && e.message?.contains("login_required", ignoreCase = true) == true ->
                    "YouTube pide iniciar sesión para esta versión y no he encontrado otra"
                e is ContentNotAvailableException -> "YouTube no deja reproducir esta versión y no he encontrado otra"
                else -> e.message ?: "No se pudo obtener el audio"
            },
            e,
        )
    }
}
