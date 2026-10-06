package com.lyra.music.data.model

import java.text.Normalizer

/**
 * Títulos limpios: quita el ruido que llevan muchos vídeos ("(Official Video)",
 * "[Lyrics]", "HD"…) y, en vídeos subidos por fans o canales y en SoundCloud,
 * separa "Artista - Canción" para que la canción tenga su artista de verdad.
 * Lo que sí dice algo de la canción ("feat.", "Remix", "Live", "con…") se queda.
 */
object TitleCleaner {

    /** Palabras que, si son lo único que hay entre paréntesis o tras un guion, son ruido. */
    private val NOISE = setOf(
        "official", "oficial", "officiel", "music", "musical", "musica", "video", "videoclip", "clip",
        "audio", "lyric", "lyrics", "letra", "letras", "con", "visualizer", "visualiser", "visual",
        "hd", "hq", "4k", "8k", "uhd", "1080p", "720p", "fixed", "english", "cc", "subtitulado",
        "subtitulos", "sub", "subs", "espanol", "explicit", "mv", "m/v", "full",
    )
    /** Palabras que unen ruido ("Official Video and Lyrics"). */
    private val GLUE = setOf("and", "y", "e", "with", "w/", "&", "+")
    private val YEAR = Regex("(19|20)\\d\\d")
    private val BRACKET = Regex("[(\\[【]([^()\\[\\]【】]*)[)\\]】]")
    private val SEPARATOR = Regex("(?:^|\\s)[-–—|]+(?:\\s|$)|\\s//\\s")
    private val SPACES = Regex("\\s+")
    private val QUOTES = "\"'“”‘’«»「」"
    private val MARKS = Regex("\\p{Mn}+")
    private val NOT_ALNUM = Regex("[^\\p{L}\\p{N}]")
    private val NAME_SEPARATOR = Regex("\\s*(?:,|&|\\+|\\s[xX]\\s|\\bfeat\\.?|\\bft\\.?|\\svs\\.?\\s)\\s*", RegexOption.IGNORE_CASE)

    fun clean(song: Song): Song {
        val artists = song.artists.map { it.copy(name = cleanArtist(it.name)) }.filter { it.name.isNotBlank() }.ifEmpty { song.artists }
        val segments = segments(song.title)
        if (segments.isEmpty()) return song.copy(artists = artists)
        var title = segments.joinToString(" - ")
        var finalArtists = artists
        if (segments.size >= 2) {
            val left = segments.first()
            val right = segments.drop(1).joinToString(" - ")
            val names = names(left)
            val first = artists.firstOrNull()?.name
            when {
                // "ROSALÍA - CANDY" de ROSALÍA: sobra el artista del título.
                first != null && names.isNotEmpty() && key(names.first()) == key(first) -> {
                    title = right
                    finalArtists = names.map { name -> artists.firstOrNull { key(it.name) == key(name) } ?: ArtistRef(name) } +
                        artists.filter { a -> names.none { key(it) == key(a.name) } }
                }
                // Subido por un fan, un canal o una cuenta de SoundCloud: el artista de verdad va en el título.
                song.isVideo || song.source == Source.SOUNDCLOUD -> {
                    title = right
                    finalArtists = names.map { ArtistRef(it) }.ifEmpty { artists }
                }
            }
        }
        return song.copy(title = title.ifBlank { song.title }, artists = finalArtists)
    }

    /** "RammsteinVEVO" → "Rammstein"; "Bad Bunny - Topic" → "Bad Bunny". */
    fun cleanArtist(name: String): String =
        name.replace(Regex("\\s*-\\s*Topic$"), "").replace(Regex("(?<=\\S)VEVO$"), "").trim()

    /** El título sin ruido, partido por sus guiones ("Artista", "Canción"…). */
    fun segments(title: String): List<String> {
        val withoutNoise = BRACKET.replace(title) { match -> if (isNoise(match.groupValues[1])) " " else match.value }
        return withoutNoise.split(SEPARATOR)
            .map { unquote(stripTrailingNoise(it.replace(SPACES, " ").trim())) }
            .filterIndexed { index, part -> part.isNotBlank() && (index == 0 || !isNoise(part)) }
    }

    /**
     * Ruido sin paréntesis al final: "Tainted Love Official Music Video and Lyrics" →
     * "Tainted Love". Solo con dos o más palabras de ruido, para no tocar "Video Games".
     */
    private fun stripTrailingNoise(text: String): String {
        val words = text.split(' ')
        var cut = words.size
        var noise = 0
        while (cut > 0) {
            val word = key(words[cut - 1], keepSpaces = true)
            when {
                word in NOISE || YEAR.matches(word) -> noise++
                word in GLUE || word.isEmpty() -> Unit
                else -> break
            }
            cut--
        }
        // No se deja colgando un "and" del principio del ruido.
        while (cut < words.size && key(words[cut], keepSpaces = true) in GLUE) cut++
        if (noise < 2 || cut == 0) return text
        return words.take(cut).joinToString(" ").trim()
    }

    private fun isNoise(text: String): Boolean {
        val words = key(text, keepSpaces = true).split(' ').filter { it.isNotBlank() }
        if (words.isEmpty()) return true
        return words.all { it in NOISE || YEAR.matches(it) } && words.any { it in NOISE }
    }

    /** Quita las comillas que envuelven el texto (''Pussy'' → Pussy), no los apóstrofos de dentro. */
    private fun unquote(text: String): String {
        if (text.length > 2 && text.first() in QUOTES && text.last() in QUOTES) {
            return text.trim { it in QUOTES || it.isWhitespace() }
        }
        return text
    }

    private fun names(text: String): List<String> = text.split(NAME_SEPARATOR).map { it.trim() }.filter { it.isNotEmpty() }

    private fun key(text: String, keepSpaces: Boolean = false): String {
        val plain = Normalizer.normalize(text.lowercase(), Normalizer.Form.NFD).replace(MARKS, "")
        return if (keepSpaces) plain.replace(Regex("[^\\p{L}\\p{N}/\\s]"), " ").replace(SPACES, " ").trim() else plain.replace(NOT_ALNUM, "")
    }
}

/** La misma canción con el título y los artistas limpios. */
fun Song.cleaned(): Song = TitleCleaner.clean(this)
