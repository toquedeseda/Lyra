package com.lyra.music.data.repo

import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import java.util.concurrent.ConcurrentHashMap

/**
 * Canciones parecidas a un grupo de canciones (una playlist o la cola): junta la
 * radio de varias de ellas y se queda con las que más se repiten.
 */
class Recommender(private val music: MusicRepository) {

    private val cache = ConcurrentHashMap<String, List<Song>>()

    /**
     * Hasta [count] canciones parecidas a [songs] que no estén ya entre ellas ni en [avoid].
     * Con [cacheKey] se recuerda el resultado; con [refresh] se piden otras.
     */
    suspend fun similarTo(
        songs: List<Song>,
        count: Int = 20,
        seeds: Int = 4,
        avoid: Collection<Song> = emptyList(),
        cacheKey: String? = null,
        refresh: Boolean = false,
    ): List<Song> {
        if (!refresh && cacheKey != null) cache[cacheKey]?.let { return it }
        var result = compute(songs, count, seeds, avoid)
        // Si evitando las anteriores no queda casi nada, mejor repetir alguna.
        if (result.size < count / 2 && avoid.isNotEmpty()) result = compute(songs, count, seeds, emptyList())
        if (cacheKey != null && result.isNotEmpty()) cache[cacheKey] = result
        return result
    }

    private suspend fun compute(songs: List<Song>, count: Int, seeds: Int, avoid: Collection<Song>): List<Song> = coroutineScope {
        if (songs.isEmpty()) return@coroutineScope emptyList()
        val pool = songs.filter { it.source == Source.YOUTUBE }.ifEmpty { songs }
        val radios = pool.shuffled().take(seeds).map { seed ->
            async { runCatching { music.radio(seed).songs }.getOrDefault(emptyList()) }
        }.awaitAll()

        val excluded = songs + avoid
        val excludedIds = excluded.mapTo(HashSet()) { it.id }
        val excludedKeys = excluded.mapTo(HashSet()) { SongMatcher.songKey(it) }
        val scores = LinkedHashMap<String, Pair<Song, Double>>()
        for (radio in radios) {
            radio.forEachIndexed { index, song ->
                val key = SongMatcher.songKey(song)
                if (song.id in excludedIds || key in excludedKeys) return@forEachIndexed
                // Suma más si sale en varias radios y si sale pronto (lo más parecido va primero).
                val weight = 1.0 + (1.0 - index.toDouble() / radio.size)
                val previous = scores[key]
                scores[key] = Pair(previous?.first ?: song, (previous?.second ?: 0.0) + weight)
            }
        }
        val ranked = scores.values.sortedByDescending { it.second }.map { it.first }
        SongMatcher.spreadArtists(ranked.take(count * 2), null).take(count)
    }
}
