package com.lyra.music.data.model

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/**
 * Todos los identificadores llevan el origen delante: `yt:<id>` o `sc:<ruta>`.
 * Así un mismo campo sirve para las dos fuentes y nunca chocan entre sí.
 */
enum class Source(val prefix: String, val label: String) {
    YOUTUBE("yt", "YouTube Music"),
    SOUNDCLOUD("sc", "SoundCloud");

    companion object {
        fun of(id: String): Source = if (id.startsWith("sc:")) SOUNDCLOUD else YOUTUBE
    }
}

fun String.remoteId(): String = substringAfter(':')

@Serializable
data class ArtistRef(val name: String, val id: String? = null)

@Serializable
data class AlbumRef(val title: String, val id: String? = null)

@Serializable
sealed interface MusicItem {
    val id: String
    val title: String
    val thumbnailUrl: String?
}

@Serializable
@SerialName("song")
data class Song(
    override val id: String,
    override val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val album: AlbumRef? = null,
    val durationMs: Long? = null,
    override val thumbnailUrl: String? = null,
    val isVideo: Boolean = false,
    val explicit: Boolean = false,
) : MusicItem {
    val source: Source get() = Source.of(id)
    val artistsText: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
@SerialName("album")
data class AlbumItem(
    override val id: String,
    override val title: String,
    val artists: List<ArtistRef> = emptyList(),
    val year: String? = null,
    override val thumbnailUrl: String? = null,
    /** "Álbum", "Single", "EP"… tal como lo da la fuente. */
    val kind: String? = null,
) : MusicItem {
    val artistsText: String get() = artists.joinToString(", ") { it.name }
}

@Serializable
@SerialName("artist")
data class ArtistItem(
    override val id: String,
    override val title: String,
    override val thumbnailUrl: String? = null,
    val subtitle: String? = null,
) : MusicItem

@Serializable
@SerialName("playlist")
data class PlaylistItem(
    override val id: String,
    override val title: String,
    val author: String? = null,
    override val thumbnailUrl: String? = null,
    val songCountText: String? = null,
) : MusicItem

/** Un "mix" del inicio: al tocarlo empieza la radio de la canción semilla. */
@Serializable
@SerialName("radio")
data class RadioItem(
    override val id: String,
    override val title: String,
    val subtitle: String,
    override val thumbnailUrl: String? = null,
    val seed: Song,
) : MusicItem

@Serializable
data class BrowseEndpoint(val browseId: String, val params: String? = null)

@Serializable
enum class SectionStyle { CAROUSEL, SONG_GRID, LIST }

@Serializable
data class Section(
    val title: String,
    val items: List<MusicItem>,
    val subtitle: String? = null,
    val more: BrowseEndpoint? = null,
    val style: SectionStyle = SectionStyle.CAROUSEL,
)

@Serializable
data class Chip(val title: String, val endpoint: BrowseEndpoint)

data class HomePage(
    val sections: List<Section>,
    val chips: List<Chip>,
    val continuation: String?,
)

data class SearchPage(
    val items: List<MusicItem>,
    val continuation: String? = null,
    val topResult: MusicItem? = null,
)

data class SearchSuggestions(
    val queries: List<String>,
    val items: List<MusicItem>,
)

data class AlbumPage(
    val album: AlbumItem,
    val songs: List<Song>,
    val subtitle: String? = null,
    val description: String? = null,
    val sections: List<Section> = emptyList(),
)

data class ArtistPage(
    val artist: ArtistItem,
    val bannerUrl: String? = null,
    val description: String? = null,
    val listeners: String? = null,
    val topSongs: List<Song> = emptyList(),
    val topSongsMore: BrowseEndpoint? = null,
    val radioPlaylistId: String? = null,
    val sections: List<Section> = emptyList(),
)

data class PlaylistPage(
    val playlist: PlaylistItem,
    val songs: List<Song>,
    val continuation: String? = null,
    val subtitle: String? = null,
    val description: String? = null,
)

data class RadioPage(
    val songs: List<Song>,
    val continuation: String? = null,
    val relatedBrowseId: String? = null,
    val lyricsBrowseId: String? = null,
)

data class BrowsePage(
    val title: String?,
    val sections: List<Section>,
)

data class MoodCategory(val title: String, val endpoint: BrowseEndpoint)

data class MoodGroup(val title: String, val categories: List<MoodCategory>)
