package com.lyra.music.data.repo

import android.content.Context
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.util.Calendar

/**
 * Lo nuevo de tus artistas: los que sigues y los que más escuchas. Mira sus
 * álbumes y singles más recientes (de este año y el anterior).
 */
class ReleasesRepository(
    context: Context,
    private val music: MusicRepository,
    private val library: LibraryRepository,
) {
    @Serializable
    private data class Cache(val savedAt: Long, val items: List<AlbumItem>)

    private val json = Json { ignoreUnknownKeys = true }
    private val file = File(context.filesDir, "releases.json")
    private val lock = Mutex()

    /** Lanzamientos recientes (de caché si tienen menos de [maxAgeMs]). */
    suspend fun releases(maxAgeMs: Long = 6 * 3_600_000L): List<AlbumItem> = lock.withLock {
        val cached = read()
        if (cached != null && System.currentTimeMillis() - cached.savedAt < maxAgeMs) return@withLock cached.items
        val fresh = runCatching { fetch() }.getOrNull() ?: return@withLock cached?.items.orEmpty()
        write(Cache(System.currentTimeMillis(), fresh))
        fresh
    }

    private suspend fun fetch(): List<AlbumItem> = coroutineScope {
        val followed = library.followedArtists.first()
            .filter { Source.of(it.id) == Source.YOUTUBE }
            .map { ArtistRef(it.title, it.id) }
        val top = runCatching { library.topArtists(days = 60, limit = 10) }.getOrDefault(emptyList())
            .filter { it.id?.startsWith("yt:") == true }
        val artists = (followed + top).distinctBy { it.id }.take(16)
        if (artists.isEmpty()) return@coroutineScope emptyList()

        val thisYear = Calendar.getInstance().get(Calendar.YEAR)
        val semaphore = Semaphore(4)
        val perArtist = artists.mapIndexed { order, artist ->
            async {
                semaphore.withPermit {
                    val page = runCatching { music.artist(artist.id!!) }.getOrNull() ?: return@withPermit emptyList()
                    page.sections
                        .filter { section -> section.items.isNotEmpty() && section.items.all { it is AlbumItem } }
                        .flatMap { it.items.take(3) }
                        .filterIsInstance<AlbumItem>()
                        .filter { (it.year?.toIntOrNull() ?: 0) >= thisYear - 1 }
                        .map { album ->
                            val withArtist = if (album.artists.isEmpty()) album.copy(artists = listOf(artist)) else album
                            Triple(withArtist, album.year?.toIntOrNull() ?: 0, order)
                        }
                }
            }
        }.awaitAll().flatten()

        perArtist
            .sortedWith(compareByDescending<Triple<AlbumItem, Int, Int>> { it.second }.thenBy { it.third })
            .map { it.first }
            .distinctBy { it.id }
            .take(20)
    }

    private suspend fun read(): Cache? = withContext(Dispatchers.IO) {
        runCatching { json.decodeFromString(Cache.serializer(), file.readText()) }.getOrNull()
    }

    private suspend fun write(cache: Cache) = withContext(Dispatchers.IO) {
        runCatching { file.writeText(json.encodeToString(Cache.serializer(), cache)) }
    }
}
