package com.lyra.music.data.db

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import androidx.room.TypeConverter
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.AlbumRef
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import kotlinx.serialization.KSerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json

@Entity(tableName = "songs")
data class SongEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val albumTitle: String? = null,
    val albumId: String? = null,
    val durationMs: Long? = null,
    val thumbnailUrl: String? = null,
    val isVideo: Boolean = false,
    val explicit: Boolean = false,
    val likedAt: Long? = null,
    val playCount: Int = 0,
    val totalPlayMs: Long = 0,
    val lastPlayedAt: Long? = null,
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artists = artists,
        album = albumTitle?.let { AlbumRef(it, albumId) },
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        isVideo = isVideo,
        explicit = explicit,
    )

    /** Actualiza los metadatos sin tocar favoritos ni estadísticas. */
    fun withMetadata(song: Song) = copy(
        title = song.title,
        artists = song.artists.ifEmpty { artists },
        albumTitle = song.album?.title ?: albumTitle,
        albumId = song.album?.id ?: albumId,
        durationMs = song.durationMs ?: durationMs,
        thumbnailUrl = song.thumbnailUrl ?: thumbnailUrl,
        isVideo = song.isVideo,
        explicit = song.explicit || explicit,
    )

    companion object {
        fun from(song: Song) = SongEntity(
            id = song.id,
            title = song.title,
            artists = song.artists,
            albumTitle = song.album?.title,
            albumId = song.album?.id,
            durationMs = song.durationMs,
            thumbnailUrl = song.thumbnailUrl,
            isVideo = song.isVideo,
            explicit = song.explicit,
        )
    }
}

@Entity(tableName = "playlists")
data class PlaylistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val description: String? = null,
    val createdAt: Long = System.currentTimeMillis(),
    val updatedAt: Long = System.currentTimeMillis(),
    /** Si se importó de YouTube Music, SoundCloud o Spotify, su id original (para re-sincronizar). */
    val remoteId: String? = null,
    val coverUrl: String? = null,
    /** Se mantiene al día con la original (canciones nuevas). */
    @ColumnInfo(defaultValue = "0") val syncEnabled: Boolean = false,
    /** Las canciones nuevas se descargan solas. */
    @ColumnInfo(defaultValue = "0") val autoDownload: Boolean = false,
    val lastSyncedAt: Long? = null,
    /** Carpeta de la biblioteca en la que está (null = suelta). */
    val folderId: Long? = null,
    /** Portada elegida por ti (imagen guardada en la app); manda sobre el mosaico. */
    val customCover: String? = null,
)

/** Carpeta para agrupar playlists en la biblioteca. */
@Entity(tableName = "playlist_folders")
data class PlaylistFolderEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val name: String,
    val createdAt: Long = System.currentTimeMillis(),
)

data class FolderSummary(val id: Long, val name: String, val playlistCount: Int)

@Entity(
    tableName = "playlist_songs",
    primaryKeys = ["playlistId", "songId"],
    indices = [Index("songId")],
)
data class PlaylistSongEntity(
    val playlistId: Long,
    val songId: String,
    val position: Int,
    val addedAt: Long = System.currentTimeMillis(),
)

@Entity(tableName = "saved_albums")
data class SavedAlbumEntity(
    @PrimaryKey val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val year: String? = null,
    val thumbnailUrl: String? = null,
    val kind: String? = null,
    val savedAt: Long = System.currentTimeMillis(),
) {
    fun toItem() = AlbumItem(id, title, artists, year, thumbnailUrl, kind)

    companion object {
        fun from(album: AlbumItem) =
            SavedAlbumEntity(album.id, album.title, album.artists, album.year, album.thumbnailUrl, album.kind)
    }
}

@Entity(tableName = "followed_artists")
data class FollowedArtistEntity(
    @PrimaryKey val id: String,
    val name: String,
    val thumbnailUrl: String? = null,
    val followedAt: Long = System.currentTimeMillis(),
) {
    fun toItem() = ArtistItem(id, name, thumbnailUrl)
}

@Entity(tableName = "play_events", indices = [Index("songId"), Index("playedAt")])
data class PlayEventEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val songId: String,
    val playedAt: Long,
    val playedMs: Long,
)

@Entity(tableName = "search_history")
data class SearchHistoryEntity(
    @PrimaryKey val query: String,
    val searchedAt: Long = System.currentTimeMillis(),
)

object DownloadState {
    const val QUEUED = 0
    const val DOWNLOADING = 1
    const val COMPLETED = 2
    const val FAILED = 3
    const val PAUSED = 4
}

@Entity(tableName = "downloads", indices = [Index("state")])
data class DownloadEntity(
    @PrimaryKey val songId: String,
    val state: Int = DownloadState.QUEUED,
    val progress: Float = 0f,
    val downloadedBytes: Long = 0,
    val totalBytes: Long = 0,
    val filePath: String? = null,
    val coverPath: String? = null,
    val mimeType: String? = null,
    val bitrate: Int = 0,
    val requestedAt: Long = System.currentTimeMillis(),
    val completedAt: Long? = null,
    val error: String? = null,
)

@Entity(tableName = "lyrics")
data class LyricsEntity(
    @PrimaryKey val songId: String,
    val synced: String? = null,
    val plain: String? = null,
    val source: String? = null,
    val fetchedAt: Long = System.currentTimeMillis(),
    val notFound: Boolean = false,
)

/** Resultado de consultas que juntan canciones con su estado de descarga. */
data class SongWithDownload(
    val id: String,
    val title: String,
    val artists: List<ArtistRef>,
    val albumTitle: String?,
    val albumId: String?,
    val durationMs: Long?,
    val thumbnailUrl: String?,
    val isVideo: Boolean,
    val explicit: Boolean,
    val likedAt: Long?,
    val downloadState: Int?,
    val progress: Float?,
    val downloadedBytes: Long?,
    val totalBytes: Long?,
    val coverPath: String?,
    val error: String?,
) {
    fun toSong() = Song(
        id = id,
        title = title,
        artists = artists,
        album = albumTitle?.let { AlbumRef(it, albumId) },
        durationMs = durationMs,
        thumbnailUrl = thumbnailUrl,
        isVideo = isVideo,
        explicit = explicit,
    )
}

data class PlaylistSummary(
    val id: Long,
    val name: String,
    val description: String?,
    val updatedAt: Long,
    val remoteId: String?,
    val coverUrl: String?,
    val songCount: Int,
    val syncEnabled: Boolean = false,
    val folderId: Long? = null,
    val customCover: String? = null,
)

data class PlayCount(val songId: String, val plays: Int, val totalMs: Long)

class Converters {
    private val json: Json = Json { ignoreUnknownKeys = true }

    // Tipo explícito: KSP no ve las funciones que genera el plugin de serialización.
    private val artistList: KSerializer<List<ArtistRef>> by lazy { ListSerializer(ArtistRef.serializer()) }

    @TypeConverter
    fun artistsToString(value: List<ArtistRef>): String = json.encodeToString(artistList, value)

    @TypeConverter
    fun stringToArtists(value: String): List<ArtistRef> =
        runCatching { json.decodeFromString(artistList, value) }.getOrDefault(emptyList())
}
