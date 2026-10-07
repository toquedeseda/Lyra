package com.lyra.music.data.source.lyrics

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import kotlin.math.abs

/** Una línea de letra sincronizada. */
data class LyricLine(val timeMs: Long, val text: String)

data class Lyrics(
    val synced: List<LyricLine>?,
    val plain: String?,
    val source: String,
) {
    val isSynced: Boolean get() = !synced.isNullOrEmpty()
}

/**
 * Cliente de LRCLIB (https://lrclib.net), una base de datos abierta de letras
 * sincronizadas. Se consulta en el momento de reproducir; no hace falta cuenta.
 */
class Lrclib(private val http: OkHttpClient) {

    @Serializable
    private data class Entry(
        val id: Long = 0,
        val trackName: String? = null,
        val artistName: String? = null,
        val duration: Double? = null,
        val instrumental: Boolean = false,
        val plainLyrics: String? = null,
        val syncedLyrics: String? = null,
    )

    private val json = Json { ignoreUnknownKeys = true }

    suspend fun find(title: String, artist: String, album: String?, durationSec: Long?): Lyrics? =
        withContext(Dispatchers.IO) {
            val cleanTitle = cleanTitle(title)
            val cleanArtist = artist.split(",", "&").first().trim()
            exact(cleanTitle, cleanArtist, album, durationSec)?.let { return@withContext it }
            search(cleanTitle, cleanArtist, durationSec)
        }

    private fun exact(title: String, artist: String, album: String?, durationSec: Long?): Lyrics? {
        val url = "https://lrclib.net/api/get".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .apply {
                if (!album.isNullOrBlank()) addQueryParameter("album_name", album)
                if (durationSec != null && durationSec > 0) addQueryParameter("duration", durationSec.toString())
            }
            .build()
        val body = get(url.toString()) ?: return null
        return json.decodeFromString<Entry>(body).toLyrics()
    }

    private fun search(title: String, artist: String, durationSec: Long?): Lyrics? {
        val url = "https://lrclib.net/api/search".toHttpUrl().newBuilder()
            .addQueryParameter("track_name", title)
            .addQueryParameter("artist_name", artist)
            .build()
        val body = get(url.toString()) ?: return null
        val entries = json.decodeFromString<List<Entry>>(body).filter { !it.instrumental }
        val candidates = if (durationSec != null && durationSec > 0) {
            entries.filter { it.duration == null || abs(it.duration - durationSec) <= 4 }
        } else {
            entries
        }
        val best = candidates.firstOrNull { !it.syncedLyrics.isNullOrBlank() }
            ?: candidates.firstOrNull { !it.plainLyrics.isNullOrBlank() }
        return best?.toLyrics()
    }

    private fun get(url: String): String? {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Lyra (https://github.com/toquedeseda/Lyra)")
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 404) return null
            if (!response.isSuccessful) throw java.io.IOException("LRCLIB respondió ${response.code}")
            return response.body.string()
        }
    }

    private fun Entry.toLyrics(): Lyrics? {
        if (instrumental) return null
        val synced = syncedLyrics?.let(::parseLrc)?.takeIf { it.isNotEmpty() }
        val plain = plainLyrics?.takeIf { it.isNotBlank() }
        if (synced == null && plain == null) return null
        return Lyrics(synced, plain, "LRCLIB")
    }

    companion object {
        private val TIME_TAG = Regex("\\[(\\d{1,2}):(\\d{2})(?:[.:](\\d{1,3}))?]")
        private val NOISE = Regex(
            "\\s*[(\\[](official|video|audio|lyric|letra|visualizer|visualiser|oficial|hd|4k|remaster|mv)[^)\\]]*[)\\]]",
            RegexOption.IGNORE_CASE,
        )
        private val FEAT = Regex("\\s*[(\\[]?\\b(feat\\.?|ft\\.?|featuring|con)\\s[^)\\]]*[)\\]]?", RegexOption.IGNORE_CASE)

        /** Quita "(Official Video)", "[Lyrics]", "feat. X"… para encontrar mejor la canción. */
        fun cleanTitle(title: String): String =
            title.replace(NOISE, "").replace(FEAT, "").replace(Regex("\\s+-\\s+Topic$"), "").trim()
                .ifEmpty { title }

        /** Formato LRC: `[mm:ss.xx] texto`, con posibles varias marcas por línea. */
        fun parseLrc(lrc: String): List<LyricLine> {
            val lines = mutableListOf<LyricLine>()
            for (raw in lrc.lineSequence()) {
                val tags = TIME_TAG.findAll(raw).toList()
                if (tags.isEmpty()) continue
                val text = raw.substring(tags.last().range.last + 1).trim()
                for (tag in tags) {
                    val minutes = tag.groupValues[1].toLong()
                    val seconds = tag.groupValues[2].toLong()
                    val fraction = tag.groupValues[3]
                    val millis = when (fraction.length) {
                        0 -> 0L
                        1 -> fraction.toLong() * 100
                        2 -> fraction.toLong() * 10
                        else -> fraction.take(3).toLong()
                    }
                    lines += LyricLine(minutes * 60_000 + seconds * 1000 + millis, text)
                }
            }
            return lines.sortedBy { it.timeMs }
        }
    }
}
