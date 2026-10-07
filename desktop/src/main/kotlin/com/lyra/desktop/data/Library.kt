package com.lyra.desktop.data

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.cleaned
import com.lyra.music.sync.AlbumData
import com.lyra.music.sync.ArtistData
import com.lyra.music.sync.FolderData
import com.lyra.music.sync.LikeData
import com.lyra.music.sync.PlaylistData
import com.lyra.music.sync.SyncJson
import com.lyra.music.sync.SyncRecord
import com.lyra.music.sync.SyncTypes
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.text.Collator
import java.time.LocalDate
import java.util.Locale
import java.util.UUID

@Serializable
data class LibPlaylist(
    val id: String,
    val name: String,
    val description: String? = null,
    val songIds: List<String> = emptyList(),
    val createdAt: Long,
    val updatedAt: Long,
    /** Si se guardó de YouTube Music, SoundCloud o Spotify, su id original. */
    val remoteId: String? = null,
    val coverUrl: String? = null,
    /** Portada elegida en el PC (archivo en %APPDATA%\Lyra\portadas). */
    val customCover: String? = null,
    val folderId: String? = null,
)

@Serializable
data class LibFolder(val id: String, val name: String, val createdAt: Long, val updatedAt: Long = createdAt)

@Serializable
data class LibAlbum(val album: AlbumItem, val savedAt: Long)

@Serializable
data class LibArtist(val artist: ArtistItem, val followedAt: Long)

@Serializable
data class PlayEvent(val songId: String, val at: Long, val ms: Long)

@Serializable
data class SongStats(val plays: Int = 0, val lastPlayed: Long = 0, val totalMs: Long = 0)

@Serializable
data class RecentContext(val item: MusicItem, val at: Long)

@Serializable
data class LibraryData(
    /** Datos de cada canción que aparece en algún sitio de la biblioteca. */
    val songs: Map<String, Song> = emptyMap(),
    /** Me gusta: id → cuándo. */
    val liked: Map<String, Long> = emptyMap(),
    val playlists: List<LibPlaylist> = emptyList(),
    val folders: List<LibFolder> = emptyList(),
    val albums: List<LibAlbum> = emptyList(),
    val artists: List<LibArtist> = emptyList(),
    val history: List<PlayEvent> = emptyList(),
    val stats: Map<String, SongStats> = emptyMap(),
    val searches: List<String> = emptyList(),
    /** Lo último que has puesto (playlists, álbumes, artistas…), para los accesos del Inicio. */
    val recent: List<RecentContext> = emptyList(),
)

/** Una fila de la biblioteca (barra de la izquierda). */
sealed interface LibraryEntry {
    val key: String
    val name: String

    data class Folder(val folder: LibFolder, val playlists: List<LibPlaylist>) : LibraryEntry {
        override val key get() = "f:" + folder.id
        override val name get() = folder.name
    }

    data class Playlist(val playlist: LibPlaylist) : LibraryEntry {
        override val key get() = "p:" + playlist.id
        override val name get() = playlist.name
    }

    data class Album(val album: LibAlbum) : LibraryEntry {
        override val key get() = "a:" + album.album.id
        override val name get() = album.album.title
    }

    data class Artist(val artist: LibArtist) : LibraryEntry {
        override val key get() = "r:" + artist.artist.id
        override val name get() = artist.artist.title
    }
}

/**
 * Tu biblioteca en el PC: Me gusta, playlists, carpetas, álbumes, artistas e historial.
 * Vive en memoria y se guarda sola en %APPDATA%\Lyra\biblioteca.json (con copias de los últimos
 * días por si acaso).
 */
