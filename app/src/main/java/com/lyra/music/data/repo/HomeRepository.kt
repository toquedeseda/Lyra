package com.lyra.music.data.repo

import android.content.Context
import com.lyra.music.data.model.Chip
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.RadioItem
import com.lyra.music.data.model.Section
import com.lyra.music.data.model.SectionStyle
import com.lyra.music.data.model.Song
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

data class HomeFeed(
    val quickAccess: List<MusicItem> = emptyList(),
    val sections: List<Section> = emptyList(),
    val chips: List<Chip> = emptyList(),
    val canLoadMore: Boolean = false,
    val loading: Boolean = false,
    val error: String? = null,
)

/**
 * Construye el Inicio: accesos rápidos, tus mixes, "porque escuchaste…",
 * recientes y las secciones de YouTube Music. Se guarda en disco para que
 * aparezca al instante al abrir la app, y se refresca por detrás.
 */
class HomeRepository(
    context: Context,
    private val music: MusicRepository,
    private val library: LibraryRepository,
    scope: CoroutineScope,
    private val releases: suspend () -> List<com.lyra.music.data.model.AlbumItem> = { emptyList() },
) {
    @Serializable
    private data class Cache(
        val personal: List<Section> = emptyList(),
        val remote: List<Section> = emptyList(),
        val chips: List<Chip> = emptyList(),
        val savedAt: Long = 0,
    )

    private data class State(
        val personal: List<Section> = emptyList(),
        val remote: List<Section> = emptyList(),
        val chips: List<Chip> = emptyList(),
        val continuation: String? = null,
        val loading: Boolean = false,
        val error: String? = null,
        val savedAt: Long = 0,
    )

    private val json = Json { ignoreUnknownKeys = true }
    private val cacheFile = File(context.filesDir, "home_cache.json")
    private val state = MutableStateFlow(State())
    private val refreshLock = Mutex()

    val feed: StateFlow<HomeFeed> = combine(state, library.recentContexts, library.recentlyPlayed) { s, contexts, recent ->
        val liked = PlaylistItem(LIKED_SONGS_ID, "Canciones que te gustan", null, null)
        val quick = (listOf<MusicItem>(liked) + contexts + recent).distinctBy { it.id }.take(8)
        val recentSection = if (recent.isNotEmpty()) {
            listOf(Section("Escuchado recientemente", recent.take(15)))
        } else {
            emptyList()
        }
        HomeFeed(
            quickAccess = quick,
            sections = s.personal.take(1) + recentSection + s.personal.drop(1) + s.remote,
            chips = s.chips,
            canLoadMore = s.continuation != null,
            loading = s.loading,
            error = s.error,
        )
    }.stateIn(scope, SharingStarted.Eagerly, HomeFeed(loading = true))

    init {
        scope.launch(Dispatchers.IO) {
            val cache = runCatching { json.decodeFromString<Cache>(cacheFile.readText()) }.getOrNull()
            if (cache != null) {
                state.update { it.copy(personal = cache.personal, remote = cache.remote, chips = cache.chips, savedAt = cache.savedAt) }
            }
        }
    }

    /** Vuelve a pedir el Inicio. Sin [force], no repite si se refrescó hace menos de 30 min. */
    suspend fun refresh(force: Boolean = false) = refreshLock.withLock {
        val current = state.value
        if (!force && current.remote.isNotEmpty() && System.currentTimeMillis() - current.savedAt < 30 * 60_000) return@withLock
        state.update { it.copy(loading = true, error = null) }
        try {
            coroutineScope {
                val personal = async { runCatching { personalSections() }.getOrDefault(emptyList()) }
                val remote = async { music.home() }
                val page = remote.await()
                val personalSections = personal.await()
                state.update {
                    it.copy(
                        personal = personalSections,
                        remote = page.sections,
                        chips = page.chips,
                        continuation = page.continuation,
                        loading = false,
                        savedAt = System.currentTimeMillis(),
                    )
                }
                save()
            }
        } catch (e: Exception) {
            state.update { it.copy(loading = false, error = e.message ?: "No se pudo cargar el inicio") }
        }
    }

    suspend fun loadMore() {
        val token = state.value.continuation ?: return
        runCatching { music.homeMore(token) }.onSuccess { page ->
            state.update { it.copy(remote = (it.remote + page.sections).distinctBy { s -> s.title }, continuation = page.continuation) }
        }.onFailure {
            state.update { it.copy(continuation = null) }
        }
    }

    suspend fun chipSections(chip: Chip): List<Section> = music.homeChip(chip.endpoint).sections

    private suspend fun personalSections(): List<Section> = coroutineScope {
        val top = library.topSongs(days = 30, limit = 60)
        val recent = library.recentlyPlayed.first()
        val sections = mutableListOf<Section>()

        // Tus mixes: una radio por cada uno de tus artistas más escuchados.
        val seeds = (top + recent).filter { it.artists.isNotEmpty() }.distinctBy { it.artists.first().name }.take(6)
        if (seeds.isNotEmpty()) {
            val mixes = seeds.mapIndexed { index, seed ->
                val artist = seed.artists.first().name
                val others = (top + recent).filter { it.artists.firstOrNull()?.name != artist }
                    .flatMap { it.artists.take(1) }.map { it.name }.distinct().take(2)
                RadioItem(
                    id = "radio:${seed.id}",
                    title = "Mix ${index + 1}",
                    subtitle = (listOf(artist) + others).joinToString(", ") + " y más",
                    thumbnailUrl = seed.thumbnailUrl,
                    seed = seed,
                )
            }
            sections += Section("Hecho para ti", mixes, subtitle = "Mixes infinitos con lo que más escuchas")
        }

        // Lo nuevo de los artistas que sigues y más escuchas.
        runCatching { releases() }.getOrNull()?.takeIf { it.isNotEmpty() }?.let { items ->
            sections += Section("Lanzamientos de tus artistas", items, subtitle = "Lo último de quienes sigues y más escuchas")
        }

        // Porque escuchaste…: lo relacionado con tu canción favorita del mes.
        val anchor = top.firstOrNull { it.source == com.lyra.music.data.model.Source.YOUTUBE }
            ?: recent.firstOrNull { it.source == com.lyra.music.data.model.Source.YOUTUBE }
        if (anchor != null) {
            val related = runCatching { music.related(anchor) }.getOrNull()
            related?.sections?.firstOrNull { section -> section.items.any { it is Song } }?.let { section ->
                sections += section.copy(title = "Porque escuchaste ${anchor.title}", more = null, style = SectionStyle.SONG_GRID)
            }
            related?.sections?.firstOrNull { section -> section.items.all { it is com.lyra.music.data.model.ArtistItem } }?.let {
                sections += it.copy(title = "Artistas parecidos a ${anchor.artists.firstOrNull()?.name ?: anchor.title}", more = null)
            }
        }
        sections
    }

    private suspend fun save() = withContext(Dispatchers.IO) {
        val s = state.value
        runCatching {
            cacheFile.writeText(json.encodeToString(Cache.serializer(), Cache(s.personal, s.remote, s.chips, s.savedAt)))
        }
    }
}
