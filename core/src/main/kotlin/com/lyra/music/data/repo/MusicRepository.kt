package com.lyra.music.data.repo

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.AlbumPage
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.ArtistPage
import com.lyra.music.data.model.BrowseEndpoint
import com.lyra.music.data.model.BrowsePage
import com.lyra.music.data.model.HomePage
import com.lyra.music.data.model.MoodGroup
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.PlaylistPage
import com.lyra.music.data.model.RadioPage
import com.lyra.music.data.model.SearchPage
import com.lyra.music.data.model.SearchSuggestions
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.innertube.SearchFilter
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.data.source.soundcloud.SoundCloudFilter
import org.schabi.newpipe.extractor.Page
import java.net.URI
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

enum class SearchTab(val label: String) {
    ALL("Todo"),
    SONGS("Canciones"),
    VIDEOS("Vídeos"),
    ALBUMS("Álbumes"),
    ARTISTS("Artistas"),
    PLAYLISTS("Playlists"),
    SOUNDCLOUD("SoundCloud"),
}

/** Lo que hay detrás de un enlace pegado en el buscador. */
sealed interface LinkTarget {
    data class PlaySong(val song: Song) : LinkTarget
    data class Open(val item: MusicItem) : LinkTarget
}

/** Punto único para leer música de YouTube Music y SoundCloud. */
class MusicRepository(
    val innerTube: InnerTube,
    val newPipe: NewPipeSource,
) {
    private val soundCloudPages = ConcurrentHashMap<String, Pair<String, Page>>()

    suspend fun search(query: String, tab: SearchTab): SearchPage = when (tab) {
        SearchTab.ALL -> innerTube.search(query, SearchFilter.ALL)
        SearchTab.SONGS -> innerTube.search(query, SearchFilter.SONGS)
        SearchTab.VIDEOS -> innerTube.search(query, SearchFilter.VIDEOS)
        SearchTab.ALBUMS -> innerTube.search(query, SearchFilter.ALBUMS)
        SearchTab.ARTISTS -> innerTube.search(query, SearchFilter.ARTISTS)
        SearchTab.PLAYLISTS -> {
            val featured = innerTube.search(query, SearchFilter.FEATURED_PLAYLISTS)
            val community = runCatching { innerTube.search(query, SearchFilter.COMMUNITY_PLAYLISTS) }.getOrNull()
            SearchPage((featured.items + community?.items.orEmpty()).distinctBy { it.id }, community?.continuation)
        }
        SearchTab.SOUNDCLOUD -> {
            val result = newPipe.searchSoundCloud(query, SoundCloudFilter.TRACKS)
            val users = runCatching { newPipe.searchSoundCloud(query, SoundCloudFilter.USERS).items.take(3) }
                .getOrDefault(emptyList())
            val playlists = runCatching { newPipe.searchSoundCloud(query, SoundCloudFilter.PLAYLISTS).items.take(4) }
                .getOrDefault(emptyList())
            SearchPage(users + result.items + playlists, rememberPage(query, result.nextPage))
        }
    }

    suspend fun searchMore(token: String): SearchPage {
        val soundCloud = soundCloudPages.remove(token)
        if (soundCloud != null) {
            val (query, page) = soundCloud
            val result = newPipe.searchSoundCloud(query, SoundCloudFilter.TRACKS, page)
            return SearchPage(result.items, rememberPage(query, result.nextPage))
        }
        return innerTube.searchMore(token)
    }

    private fun rememberPage(query: String, page: Page?): String? {
        page ?: return null
        val token = "sc-" + UUID.randomUUID()
        soundCloudPages[token] = query to page
        return token
    }

    suspend fun suggestions(input: String): SearchSuggestions = innerTube.suggestions(input)

    suspend fun album(id: String): AlbumPage = innerTube.album(id)

    suspend fun artist(id: String): ArtistPage = when (Source.of(id)) {
        Source.YOUTUBE -> innerTube.artist(id)
        Source.SOUNDCLOUD -> newPipe.soundCloudUser(id)
    }

    suspend fun playlist(id: String): PlaylistPage = when (Source.of(id)) {
        Source.YOUTUBE -> innerTube.playlist(id)
        Source.SOUNDCLOUD -> newPipe.soundCloudPlaylist(id)
    }

    suspend fun playlistMore(token: String): Pair<List<Song>, String?> = innerTube.playlistMore(token)

    /** Todas las canciones de una playlist, recorriendo todas sus páginas. */
    suspend fun fullPlaylist(id: String): PlaylistPage {
        val first = playlist(id)
        var token = first.continuation
        val songs = first.songs.toMutableList()
        var guard = 0
        while (token != null && guard++ < 50) {
            val (more, next) = playlistMore(token)
            if (more.isEmpty()) break
            songs += more
            token = next
        }
        return first.copy(songs = songs.distinctBy { it.id }, continuation = null)
    }

    /** Radio de una canción. En SoundCloud se usan sus pistas relacionadas. */
    suspend fun radio(song: Song): RadioPage = when (song.source) {
        Source.YOUTUBE -> innerTube.radio(song.id)
        Source.SOUNDCLOUD -> RadioPage(listOf(song) + newPipe.soundCloudRelated(song.id))
    }

    suspend fun radioMore(token: String): RadioPage = innerTube.radioMore(token)

    /**
     * Datos completos (carátula, álbum, duración…) de canciones de las que solo se sabe el id,
     * p. ej. las de una playlist compartida. Las de SoundCloud se quedan como están.
     */
    suspend fun songsByIds(ids: List<String>): Map<String, Song> =
        ids.filter { Source.of(it) == Source.YOUTUBE }.distinct().chunked(50)
            .flatMap { chunk -> runCatching { innerTube.songs(chunk) }.getOrDefault(emptyList()) }
            .associateBy { it.id }

    suspend fun radioFromPlaylist(playlistId: String): RadioPage = innerTube.radioFromPlaylist(playlistId)

    suspend fun related(song: Song): BrowsePage? {
        if (song.source != Source.YOUTUBE) return null
        val id = innerTube.radio(song.id).relatedBrowseId ?: return null
        return innerTube.related(id)
    }

    suspend fun home(): HomePage = innerTube.home()
    suspend fun homeMore(token: String): HomePage = innerTube.homeMore(token)
    suspend fun homeChip(endpoint: BrowseEndpoint): HomePage = innerTube.homeWithChip(endpoint)
    suspend fun browse(endpoint: BrowseEndpoint): BrowsePage = innerTube.browse(endpoint)
    suspend fun moods(): List<MoodGroup> = innerTube.moods()

    /** Reconoce enlaces de YouTube, YouTube Music y SoundCloud. */
    suspend fun resolveLink(text: String): LinkTarget? {
        val url = text.trim().takeIf { it.startsWith("http") } ?: return null
        val uri = runCatching { URI(url) }.getOrNull() ?: return null
        val host = uri.host?.removePrefix("www.")?.removePrefix("m.") ?: return null
        val path = uri.path.orEmpty()
        val params = uri.rawQuery.orEmpty().split('&').mapNotNull {
            val parts = it.split('=', limit = 2)
            if (parts.size == 2) parts[0] to java.net.URLDecoder.decode(parts[1], "UTF-8") else null
        }.toMap()

        return when {
            host == "youtu.be" -> songFromVideo(path.trim('/'))
            host.endsWith("youtube.com") -> when {
                path.startsWith("/watch") -> params["v"]?.let { songFromVideo(it) }
                path.startsWith("/playlist") -> params["list"]?.let {
                    LinkTarget.Open(PlaylistItem("yt:VL$it", "Playlist"))
                }
                path.startsWith("/browse/MPREb") -> LinkTarget.Open(AlbumItem("yt:" + path.substringAfterLast('/'), "Álbum"))
                path.startsWith("/channel/") -> LinkTarget.Open(ArtistItem("yt:" + path.substringAfter("/channel/").trim('/'), "Artista"))
                path.startsWith("/shorts/") -> songFromVideo(path.substringAfter("/shorts/").trim('/'))
                else -> null
            }
            host.endsWith("soundcloud.com") -> {
                val parts = path.trim('/').split('/').filter { it.isNotEmpty() }
                val id = "sc:" + parts.joinToString("/")
                when {
                    parts.isEmpty() -> null
                    parts.size == 1 -> LinkTarget.Open(ArtistItem(id, parts[0]))
                    parts.getOrNull(1) == "sets" -> LinkTarget.Open(PlaylistItem(id, parts.getOrElse(2) { "Playlist" }))
                    else -> newPipe.soundCloudTrack(id)?.let { LinkTarget.PlaySong(it) }
                }
            }
            else -> null
        }
    }

    private suspend fun songFromVideo(videoId: String): LinkTarget? {
        if (videoId.isBlank()) return null
        // La radio de un vídeo empieza por el propio vídeo, con todos sus datos.
        val song = innerTube.radio("yt:$videoId").songs.firstOrNull { it.id == "yt:$videoId" } ?: return null
        return LinkTarget.PlaySong(song)
    }
}
