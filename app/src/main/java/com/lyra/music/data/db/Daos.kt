package com.lyra.music.data.db

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import androidx.room.Upsert
import com.lyra.music.data.model.Song
import kotlinx.coroutines.flow.Flow

private const val SONG_WITH_DOWNLOAD = """
    SELECT s.id, s.title, s.artists, s.albumTitle, s.albumId, s.durationMs, s.thumbnailUrl, s.isVideo,
           s.explicit, s.likedAt, d.state AS downloadState, d.progress, d.downloadedBytes, d.totalBytes,
           d.coverPath, d.error
    FROM songs s LEFT JOIN downloads d ON d.songId = s.id
"""

@Dao
interface SongDao {
    @Query("SELECT * FROM songs WHERE id = :id")
    suspend fun get(id: String): SongEntity?

    @Query("SELECT * FROM songs WHERE id IN (:ids)")
    suspend fun getAll(ids: List<String>): List<SongEntity>

    @Query("SELECT * FROM songs WHERE id = :id")
    fun observe(id: String): Flow<SongEntity?>

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(song: SongEntity): Long

    @Upsert
    suspend fun upsert(song: SongEntity)

    @Upsert
    suspend fun upsertAll(songs: List<SongEntity>)

    /** Guarda la canción conservando favoritos y estadísticas si ya existía. */
    @Transaction
    suspend fun save(song: Song) {
        val existing = get(song.id)
        upsert(existing?.withMetadata(song) ?: SongEntity.from(song))
    }

    @Transaction
    suspend fun saveAll(songs: List<Song>) {
        val existing = getAll(songs.map { it.id }).associateBy { it.id }
        upsertAll(songs.map { existing[it.id]?.withMetadata(it) ?: SongEntity.from(it) })
    }

    @Query("UPDATE songs SET likedAt = :likedAt WHERE id = :id")
    suspend fun setLikedAt(id: String, likedAt: Long?)

    @Query("$SONG_WITH_DOWNLOAD WHERE s.likedAt IS NOT NULL ORDER BY s.likedAt DESC")
    fun liked(): Flow<List<SongWithDownload>>

    @Query("SELECT id FROM songs WHERE likedAt IS NOT NULL")
    fun likedIds(): Flow<List<String>>

    @Query("SELECT * FROM songs WHERE likedAt IS NOT NULL ORDER BY likedAt DESC")
    suspend fun likedList(): List<SongEntity>

    @Query(
        """UPDATE songs SET playCount = playCount + 1, totalPlayMs = totalPlayMs + :playedMs,
           lastPlayedAt = :now WHERE id = :id""",
    )
    suspend fun recordPlay(id: String, playedMs: Long, now: Long)

    @Query(
        """SELECT * FROM songs WHERE lastPlayedAt IS NOT NULL
           ORDER BY lastPlayedAt DESC LIMIT :limit""",
    )
    fun recentlyPlayed(limit: Int): Flow<List<SongEntity>>

    @Query(
        """SELECT * FROM songs WHERE lastPlayedAt IS NOT NULL
           ORDER BY lastPlayedAt DESC LIMIT :limit""",
    )
    suspend fun recentlyPlayedList(limit: Int): List<SongEntity>

    @Query(
        """SELECT * FROM songs WHERE title LIKE '%' || :query || '%' OR artists LIKE '%' || :query || '%'
           ORDER BY (likedAt IS NOT NULL) DESC, playCount DESC LIMIT :limit""",
    )
    suspend fun searchLocal(query: String, limit: Int): List<SongEntity>

    @Query("SELECT * FROM songs")
    suspend fun all(): List<SongEntity>

    /** Canciones de tu biblioteca: favoritas, descargadas o en alguna de tus listas (las más escuchadas primero). */
    @Query(
        """$SONG_WITH_DOWNLOAD WHERE s.likedAt IS NOT NULL OR d.songId IS NOT NULL
           OR s.id IN (SELECT songId FROM playlist_songs)
           ORDER BY s.playCount DESC, s.title COLLATE NOCASE""",
    )
    fun inLibrary(): Flow<List<SongWithDownload>>
}

@Dao
interface PlaylistDao {
    @Query(
        """SELECT p.id, p.name, p.description, p.updatedAt, p.remoteId, p.coverUrl,
                  COUNT(ps.songId) AS songCount, p.syncEnabled
           FROM playlists p LEFT JOIN playlist_songs ps ON ps.playlistId = p.id
           GROUP BY p.id ORDER BY p.updatedAt DESC""",
    )
    fun summaries(): Flow<List<PlaylistSummary>>

    @Query("SELECT * FROM playlists WHERE id = :id")
    fun observe(id: Long): Flow<PlaylistEntity?>

