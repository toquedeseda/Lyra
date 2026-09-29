package com.lyra.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lyra.music.LyraApp
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.SearchFilter
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.playback.MediaItems.toMediaItem
import kotlinx.coroutines.flow.first
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Árbol de navegación para Android Auto:
 *   Para ti · Me gusta · Descargas · Recientes · Playlists → (cada playlist)
 * Cada canción lleva su contexto en el id (`liked::yt:abc`) para que, al tocarla,
 * se reproduzca la lista entera empezando por ella.
 */
class AutoLibrary(private val context: Context) {

    private val container get() = (context.applicationContext as LyraApp).container

    val root: MediaItem = browsable(ROOT, "Lyra")

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> listOf(
            browsable(FOR_YOU, "Para ti"),
            browsable(LIKED, "Me gusta"),
            browsable(DOWNLOADS, "Descargas"),
            browsable(RECENT, "Recientes"),
            browsable(PLAYLISTS, "Playlists"),
        )
        parentId == PLAYLISTS -> container.database.playlists().summaries().first().map {
            browsable("$PLAYLIST_PREFIX${it.id}", it.name, "${it.songCount} canciones")
        }
        else -> songsFor(parentId).map { playable(it, parentId) }
    }

    suspend fun songsFor(contextId: String): List<Song> {
        val c = container
        return when {
            contextId == FOR_YOU -> {
                val feed = c.home.feed.value
                feed.sections.flatMap { it.items }.filterIsInstance<Song>().distinctBy { it.id }.take(50)
                    .ifEmpty { c.library.topSongs(30, 50) }
            }
            contextId == LIKED -> c.library.likedList()
            contextId == DOWNLOADS -> c.downloads.downloads.first()
                .filter { it.downloadState == DownloadState.COMPLETED }.map { it.toSong() }
            contextId == RECENT -> c.database.songs().recentlyPlayedList(50).map { it.toSong() }
            contextId.startsWith(PLAYLIST_PREFIX) ->
                c.library.playlistSongList(contextId.removePrefix(PLAYLIST_PREFIX).toLongOrNull() ?: -1)
            contextId.startsWith(SEARCH_PREFIX) -> search(contextId.removePrefix(SEARCH_PREFIX))
            else -> emptyList()
        }
    }

    suspend fun search(query: String): List<Song> =
        container.music.innerTube.search(query, SearchFilter.SONGS).items.filterIsInstance<Song>().take(30)

    fun playable(song: Song, contextId: String): MediaItem {
        val base = song.toMediaItem(container.downloads.localCover(song.id))
        val artwork = container.downloads.localCover(song.id)?.let { ArtworkProvider.fileUri(context, it) }
            ?: hiResArtwork(song.thumbnailUrl, 480)?.let { ArtworkProvider.remoteUri(context, it) }
        return base.buildUpon()
            .setMediaId("$contextId::${song.id}")
            .setMediaMetadata(base.mediaMetadata.buildUpon().setArtworkUri(artwork).build())
            .build()
    }

    private fun browsable(id: String, title: String, subtitle: String? = null): MediaItem =
        MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .build(),
            )
            .build()

    companion object {
        const val ROOT = "root"
        const val FOR_YOU = "for_you"
        const val LIKED = "liked"
        const val DOWNLOADS = "downloads"
        const val RECENT = "recent"
        const val PLAYLISTS = "playlists"
        const val PLAYLIST_PREFIX = "playlist:"
        const val SEARCH_PREFIX = "search:"
    }
}

/**
 * Android Auto solo carga carátulas desde `content://`. Este proveedor las
 * descarga (solo de los servidores de imágenes de YouTube y SoundCloud) y las sirve.
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val container = (context.applicationContext as LyraApp).container
        uri.getQueryParameter("f")?.let { path ->
            val file = File(path)
            val allowed = file.canonicalPath.startsWith(container.downloads.coversDirectory.canonicalPath)
            return if (allowed && file.exists()) ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY) else null
        }
        val remote = uri.getQueryParameter("u") ?: return null
        val host = Uri.parse(remote).host.orEmpty()
        if (ALLOWED_HOSTS.none { host.endsWith(it) }) return null
        val dir = File(context.cacheDir, "artwork").apply { mkdirs() }
        val file = File(dir, sha1(remote) + ".jpg")
        if (!file.exists()) {
            container.http.newCall(Request.Builder().url(remote).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                file.outputStream().use { response.body.byteStream().copyTo(it) }
            }
        }
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri) = "image/jpeg"
    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    companion object {
        private val ALLOWED_HOSTS = listOf("googleusercontent.com", "ytimg.com", "ggpht.com", "sndcdn.com")

        private fun authority(context: Context) = context.packageName + ".artwork"

        fun remoteUri(context: Context, url: String): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).path("a").appendQueryParameter("u", url).build()

        fun fileUri(context: Context, file: File): Uri =
            Uri.Builder().scheme("content").authority(authority(context)).path("f").appendQueryParameter("f", file.absolutePath).build()

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
