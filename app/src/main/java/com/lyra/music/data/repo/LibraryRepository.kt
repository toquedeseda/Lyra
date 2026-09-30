package com.lyra.music.data.repo

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.lyra.music.data.db.FolderSummary
import com.lyra.music.data.db.PlaylistFolderEntity
import com.lyra.music.data.db.FollowedArtistEntity
import com.lyra.music.data.db.LyraDatabase
import com.lyra.music.data.db.PlayEventEntity
import com.lyra.music.data.db.PlaylistEntity
import com.lyra.music.data.db.PlaylistSummary
import com.lyra.music.data.db.SavedAlbumEntity
import com.lyra.music.data.db.SearchHistoryEntity
import com.lyra.music.data.db.SongWithDownload
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.settings.SettingsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** Id especial de las playlists locales cuando se tratan como [PlaylistItem]. */
fun localPlaylistId(id: Long) = "local:$id"

/** Carpeta (dentro de files/) de las portadas elegidas para las playlists. */
const val COVERS_DIR = "playlist_covers"

const val LIKED_SONGS_ID = "local:liked"
const val DOWNLOADS_ID = "local:downloads"

/** Todo lo que es "tuyo": favoritos, playlists, álbumes, artistas e historial. */
class LibraryRepository(
    context: Context,
    private val db: LyraDatabase,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    private val appContext = context.applicationContext
    private val songs = db.songs()
    private val playlists = db.playlists()
    private val folderDao = db.folders()
    private val library = db.library()
    private val historyDao = db.history()
    private val searches = db.searches()

    // ------------------------------------------------------------ favoritos

    val likedIds: StateFlow<Set<String>> = songs.likedIds().map { it.toSet() }
        .stateIn(scope, SharingStarted.Eagerly, emptySet())

    val likedSongs: Flow<List<SongWithDownload>> = songs.liked()

    fun isLiked(songId: String): Boolean = songId in likedIds.value

    suspend fun setLiked(song: Song, liked: Boolean) {
        songs.save(song)
        songs.setLikedAt(song.id, if (liked) System.currentTimeMillis() else null)
        if (liked && settings.current.autoDownloadLiked) downloads.enqueue(listOf(song))
    }

    suspend fun toggleLike(song: Song): Boolean {
        val liked = !isLiked(song.id)
        setLiked(song, liked)
        return liked
    }

    suspend fun likedList(): List<Song> = songs.likedList().map { it.toSong() }

    // ------------------------------------------------------------ playlists

    val playlistSummaries: Flow<List<PlaylistSummary>> = playlists.summaries()

    fun playlist(id: Long) = playlists.observe(id)
    fun playlistSongs(id: Long): Flow<List<SongWithDownload>> = playlists.songs(id)
    fun playlistCovers(id: Long): Flow<List<String>> = playlists.covers(id)
    fun playlistsContaining(songId: String): Flow<List<Long>> = playlists.playlistsContaining(songId)

    suspend fun playlistSongList(id: Long): List<Song> = playlists.songList(id).map { it.toSong() }

    suspend fun createPlaylist(name: String, initial: List<Song> = emptyList(), remoteId: String? = null, cover: String? = null): Long {
        val id = playlists.insert(PlaylistEntity(name = name.trim().ifEmpty { "Mi playlist" }, remoteId = remoteId, coverUrl = cover))
        if (initial.isNotEmpty()) addToPlaylist(id, initial)
        return id
    }

    suspend fun addToPlaylist(playlistId: Long, items: List<Song>) {
        songs.saveAll(items)
        playlists.addSongs(playlistId, items.map { it.id })
    }

    suspend fun removeFromPlaylist(playlistId: Long, songId: String) {
        playlists.removeSong(playlistId, songId)
        playlists.touch(playlistId)
    }

    suspend fun reorderPlaylist(playlistId: Long, orderedIds: List<String>) = playlists.reorder(playlistId, orderedIds)

    suspend fun renamePlaylist(id: Long, name: String, description: String?) = playlists.rename(id, name, description)

    suspend fun deletePlaylist(id: Long) {
        val cover = playlists.get(id)?.customCover
        playlists.delete(id)
        deleteCoverFile(cover)
    }

    // ------------------------------------------------------------- carpetas y portadas

    val folders: Flow<List<FolderSummary>> = folderDao.summaries()

    fun folder(id: Long): Flow<PlaylistFolderEntity?> = folderDao.observe(id)

    fun folderCovers(id: Long): Flow<List<String>> = folderDao.covers(id).map { it.filterNotNull() }

    suspend fun createFolder(name: String): Long = folderDao.insert(PlaylistFolderEntity(name = name.trim()))

    suspend fun renameFolder(id: Long, name: String) = folderDao.rename(id, name.trim())

    suspend fun deleteFolder(id: Long) = folderDao.delete(id)

    suspend fun moveToFolder(playlistId: Long, folderId: Long?) = playlists.setFolder(playlistId, folderId)

    /** Pone de portada una imagen de la galería (recortada en cuadrado y guardada dentro de la app). */
    suspend fun setPlaylistCover(playlistId: Long, image: Uri): Boolean {
        val bitmap = loadSquare(image, 900) ?: return false
        val file = withContext(Dispatchers.IO) {
            val dir = File(appContext.filesDir, COVERS_DIR).apply { mkdirs() }
            File(dir, "${playlistId}_${System.currentTimeMillis()}.jpg").also { out ->
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 90, it) }
            }
        }
        val old = playlists.get(playlistId)?.customCover
        playlists.setCustomCover(playlistId, Uri.fromFile(file).toString())
        deleteCoverFile(old)
        return true
    }

    suspend fun removePlaylistCover(playlistId: Long) {
        val old = playlists.get(playlistId)?.customCover
        playlists.setCustomCover(playlistId, null)
        deleteCoverFile(old)
    }

    private suspend fun loadSquare(uri: Uri, size: Int): Bitmap? {
        val request = ImageRequest.Builder(appContext).data(uri).size(size * 2).allowHardware(false).build()
        val image = (SingletonImageLoader.get(appContext).execute(request) as? SuccessResult)?.image?.toBitmap() ?: return null
        return withContext(Dispatchers.Default) {
            val side = minOf(image.width, image.height)
            val square = Bitmap.createBitmap(image, (image.width - side) / 2, (image.height - side) / 2, side, side)
            if (side > size) Bitmap.createScaledBitmap(square, size, size, true) else square
        }
    }

    private suspend fun deleteCoverFile(cover: String?) = withContext(Dispatchers.IO) {
        val path = cover?.let { Uri.parse(it).path } ?: return@withContext
        val file = File(path)
        // Solo se borran las que guardó Lyra (nunca imágenes de otras carpetas).
        if (file.parentFile?.name == COVERS_DIR) file.delete()
    }

    /** Mantener al día con la original y/o descargar solas las canciones nuevas. */
    suspend fun setPlaylistSync(id: Long, sync: Boolean, autoDownload: Boolean) = playlists.setSync(id, sync, autoDownload)

    /** Copia una playlist de YouTube Music o SoundCloud a la biblioteca (o la re-sincroniza). */
    suspend fun importPlaylist(item: PlaylistItem, items: List<Song>): Long {
        val existing = playlists.byRemoteId(item.id)
        return if (existing != null) {
            songs.saveAll(items)
            playlists.reorder(existing.id, emptyList())
            playlists.addSongs(existing.id, items.map { it.id })
            existing.id
        } else {
            // Las playlists guardadas se mantienen sincronizadas por defecto.
            createPlaylist(item.title, items, remoteId = item.id, cover = item.thumbnailUrl).also {
                playlists.setSync(it, sync = true, autoDownload = false)
            }
        }
    }

    // ------------------------------------------------------------ álbumes y artistas

    val savedAlbums: Flow<List<AlbumItem>> = library.albums().map { list -> list.map { it.toItem() } }
    val followedArtists: Flow<List<ArtistItem>> = library.artists().map { list -> list.map { it.toItem() } }

    fun isAlbumSaved(id: String): Flow<Boolean> = library.isAlbumSaved(id)
    fun isFollowing(id: String): Flow<Boolean> = library.isFollowing(id)

    suspend fun setAlbumSaved(album: AlbumItem, saved: Boolean, tracks: List<Song> = emptyList()) {
        if (saved) {
            library.saveAlbum(SavedAlbumEntity.from(album))
            if (tracks.isNotEmpty()) songs.saveAll(tracks)
        } else {
            library.removeAlbum(album.id)
        }
    }

    suspend fun setFollowing(artist: ArtistItem, following: Boolean) {
        if (following) library.follow(FollowedArtistEntity(artist.id, artist.title, artist.thumbnailUrl))
        else library.unfollow(artist.id)
    }

    // ------------------------------------------------------------ historial

    val history: Flow<List<Song>> = historyDao.history(200).map { list -> list.map { it.toSong() } }
    val recentlyPlayed: Flow<List<Song>> = songs.recentlyPlayed(30).map { list -> list.map { it.toSong() } }

    suspend fun recordPlay(song: Song, playedMs: Long) = withContext(Dispatchers.IO) {
        songs.save(song)
        val now = System.currentTimeMillis()
        songs.recordPlay(song.id, playedMs, now)
        historyDao.insert(PlayEventEntity(songId = song.id, playedAt = now, playedMs = playedMs))
    }

    /** Canciones escuchadas en las últimas [hours] horas (para que la radio no las repita). */
    suspend fun recentlyPlayedIds(hours: Int): Set<String> =
        historyDao.playedSince(System.currentTimeMillis() - hours * 3_600_000L).toSet()

    suspend fun clearHistory() {
        historyDao.clear()
        historyDao.clearSongStats()
    }

    /** Canciones más escuchadas en los últimos [days] días. */
    suspend fun topSongs(days: Int, limit: Int): List<Song> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        val top = historyDao.topSongs(since, limit)
        val byId = songs.getAll(top.map { it.songId }).associateBy { it.id }
        return top.mapNotNull { byId[it.songId]?.toSong() }
    }

    suspend fun topArtists(days: Int, limit: Int): List<ArtistRef> =
        topSongs(days, 100).flatMap { it.artists.take(1) }
            .groupingBy { it.id ?: it.name }.eachCount()
            .entries.sortedByDescending { it.value }
            .take(limit)
            .mapNotNull { entry -> topSongs(days, 100).flatMap { it.artists }.firstOrNull { (it.id ?: it.name) == entry.key } }

    suspend fun searchLocal(query: String): List<Song> = songs.searchLocal(query, 20).map { it.toSong() }

    /** Todo lo que tienes guardado (para buscar dentro de la biblioteca). */
    val librarySongs: Flow<List<Song>> = songs.inLibrary().map { list -> list.map { it.toSong() } }.distinctUntilChanged()

    // ------------------------------------------------------------ búsquedas

    val recentSearches: Flow<List<String>> = searches.recent(15).map { list -> list.map { it.query } }

    suspend fun addSearch(query: String) {
        if (query.isNotBlank() && !query.startsWith("http")) searches.save(SearchHistoryEntity(query.trim()))
    }

    suspend fun removeSearch(query: String) = searches.delete(query)
    suspend fun clearSearches() = searches.clear()

    // ------------------------------------------------------------ recientes (accesos rápidos)

    private val json = Json { ignoreUnknownKeys = true }
    private val recentFile = File(context.filesDir, "recent_contexts.json")
    private val recentSerializer = ListSerializer(MusicItem.serializer())
    private val _recentContexts = MutableStateFlow<List<MusicItem>>(emptyList())

    /** Lo último que has abierto para reproducir (álbumes, playlists, mixes, artistas). */
    val recentContexts: StateFlow<List<MusicItem>> = _recentContexts.asStateFlow()

    init {
        scope.launch(Dispatchers.IO) {
            val saved = runCatching { json.decodeFromString(recentSerializer, recentFile.readText()) }.getOrNull()
            if (saved != null) _recentContexts.value = saved
        }
    }

    fun noteContext(item: MusicItem) {
        if (item is Song) return
        _recentContexts.update { current -> (listOf(item) + current.filterNot { it.id == item.id }).take(12) }
        val snapshot = _recentContexts.value
        scope.launch(Dispatchers.IO) {
            runCatching { recentFile.writeText(json.encodeToString(recentSerializer, snapshot)) }
        }
    }
}
