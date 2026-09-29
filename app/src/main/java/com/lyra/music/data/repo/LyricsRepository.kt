package com.lyra.music.data.repo

import com.lyra.music.data.db.LyraDatabase
import com.lyra.music.data.db.LyricsEntity
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.lyrics.Lrclib
import com.lyra.music.data.source.lyrics.Lyrics
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Letras: primero LRCLIB (sincronizadas), si no hay, el texto de YouTube Music.
 * Se guardan en la base de datos para que funcionen sin conexión.
 */
class LyricsRepository(
    private val lrclib: Lrclib,
    private val innerTube: InnerTube,
    private val db: LyraDatabase,
) {
    suspend fun lyrics(song: Song, force: Boolean = false): Lyrics? = withContext(Dispatchers.IO) {
        val cached = db.lyrics().get(song.id)
        if (cached != null && !force) {
            val fresh = System.currentTimeMillis() - cached.fetchedAt < RETRY_NOT_FOUND_MS
            if (!cached.notFound) return@withContext cached.toLyrics()
            if (fresh) return@withContext null
        }
        val found = runCatching {
            lrclib.find(song.title, song.artists.firstOrNull()?.name.orEmpty(), song.album?.title, song.durationMs?.div(1000))
        }.getOrNull() ?: runCatching { youtubeLyrics(song) }.getOrNull()

        db.lyrics().save(
            LyricsEntity(
                songId = song.id,
                synced = found?.synced?.let(::toLrc),
                plain = found?.plain,
                source = found?.source,
                notFound = found == null,
            ),
        )
        found
    }

    private suspend fun youtubeLyrics(song: Song): Lyrics? {
        if (song.source != Source.YOUTUBE) return null
        val browseId = innerTube.radio(song.id).lyricsBrowseId ?: return null
        val text = innerTube.lyrics(browseId) ?: return null
        return Lyrics(synced = null, plain = text, source = "YouTube Music")
    }

    private fun LyricsEntity.toLyrics(): Lyrics? {
        val lines = synced?.let(Lrclib::parseLrc)?.takeIf { it.isNotEmpty() }
        if (lines == null && plain.isNullOrBlank()) return null
        return Lyrics(lines, plain, source ?: "")
    }

    private fun toLrc(lines: List<com.lyra.music.data.source.lyrics.LyricLine>): String =
        lines.joinToString("\n") { line ->
            val minutes = line.timeMs / 60_000
            val seconds = (line.timeMs / 1000) % 60
            val hundredths = (line.timeMs % 1000) / 10
            "[%02d:%02d.%02d]%s".format(minutes, seconds, hundredths, line.text)
        }

    companion object {
        private const val RETRY_NOT_FOUND_MS = 3L * 86_400_000
    }
}
