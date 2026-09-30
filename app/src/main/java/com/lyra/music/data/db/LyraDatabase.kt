package com.lyra.music.data.db

import android.content.Context
import androidx.room.AutoMigration
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.TypeConverters

@Database(
    entities = [
        SongEntity::class,
        PlaylistEntity::class,
        PlaylistSongEntity::class,
        SavedAlbumEntity::class,
        FollowedArtistEntity::class,
        PlayEventEntity::class,
        SearchHistoryEntity::class,
        DownloadEntity::class,
        LyricsEntity::class,
        PlaylistFolderEntity::class,
    ],
    version = 3,
    exportSchema = true,
    // v2: playlists sincronizadas (syncEnabled, autoDownload, lastSyncedAt).
    // v3: carpetas de playlists y portadas propias (folderId, customCover).
    autoMigrations = [AutoMigration(from = 1, to = 2), AutoMigration(from = 2, to = 3)],
)
@TypeConverters(Converters::class)
abstract class LyraDatabase : RoomDatabase() {
    abstract fun songs(): SongDao
    abstract fun playlists(): PlaylistDao
    abstract fun library(): LibraryDao
    abstract fun history(): HistoryDao
    abstract fun searches(): SearchDao
    abstract fun downloads(): DownloadDao
    abstract fun lyrics(): LyricsDao
    abstract fun folders(): FolderDao

    companion object {
        fun build(context: Context): LyraDatabase =
            Room.databaseBuilder(context, LyraDatabase::class.java, "lyra.db")
                // Las migraciones futuras se añaden aquí; nunca se borran datos del usuario.
                .build()
    }
}
