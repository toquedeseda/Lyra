package com.lyra.music.data.db

import android.content.Context
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
    ],
    version = 1,
    exportSchema = true,
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

    companion object {
        fun build(context: Context): LyraDatabase =
            Room.databaseBuilder(context, LyraDatabase::class.java, "lyra.db")
                // Las migraciones futuras se añaden aquí; nunca se borran datos del usuario.
                .build()
    }
}
