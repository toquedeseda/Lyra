package com.lyra.music.data.repo

import com.lyra.music.data.model.Song
import java.text.Normalizer
import kotlin.math.abs

/** Decide si una canción encontrada es la que se buscaba (título, artista y duración). */
object SongMatcher {

    /**
     * Puntos de [candidate] frente a lo buscado: título igual 3 (contenido 2), artista 3,
     * duración casi igual 3 (parecida 1, muy distinta −3). Con 5 o más es la misma canción.
     */
    fun score(title: String, artists: List<String>, durationMs: Long?, candidate: Song): Double {
        var score = 0.0
        val wantedTitle = normalize(title)
        val foundTitle = normalize(candidate.title)
        score += when {
            wantedTitle.isEmpty() || foundTitle.isEmpty() -> 0.0
            foundTitle == wantedTitle -> 3.0
            foundTitle.contains(wantedTitle) || wantedTitle.contains(foundTitle) -> 2.0
            else -> 0.0
        }
        val wantedArtist = normalize(artists.firstOrNull().orEmpty())
        if (wantedArtist.isNotEmpty() && candidate.artists.any { artist ->
                normalize(artist.name).let { it.isNotEmpty() && (it == wantedArtist || it.contains(wantedArtist) || wantedArtist.contains(it)) }
            }
        ) {
            score += 3.0
        }
        val found = candidate.durationMs
        if (durationMs != null && found != null) {
            val diff = abs(durationMs - found) / 1000
            score += when {
                diff <= 3 -> 3.0
                diff <= 8 -> 1.0
                diff > 25 -> -3.0
                else -> 0.0
            }
        }
        return score
    }

    private val BRACKETS = Regex("\\s*[(\\[].*?[)\\]]")
    private val NOT_ALNUM = Regex("[^\\p{L}\\p{N}]")

    /** La misma canción puede venir como vídeo, audio o versión: se compara título y artista. */
    fun songKey(song: Song): String =
        song.title.lowercase().replace(BRACKETS, "").replace(NOT_ALNUM, "") +
            "|" + (song.artists.firstOrNull()?.name?.lowercase() ?: "")

    /** Reordena para que no suenen dos canciones seguidas del mismo artista (si se puede). */
    fun spreadArtists(songs: List<Song>, previousArtist: String?): List<Song> {
        val pending = songs.toMutableList()
        val result = mutableListOf<Song>()
        var last = previousArtist
        while (pending.isNotEmpty()) {
            val next = pending.firstOrNull { it.artists.firstOrNull()?.name != last } ?: pending.first()
            pending.remove(next)
            result += next
            last = next.artists.firstOrNull()?.name
        }
        return result
    }

    /** Minúsculas, sin tildes, sin "(feat. …)", "[Official Video]" ni "- Remastered". */
    fun normalize(text: String): String =
        Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD)
            .replace(Regex("\\p{Mn}+"), "")
            .replace(Regex("\\s*[(\\[].*?[)\\]]"), "")
            .replace(Regex("\\s+-\\s+.*(remaster|version|versión|live|edit).*$"), "")
            .replace(Regex("[^\\p{L}\\p{N}]"), "")
}