    @Query("SELECT * FROM playlists WHERE id = :id")
    suspend fun get(id: Long): PlaylistEntity?

    @Query("SELECT * FROM playlists WHERE remoteId = :remoteId LIMIT 1")
    suspend fun byRemoteId(remoteId: String): PlaylistEntity?

    @Query("SELECT * FROM playlists")
    suspend fun all(): List<PlaylistEntity>

    @Insert
    suspend fun insert(playlist: PlaylistEntity): Long

    @Query("UPDATE playlists SET name = :name, description = :description, updatedAt = :now WHERE id = :id")
    suspend fun rename(id: Long, name: String, description: String?, now: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET updatedAt = :now WHERE id = :id")
    suspend fun touch(id: Long, now: Long = System.currentTimeMillis())

    @Query("UPDATE playlists SET syncEnabled = :sync, autoDownload = :autoDownload WHERE id = :id")
    suspend fun setSync(id: Long, sync: Boolean, autoDownload: Boolean)

    @Query("UPDATE playlists SET lastSyncedAt = :time WHERE id = :id")
    suspend fun markSynced(id: Long, time: Long = System.currentTimeMillis())

    @Query("SELECT * FROM playlists WHERE syncEnabled = 1 AND remoteId IS NOT NULL")
    suspend fun syncable(): List<PlaylistEntity>

    @Query("DELETE FROM playlists WHERE id = :id")
    suspend fun delete(id: Long)

    @Query("$SONG_WITH_DOWNLOAD JOIN playlist_songs ps ON ps.songId = s.id WHERE ps.playlistId = :playlistId ORDER BY ps.position")
    fun songs(playlistId: Long): Flow<List<SongWithDownload>>

    @Query("SELECT s.* FROM songs s JOIN playlist_songs ps ON ps.songId = s.id WHERE ps.playlistId = :playlistId ORDER BY ps.position")
    suspend fun songList(playlistId: Long): List<SongEntity>

    @Query("SELECT * FROM playlist_songs WHERE playlistId = :playlistId ORDER BY position")
    suspend fun entries(playlistId: Long): List<PlaylistSongEntity>

    @Query("SELECT * FROM playlist_songs")
    suspend fun allEntries(): List<PlaylistSongEntity>

    @Query(
        """SELECT s.thumbnailUrl FROM songs s JOIN playlist_songs ps ON ps.songId = s.id
           WHERE ps.playlistId = :playlistId AND s.thumbnailUrl IS NOT NULL ORDER BY ps.position LIMIT 4""",
    )
    fun covers(playlistId: Long): Flow<List<String>>

    @Query("SELECT COALESCE(MAX(position), -1) FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun maxPosition(playlistId: Long): Int

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertEntries(entries: List<PlaylistSongEntity>)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId AND songId = :songId")
    suspend fun removeSong(playlistId: Long, songId: String)

    @Query("DELETE FROM playlist_songs WHERE playlistId = :playlistId")
    suspend fun clear(playlistId: Long)

    @Query("SELECT playlistId FROM playlist_songs WHERE songId = :songId")
    fun playlistsContaining(songId: String): Flow<List<Long>>

    @Transaction
    suspend fun addSongs(playlistId: Long, songIds: List<String>) {
        var position = maxPosition(playlistId)
        insertEntries(songIds.map { PlaylistSongEntity(playlistId, it, ++position) })
        touch(playlistId)
    }

    @Transaction
    suspend fun reorder(playlistId: Long, orderedSongIds: List<String>) {
        val current = entries(playlistId).associateBy { it.songId }
        clear(playlistId)
        insertEntries(orderedSongIds.mapIndexedNotNull { index, id ->
            current[id]?.copy(position = index)
        })
        touch(playlistId)
    }
}

@Dao
interface LibraryDao {
    @Query("SELECT * FROM saved_albums ORDER BY savedAt DESC")
    fun albums(): Flow<List<SavedAlbumEntity>>

    @Query("SELECT * FROM saved_albums")
    suspend fun albumList(): List<SavedAlbumEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_albums WHERE id = :id)")
    fun isAlbumSaved(id: String): Flow<Boolean>

    @Upsert
    suspend fun saveAlbum(album: SavedAlbumEntity)

    @Upsert
    suspend fun saveAlbums(albums: List<SavedAlbumEntity>)

    @Query("DELETE FROM saved_albums WHERE id = :id")
    suspend fun removeAlbum(id: String)

    @Query("SELECT * FROM followed_artists ORDER BY followedAt DESC")
    fun artists(): Flow<List<FollowedArtistEntity>>

    @Query("SELECT * FROM followed_artists")
    suspend fun artistList(): List<FollowedArtistEntity>

    @Query("SELECT EXISTS(SELECT 1 FROM followed_artists WHERE id = :id)")
    fun isFollowing(id: String): Flow<Boolean>

    @Upsert
    suspend fun follow(artist: FollowedArtistEntity)

    @Upsert
    suspend fun followAll(artists: List<FollowedArtistEntity>)

    @Query("DELETE FROM followed_artists WHERE id = :id")
    suspend fun unfollow(id: String)
}

@Dao
interface HistoryDao {
    @Insert
    suspend fun insert(event: PlayEventEntity)

