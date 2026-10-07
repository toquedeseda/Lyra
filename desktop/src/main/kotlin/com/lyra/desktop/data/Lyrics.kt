package com.lyra.desktop.data

import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.lyrics.LyricLine
import com.lyra.music.data.source.lyrics.Lrclib
import com.lyra.music.data.source.lyrics.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Letras, como en el móvil: primero LRCLIB (sincronizadas) y, si no hay, el texto de YouTube Music.
 * Se piden al escuchar la canción y se guardan en el PC para que funcionen sin conexión.
 */
class LyricsRepository(
    private val lrclib: Lrclib,
    private val innerTube: InnerTube,
    private val dir: File,
) {
    @Serializable
    private data class Cached(
        val synced: String? = null,
        val plain: String? = null,
        val source: String? = null,
        val notFound: Boolean = false,
        val fetchedAt: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }

    init {
        dir.mkdirs()
    }

    private fun fileFor(song: Song) = File(dir, song.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")

    suspend fun lyrics(song: Song, force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        val file = fileFor(song)
        val cached = runCatching { json.decodeFromString(Cached.serializer(), file.readText()) }.getOrNull()
        if (cached != null && !force) {
            if (!cached.notFound) return@withContext cached.toLyrics()
            if (System.currentTimeMillis() - cached.fetchedAt < RETRY_NOT_FOUND_MS) return@withContext null
        }
        val found = runCatching {
            lrclib.find(song.title, song.artists.firstOrNull()?.name.orEmpty(), song.album?.title, song.durationMs?.div(1000))
        }.getOrNull() ?: runCatching { youtubeLyrics(song) }.getOrNull()
        val entry = Cached(found?.synced?.let(::toLrc), found?.plain, found?.source, found == null, System.currentTimeMillis())
        runCatching { file.writeText(json.encodeToString(Cached.serializer(), entry)) }
        found
    }

    private suspend fun youtubeLyrics(song: Song): Lyrics? {
        if (song.source != Source.YOUTUBE) return null
        val browseId = innerTube.radio(song.id).lyricsBrowseId ?: return null
        val text = innerTube.lyrics(browseId) ?: return null
        return Lyrics(synced = null, plain = text, source = "YouTube Music")
    }

    private fun Cached.toLyrics(): Lyrics? {
        val lines = synced?.let(Lrclib::parseLrc)?.takeIf { it.isNotEmpty() }
        if (lines == null && plain.isNullOrBlank()) return null
        return Lyrics(lines, plain, source ?: "")
    }

    private fun toLrc(lines: List<LyricLine>): String = lines.joinToString("\n") { line ->
        val minutes = line.timeMs / 60_000
        val seconds = (line.timeMs / 1000) % 60
        val hundredths = (line.timeMs % 1000) / 10
        "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, line.text)
    }

    private companion object {
        const val RETRY_NOT_FOUND_MS = 3L * 86_400_000
    }
}