@OptIn(FlowPreview::class)
class Library(private val file: File, private val scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        coerceInputValues = true
    }
    private val state = MutableStateFlow(load())
    val data: StateFlow<LibraryData> = state.asStateFlow()
    val current: LibraryData get() = state.value

    val likedIds: StateFlow<Set<String>> = state.map { it.liked.keys }.distinctUntilChanged()
        .stateIn(scope, SharingStarted.Eagerly, state.value.liked.keys)

    init {
        scope.launch(Dispatchers.IO) {
            state.drop(1).debounce(1_000).collect { save(it) }
        }
    }

    // ------------------------------------------------------------------ guardar

    private fun load(): LibraryData {
        val text = runCatching { file.readText() }.getOrNull() ?: return LibraryData()
        return runCatching { json.decodeFromString(LibraryData.serializer(), text) }.getOrElse {
            // Archivo roto: se aparta (no se pierde) y se empieza con la copia más reciente.
            runCatching { file.copyTo(File(file.path + ".roto-" + System.currentTimeMillis()), overwrite = true) }
            backups().firstNotNullOfOrNull { backup ->
                runCatching { json.decodeFromString(LibraryData.serializer(), backup.readText()) }.getOrNull()
            } ?: LibraryData()
        }
    }

    private fun backups(): List<File> =
        file.parentFile.listFiles { f -> f.name.startsWith("biblioteca-") && f.name.endsWith(".bak") }
            ?.sortedByDescending { it.name } ?: emptyList()

    fun saveNow() = save(state.value)

    @Synchronized
    private fun save(data: LibraryData) {
        runCatching {
            // Una copia al día (se guardan las 7 últimas).
            if (file.exists()) {
                val today = File(file.parentFile, "biblioteca-${LocalDate.now()}.bak")
                if (!today.exists()) {
                    file.copyTo(today)
                    backups().drop(7).forEach { it.delete() }
                }
            }
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(LibraryData.serializer(), data))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }

    /** Cambia la biblioteca entera (lo usan la importación y la sincronización). */
    fun replace(transform: (LibraryData) -> LibraryData) = state.update(transform)

    // ------------------------------------------------------------------ canciones

    fun LibraryData.withSongs(songs: Collection<Song>): LibraryData {
        if (songs.isEmpty()) return this
        val updated = this.songs.toMutableMap()
        for (song in songs) {
            val clean = song.cleaned()
            val old = updated[clean.id]
            // Se queda con los datos más completos (carátula, álbum, duración…).
            updated[clean.id] = if (old == null) clean else clean.copy(
                album = clean.album ?: old.album,
                durationMs = clean.durationMs ?: old.durationMs,
                thumbnailUrl = clean.thumbnailUrl ?: old.thumbnailUrl,
                artists = clean.artists.ifEmpty { old.artists },
            )
        }
        return copy(songs = updated)
    }

    fun remember(songs: Collection<Song>) = state.update { it.withSongs(songs) }

    fun song(id: String): Song? = state.value.songs[id]

    // ------------------------------------------------------------------ Me gusta

    fun isLiked(id: String) = id in state.value.liked

    fun setLiked(song: Song, liked: Boolean) = state.update { data ->
        if (liked) {
            if (song.id in data.liked) data else data.withSongs(listOf(song)).copy(liked = data.liked + (song.id to System.currentTimeMillis()))
        } else {
            data.copy(liked = data.liked - song.id)
        }
    }

    fun toggleLike(song: Song): Boolean {
        val liked = !isLiked(song.id)
        setLiked(song, liked)
        return liked
    }

    fun likedSongs(data: LibraryData = state.value): List<Song> =
        data.liked.entries.sortedByDescending { it.value }.mapNotNull { data.songs[it.key] }

    // ------------------------------------------------------------------ playlists

    fun playlist(id: String): LibPlaylist? = state.value.playlists.firstOrNull { it.id == id }

    fun playlistSongs(id: String, data: LibraryData = state.value): List<Song> {
        val playlist = data.playlists.firstOrNull { it.id == id } ?: return emptyList()
        return playlist.songIds.mapNotNull { data.songs[it] }
    }

    fun createPlaylist(name: String, songs: List<Song> = emptyList(), remoteId: String? = null, coverUrl: String? = null, description: String? = null): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        state.update { data ->
            data.withSongs(songs).copy(
                playlists = data.playlists + LibPlaylist(
                    id = id,
                    name = name.trim().ifBlank { "Mi playlist" },
                    description = description,
                    songIds = songs.map { it.id }.distinct(),
                    createdAt = now,
                    updatedAt = now,
                    remoteId = remoteId,
                    coverUrl = coverUrl,
                ),
            )
        }
        return id
    }

    private fun updatePlaylist(id: String, transform: (LibPlaylist) -> LibPlaylist) = state.update { data ->
        data.copy(playlists = data.playlists.map { if (it.id == id) transform(it).copy(updatedAt = System.currentTimeMillis()) else it })
    }

    fun renamePlaylist(id: String, name: String, description: String?) =
        updatePlaylist(id) { it.copy(name = name.trim().ifBlank { it.name }, description = description?.trim()?.ifBlank { null }) }

    fun deletePlaylist(id: String) = state.update { data -> data.copy(playlists = data.playlists.filterNot { it.id == id }) }

    /** Añade las que no estén ya. Devuelve cuántas se han añadido. */
    fun addToPlaylist(id: String, songs: List<Song>): Int {
        var added = 0
        state.update { data ->
            val playlist = data.playlists.firstOrNull { it.id == id } ?: return@update data
            val fresh = songs.map { it.id }.distinct().filterNot { it in playlist.songIds }
            added = fresh.size
            if (fresh.isEmpty()) return@update data
            data.withSongs(songs).copy(
                playlists = data.playlists.map {
                    if (it.id == id) it.copy(songIds = it.songIds + fresh, updatedAt = System.currentTimeMillis()) else it
                },
            )
        }
        return added
    }

    fun removeFromPlaylist(id: String, songIds: Set<String>) = updatePlaylist(id) { it.copy(songIds = it.songIds.filterNot { s -> s in songIds }) }

    fun movePlaylistSong(id: String, from: Int, to: Int) = updatePlaylist(id) { playlist ->
        val list = playlist.songIds.toMutableList()
        if (from !in list.indices || to !in list.indices) return@updatePlaylist playlist
        list.add(to, list.removeAt(from))
        playlist.copy(songIds = list)
    }

    fun setPlaylistCover(id: String, path: String?) = updatePlaylist(id) { it.copy(customCover = path) }

    fun movePlaylistToFolder(id: String, folderId: String?) = updatePlaylist(id) { it.copy(folderId = folderId) }

    /** Playlist propia ya guardada a partir de esta remota (para no duplicarla). */
    fun playlistFromRemote(remoteId: String): LibPlaylist? = state.value.playlists.firstOrNull { it.remoteId == remoteId }

    // ------------------------------------------------------------------ carpetas

    fun createFolder(name: String): String {
        val id = UUID.randomUUID().toString()
        val now = System.currentTimeMillis()
        state.update { it.copy(folders = it.folders + LibFolder(id, name.trim().ifBlank { "Carpeta" }, now)) }
        return id
    }

    fun renameFolder(id: String, name: String) = state.update { data ->
        data.copy(folders = data.folders.map { if (it.id == id) it.copy(name = name.trim().ifBlank { it.name }, updatedAt = System.currentTimeMillis()) else it })
    }

    /** Borra la carpeta; sus playlists se quedan sueltas. */
    fun deleteFolder(id: String) = state.update { data ->
        data.copy(
            folders = data.folders.filterNot { it.id == id },
            playlists = data.playlists.map { if (it.folderId == id) it.copy(folderId = null) else it },
        )
    }

    // ------------------------------------------------------------------ álbumes y artistas

    fun isAlbumSaved(id: String) = state.value.albums.any { it.album.id == id }

    fun setAlbumSaved(album: AlbumItem, saved: Boolean) = state.update { data ->
        val without = data.albums.filterNot { it.album.id == album.id }
        data.copy(albums = if (saved) without + LibAlbum(album, System.currentTimeMillis()) else without)
    }

    fun isFollowing(id: String) = state.value.artists.any { it.artist.id == id }

    fun setFollowing(artist: ArtistItem, follow: Boolean) = state.update { data ->
        val without = data.artists.filterNot { it.artist.id == artist.id }
        data.copy(artists = if (follow) without + LibArtist(artist.copy(subtitle = null), System.currentTimeMillis()) else without)
    }

    // ------------------------------------------------------------------ historial

    /** Apunta una escucha (a partir de 30 s o de la mitad de la canción). */
    fun recordPlay(song: Song, playedMs: Long) = state.update { data ->
        val now = System.currentTimeMillis()
        val old = data.stats[song.id] ?: SongStats()
        data.withSongs(listOf(song)).copy(
            history = (data.history + PlayEvent(song.id, now, playedMs)).takeLast(MAX_HISTORY),
            stats = data.stats + (song.id to SongStats(old.plays + 1, now, old.totalMs + playedMs)),
        )
    }

    fun recentlyPlayed(limit: Int, data: LibraryData = state.value): List<Song> =
        data.history.asReversed().asSequence().map { it.songId }.distinct().mapNotNull { data.songs[it] }.take(limit).toList()

    fun recentlyPlayedIds(hours: Int): Set<String> {
        val since = System.currentTimeMillis() - hours * 3_600_000L
        return state.value.history.asReversed().asSequence().takeWhile { it.at >= since }.map { it.songId }.toSet()
    }

    fun topSongs(days: Int, limit: Int, data: LibraryData = state.value): List<Song> {
        val since = System.currentTimeMillis() - days * 86_400_000L
        return data.history.asSequence().filter { it.at >= since }
            .groupingBy { it.songId }.eachCount()
            .entries.sortedByDescending { it.value }
            .mapNotNull { data.songs[it.key] }
            .take(limit)
    }

    fun clearHistory() = state.update { it.copy(history = emptyList(), stats = emptyMap(), recent = emptyList()) }

    /** Lo último puesto (playlist, álbum, artista…), para los accesos rápidos del Inicio. */
    fun touchContext(item: MusicItem) = state.update { data ->
        val now = System.currentTimeMillis()
        data.copy(recent = (listOf(RecentContext(item, now)) + data.recent.filterNot { it.item.id == item.id }).take(30))
    }

    // ------------------------------------------------------------------ búsquedas

    fun addSearch(query: String) = state.update { data ->
        val q = query.trim()
        if (q.isEmpty()) data else data.copy(searches = (listOf(q) + data.searches.filterNot { it.equals(q, ignoreCase = true) }).take(20))
    }

    fun removeSearch(query: String) = state.update { it.copy(searches = it.searches - query) }

    fun clearSearches() = state.update { it.copy(searches = emptyList()) }

    // ------------------------------------------------------------------ sincronización

    /** La biblioteca como registros de sincronización (ver core: com.lyra.music.sync). */
    fun syncSnapshot(): List<SyncRecord> {
        val d = state.value
        val records = mutableListOf<SyncRecord>()
        d.liked.forEach { (id, at) -> d.songs[id]?.let { records += SyncJson.like(it, at) } }
        d.playlists.forEach { p ->
            records += SyncJson.playlist(
                p.id,
                PlaylistData(p.name, p.description, p.songIds.mapNotNull { d.songs[it] }, p.createdAt, p.remoteId, p.coverUrl, p.folderId),
            )
        }
        d.folders.forEach { records += SyncJson.folder(it.id, FolderData(it.name, it.createdAt)) }
        d.albums.forEach { records += SyncJson.album(AlbumData(it.album, it.savedAt)) }
        d.artists.forEach { records += SyncJson.artist(ArtistData(it.artist, it.followedAt)) }
        return records
    }

    /** Aplica lo que llega del móvil u otro PC, todo de una vez. */
    fun applySync(changes: List<SyncRecord>) = state.update { start ->
        var data = start
        val now = System.currentTimeMillis()
        for (c in changes) {
            val d = c.d
            when (c.t) {
                SyncTypes.LIKE -> data = if (d == null) {
                    data.copy(liked = data.liked - c.k)
                } else {
                    val like = SyncJson.decode(LikeData.serializer(), d) ?: continue
                    data.withSongs(listOf(like.song)).copy(liked = data.liked + (c.k to like.at))
                }
                SyncTypes.PLAYLIST -> data = if (d == null) {
                    data.copy(playlists = data.playlists.filterNot { it.id == c.k })
                } else {
                    val p = SyncJson.decode(PlaylistData.serializer(), d) ?: continue
                    val old = data.playlists.firstOrNull { it.id == c.k }
                    val updated = LibPlaylist(
                        id = c.k,
                        name = p.name,
                        description = p.description,
                        songIds = p.songs.map { it.id }.distinct(),
                        createdAt = old?.createdAt ?: p.createdAt.takeIf { it > 0 } ?: now,
                        updatedAt = now,
                        remoteId = p.remoteId,
                        coverUrl = p.coverUrl,
                        customCover = old?.customCover,
                        folderId = p.folder,
                    )
                    data.withSongs(p.songs).copy(
                        playlists = if (old == null) data.playlists + updated else data.playlists.map { if (it.id == c.k) updated else it },
                    )
                }
                SyncTypes.FOLDER -> data = if (d == null) {
                    data.copy(
                        folders = data.folders.filterNot { it.id == c.k },
                        playlists = data.playlists.map { if (it.folderId == c.k) it.copy(folderId = null) else it },
                    )
                } else {
                    val f = SyncJson.decode(FolderData.serializer(), d) ?: continue
                    val old = data.folders.firstOrNull { it.id == c.k }
                    val updated = LibFolder(c.k, f.name, old?.createdAt ?: f.createdAt.takeIf { it > 0 } ?: now, now)
                    data.copy(folders = if (old == null) data.folders + updated else data.folders.map { if (it.id == c.k) updated else it })
                }
                SyncTypes.ALBUM -> data = if (d == null) {
                    data.copy(albums = data.albums.filterNot { it.album.id == c.k })
                } else {
                    val a = SyncJson.decode(AlbumData.serializer(), d) ?: continue
                    data.copy(albums = data.albums.filterNot { it.album.id == c.k } + LibAlbum(a.album, a.savedAt))
                }
                SyncTypes.ARTIST -> data = if (d == null) {
                    data.copy(artists = data.artists.filterNot { it.artist.id == c.k })
                } else {
                    val a = SyncJson.decode(ArtistData.serializer(), d) ?: continue
                    data.copy(artists = data.artists.filterNot { it.artist.id == c.k } + LibArtist(a.artist, a.followedAt))
                }
            }
        }
        data
    }

    // ------------------------------------------------------------------ orden de la biblioteca

    /** Carpetas y playlists sueltas, álbumes y artistas, en el orden elegido (como en el móvil). */
    fun entries(data: LibraryData, sort: LibrarySort): List<LibraryEntry> {
        val lastPlayed = HashMap<String, Long>()
        val plays = HashMap<String, Int>()
        data.stats.forEach { (id, s) ->
            lastPlayed[id] = s.lastPlayed
            plays[id] = s.plays
        }
        fun playlistKeys(p: LibPlaylist): Keys {
            var last = p.updatedAt
            var count = 0
            p.songIds.forEach { id ->
                last = maxOf(last, lastPlayed[id] ?: 0)
                count += plays[id] ?: 0
            }
            return Keys(p.name, p.createdAt, last, count)
        }
        val byFolder = data.playlists.groupBy { it.folderId }
        val folders = data.folders.map { folder ->
            val inside = byFolder[folder.id].orEmpty()
            val keys = inside.map(::playlistKeys)
            LibraryEntry.Folder(folder, inside) to Keys(folder.name, folder.createdAt, maxOf(folder.updatedAt, keys.maxOfOrNull { it.recent } ?: 0), keys.sumOf { it.plays })
        }
        val loose = byFolder[null].orEmpty().map { LibraryEntry.Playlist(it) to playlistKeys(it) }
        val albums = data.albums.map { saved ->
            val songs = data.songs.values.filter { it.album?.id == saved.album.id }
            LibraryEntry.Album(saved) to Keys(
                saved.album.title, saved.savedAt,
                maxOf(saved.savedAt, songs.maxOfOrNull { lastPlayed[it.id] ?: 0 } ?: 0),
                songs.sumOf { plays[it.id] ?: 0 },
            )
        }
        val artists = data.artists.map { followed ->
            val songs = data.songs.values.filter { s -> s.artists.any { it.id == followed.artist.id || it.name.equals(followed.artist.title, true) } }
            LibraryEntry.Artist(followed) to Keys(
                followed.artist.title, followed.followedAt,
                maxOf(followed.followedAt, songs.maxOfOrNull { lastPlayed[it.id] ?: 0 } ?: 0),
                songs.sumOf { plays[it.id] ?: 0 },
            )
        }
        val collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }
        val order: Comparator<Keys> = when (sort) {
            LibrarySort.RECENT -> compareByDescending<Keys> { it.recent }.thenByDescending { it.added }
            LibrarySort.PLAYS -> compareByDescending<Keys> { it.plays }.thenByDescending { it.recent }
            LibrarySort.ADDED -> compareByDescending { it.added }
            LibrarySort.NAME -> Comparator { a, b -> collator.compare(a.name.trim(), b.name.trim()) }
        }
        // Primero las carpetas, como en el móvil; luego todo lo demás mezclado según el orden.
        return folders.sortedWith { a, b -> order.compare(a.second, b.second) }.map { it.first } +
            (loose + albums + artists).sortedWith { a, b -> order.compare(a.second, b.second) }.map { it.first }
    }

    private data class Keys(val name: String, val added: Long, val recent: Long, val plays: Int)

    companion object {
        const val MAX_HISTORY = 20_000

        /** Ids especiales de la biblioteca. */
        const val LIKED_ID = "lyra:liked"
        const val DOWNLOADS_ID = "lyra:downloads"
        const val LOCAL_PREFIX = "local:"

        fun localPlaylistItem(playlist: LibPlaylist): PlaylistItem =
            PlaylistItem(LOCAL_PREFIX + playlist.id, playlist.name, null, playlist.customCover ?: playlist.coverUrl, null)
    }
}