    @Insert
    suspend fun insertAll(events: List<PlayEventEntity>)

    @Query("SELECT * FROM play_events ORDER BY playedAt DESC LIMIT :limit")
    suspend fun latest(limit: Int): List<PlayEventEntity>

    @Query(
        """SELECT songId, COUNT(*) AS plays, SUM(playedMs) AS totalMs FROM play_events
           WHERE playedAt >= :since GROUP BY songId ORDER BY plays DESC, totalMs DESC LIMIT :limit""",
    )
    suspend fun topSongs(since: Long, limit: Int): List<PlayCount>

    @Query(
        """SELECT s.* FROM songs s JOIN (SELECT songId, MAX(playedAt) AS lastPlayed FROM play_events GROUP BY songId) e
           ON e.songId = s.id ORDER BY e.lastPlayed DESC LIMIT :limit""",
    )
    fun history(limit: Int): Flow<List<SongEntity>>

    @Query("SELECT DISTINCT songId FROM play_events WHERE playedAt >= :since")
    suspend fun playedSince(since: Long): List<String>

    @Query("DELETE FROM play_events")
    suspend fun clear()

    @Query("UPDATE songs SET lastPlayedAt = NULL, playCount = 0, totalPlayMs = 0")
    suspend fun clearSongStats()
}

@Dao
interface SearchDao {
    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC LIMIT :limit")
    fun recent(limit: Int): Flow<List<SearchHistoryEntity>>

    @Query("SELECT * FROM search_history ORDER BY searchedAt DESC")
    suspend fun all(): List<SearchHistoryEntity>

    @Upsert
    suspend fun save(entry: SearchHistoryEntity)

    @Upsert
    suspend fun saveAll(entries: List<SearchHistoryEntity>)

    @Query("DELETE FROM search_history WHERE `query` = :query")
    suspend fun delete(query: String)

    @Query("DELETE FROM search_history")
    suspend fun clear()
}

@Dao
interface DownloadDao {
    @Query("$SONG_WITH_DOWNLOAD WHERE d.songId IS NOT NULL ORDER BY d.state = 2, d.requestedAt DESC")
    fun all(): Flow<List<SongWithDownload>>

    @Query("SELECT * FROM downloads")
    fun states(): Flow<List<DownloadEntity>>

    @Query("SELECT * FROM downloads WHERE songId = :songId")
    suspend fun get(songId: String): DownloadEntity?

    @Query("SELECT * FROM downloads WHERE state = 2")
    suspend fun completed(): List<DownloadEntity>

    @Query("SELECT * FROM downloads WHERE state = 0 ORDER BY requestedAt LIMIT 1")
    suspend fun nextQueued(): DownloadEntity?

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN (0, 1)")
    suspend fun pendingCount(): Int

    @Query("SELECT COUNT(*) FROM downloads WHERE state IN (0, 1)")
    fun pendingCountFlow(): Flow<Int>

    @Query("SELECT COALESCE(SUM(totalBytes), 0) FROM downloads WHERE state = 2")
    fun totalBytes(): Flow<Long>

    @Upsert
    suspend fun upsert(entity: DownloadEntity)

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertIgnore(entities: List<DownloadEntity>): List<Long>

    @Query("UPDATE downloads SET state = :state, error = :error WHERE songId = :songId")
    suspend fun setState(songId: String, state: Int, error: String? = null)

    @Query("UPDATE downloads SET progress = :progress, downloadedBytes = :downloaded, totalBytes = :total WHERE songId = :songId")
    suspend fun setProgress(songId: String, progress: Float, downloaded: Long, total: Long)

    @Query("UPDATE downloads SET state = 0, error = NULL, progress = 0 WHERE state IN (1, 3)")
    suspend fun requeueInterrupted()

    @Query("UPDATE downloads SET state = 0, error = NULL WHERE state = 3")
    suspend fun retryFailed()

    @Delete
    suspend fun delete(entity: DownloadEntity)

    @Query("DELETE FROM downloads WHERE songId = :songId")
    suspend fun delete(songId: String)

    @Query("DELETE FROM downloads")
    suspend fun clear()
}

@Dao
interface LyricsDao {
    @Query("SELECT * FROM lyrics WHERE songId = :songId")
    suspend fun get(songId: String): LyricsEntity?

    @Upsert
    suspend fun save(entity: LyricsEntity)
}
