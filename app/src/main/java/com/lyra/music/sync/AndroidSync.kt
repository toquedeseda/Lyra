package com.lyra.music.sync

import android.content.Context
import android.net.Uri
import com.lyra.music.core.ErrorLog
import com.lyra.music.data.backup.BackupManager
import com.lyra.music.data.db.FollowedArtistEntity
import com.lyra.music.data.db.LyraDatabase
import com.lyra.music.data.db.PlaylistEntity
import com.lyra.music.data.db.PlaylistFolderEntity
import com.lyra.music.data.db.SavedAlbumEntity
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.repo.LibraryRepository
import com.lyra.music.data.settings.SettingsRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

sealed interface SyncStatus {
    data class Off(val message: String? = null) : SyncStatus
    data object Syncing : SyncStatus
    data class Done(val at: Long) : SyncStatus
    data class Failed(val message: String, val lastAt: Long) : SyncStatus
}

/**
 * La biblioteca del móvil sincronizada con el PC (por el servidor de la web de Lyra): al abrir la
 * app, al cambiar algo (a los pocos segundos) y de vez en cuando por detrás. Antes de la primera vez
 * se guarda una copia de seguridad de la biblioteca, por si acaso.
 *
 * La llave se guarda aparte (no en los ajustes) para que no acabe dentro de las copias de seguridad.
 */
@OptIn(FlowPreview::class)
class AndroidSyncManager(
    private val context: Context,
    http: OkHttpClient,
    private val db: LyraDatabase,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val settings: SettingsRepository,
    private val backup: () -> BackupManager,
    private val scope: CoroutineScope,
) {
    @Serializable
    private data class Saved(val biblioteca: String, val token: String, val lastSyncAt: Long = 0)

    @Serializable
    private data class Ids(val playlists: Map<String, Long> = emptyMap(), val folders: Map<String, Long> = emptyMap())

    private val dir = File(context.filesDir, "sync").apply { mkdirs() }
    private val keyFile = File(dir, "llave.json")
    private val stateFile = File(dir, "estado.json")
    private val idsFile = File(dir, "ids.json")
    private val json = Json { ignoreUnknownKeys = true }
    private val api = HttpSyncApi(http)
    private val mutex = Mutex()

    private val _status = MutableStateFlow<SyncStatus>(loadKey()?.let { SyncStatus.Done(it.lastSyncAt) } ?: SyncStatus.Off())
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    val paired: Boolean get() = keyFile.exists()

    @Volatile private var quietUntil = 0L

    fun start() {
        scope.launch {
            delay(4_000)
            syncNow()
        }
        // Cuando cambia la biblioteca, se sincroniza a los pocos segundos.
        scope.launch {
            combine(library.likedIds, library.playlistSummaries, library.folders, library.savedAlbums, library.followedArtists) { a, b, c, d, e ->
                listOf(a.size, b.hashCode(), c.hashCode(), d.hashCode(), e.hashCode())
            }.drop(1).debounce(5_000).collect {
                if (paired && System.currentTimeMillis() > quietUntil) syncNow()
            }
        }
    }

    // ------------------------------------------------------------------ guardar

    private fun loadKey(): Saved? = runCatching { json.decodeFromString(Saved.serializer(), keyFile.readText()) }.getOrNull()

    private fun saveKey(saved: Saved?) {
        if (saved == null) keyFile.delete() else runCatching { keyFile.writeText(json.encodeToString(Saved.serializer(), saved)) }
    }

    private fun loadState() = runCatching { json.decodeFromString(SyncState.serializer(), stateFile.readText()) }.getOrDefault(SyncState())

    private fun saveState(state: SyncState) {
        runCatching { stateFile.writeText(json.encodeToString(SyncState.serializer(), state)) }
    }

    private var ids: Ids = runCatching { json.decodeFromString(Ids.serializer(), idsFile.readText()) }.getOrDefault(Ids())

    private fun saveIds() {
        runCatching { idsFile.writeText(json.encodeToString(Ids.serializer(), ids)) }
    }

    // ------------------------------------------------------------------ acciones

    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        val saved = loadKey() ?: return@withContext false
        mutex.withLock {
            _status.value = SyncStatus.Syncing
            try {
                val result = SyncEngine(api, store).sync(SyncKey(saved.biblioteca, saved.token), loadState())
                saveState(result.state)
                val now = System.currentTimeMillis()
                saveKey(saved.copy(lastSyncAt = now))
                _status.value = SyncStatus.Done(now)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncUnpairedException) {
                forget("Este móvil se quitó de la sincronización desde otro dispositivo.")
                false
            } catch (e: Exception) {
                _status.value = SyncStatus.Failed(e.message ?: "Sin conexión", saved.lastSyncAt)
                false
            }
        }
    }

    /** Activa la sincronización desde el móvil: crea la biblioteca, la sube y da el código para el PC. */
    suspend fun createLibrary(): SyncCode = withContext(Dispatchers.IO) {
        backupBeforeFirstSync()
        val key = api.create()
        remember(key)
        syncNow()
        api.pairCode(key)
    }

    /** Se une a una biblioteca que ya existe (la del PC u otro móvil). */
    suspend fun join(code: String) = withContext(Dispatchers.IO) {
        backupBeforeFirstSync()
        val key = api.join(code.trim())
        remember(key)
        if (!syncNow()) throw IllegalStateException((status.value as? SyncStatus.Failed)?.message ?: "No se pudo sincronizar")
    }

    suspend fun newCode(): SyncCode = withContext(Dispatchers.IO) {
        val saved = loadKey() ?: throw IllegalStateException("No está emparejado")
        api.pairCode(SyncKey(saved.biblioteca, saved.token))
    }

    suspend fun leave() = withContext(Dispatchers.IO) {
        loadKey()?.let { saved ->
            runCatching { api.leave(SyncKey(saved.biblioteca, saved.token)) }
                .onFailure { ErrorLog.record("Sincronizar", "No se pudo avisar al servidor", it) }
        }
        forget(null)
    }

    private fun remember(key: SyncKey) {
        saveState(SyncState())
        saveKey(Saved(key.biblioteca, key.token))
    }

    private fun forget(message: String?) {
        saveKey(null)
        stateFile.delete()
        _status.value = SyncStatus.Off(message)
    }

    /** Antes de mezclar la biblioteca con la de otro dispositivo, una copia en el móvil por si acaso. */
    private suspend fun backupBeforeFirstSync() {
        val folder = File(context.filesDir, "copias").apply { mkdirs() }
        val name = "antes-de-sincronizar-" + SimpleDateFormat("yyyy-MM-dd-HHmm", Locale.US).format(Date()) + ".zip"
        runCatching { backup().export(Uri.fromFile(File(folder, name)), includeDownloads = false) }
            .onFailure { ErrorLog.record("Sincronizar", "No se pudo hacer la copia previa", it) }
        folder.listFiles()?.sortedByDescending { it.lastModified() }?.drop(3)?.forEach { it.delete() }
    }

    // ------------------------------------------------------------------ la biblioteca como registros

    private fun playlistUid(localId: Long): String {
        ids.playlists.entries.firstOrNull { it.value == localId }?.let { return it.key }
        val uid = UUID.randomUUID().toString()
        ids = ids.copy(playlists = ids.playlists + (uid to localId))
        return uid
    }

    private fun folderUid(localId: Long): String {
        ids.folders.entries.firstOrNull { it.value == localId }?.let { return it.key }
        val uid = UUID.randomUUID().toString()
        ids = ids.copy(folders = ids.folders + (uid to localId))
        return uid
    }

    private val store = object : SyncStore {
        override suspend fun snapshot(): List<SyncRecord> {
            val records = mutableListOf<SyncRecord>()
            db.songs().likedList().forEach { records += SyncJson.like(it.toSong(), it.likedAt ?: 0) }
            val folders = db.folders().all()
            val playlists = db.playlists().all()
            // Lo borrado aquí ya no tiene número local: se olvida su equivalencia.
            ids = Ids(
                playlists = ids.playlists.filterValues { id -> playlists.any { it.id == id } },
                folders = ids.folders.filterValues { id -> folders.any { it.id == id } },
            )
            folders.forEach { records += SyncJson.folder(folderUid(it.id), FolderData(it.name, it.createdAt)) }
            playlists.forEach { p ->
                val songs = db.playlists().songList(p.id).map { it.toSong() }
                records += SyncJson.playlist(
                    playlistUid(p.id),
                    PlaylistData(p.name, p.description, songs, p.createdAt, p.remoteId, p.coverUrl, p.folderId?.let(::folderUid)),
                )
            }
            db.library().albumList().forEach { records += SyncJson.album(AlbumData(it.toItem(), it.savedAt)) }
            db.library().artistList().forEach { records += SyncJson.artist(ArtistData(it.toItem(), it.followedAt)) }
            saveIds()
            return records
        }

        override suspend fun apply(changes: List<SyncRecord>) {
            quietUntil = System.currentTimeMillis() + 8_000
            // Primero las carpetas (las playlists pueden ir dentro).
            val order = listOf(SyncTypes.FOLDER, SyncTypes.PLAYLIST, SyncTypes.LIKE, SyncTypes.ALBUM, SyncTypes.ARTIST)
            for (change in changes.sortedBy { order.indexOf(it.t) }) {
                runCatching { applyOne(change) }.onFailure {
                    ErrorLog.record("Sincronizar", "No se pudo aplicar un cambio (${change.t})", it, extra = change.k)
                }
            }
            saveIds()
        }

        private suspend fun applyOne(c: SyncRecord) {
            val d = c.d
            when (c.t) {
                SyncTypes.LIKE -> {
                    if (d == null) {
                        db.songs().setLikedAt(c.k, null)
                    } else {
                        val like = SyncJson.decode(LikeData.serializer(), d) ?: return
                        db.songs().save(like.song)
                        db.songs().setLikedAt(like.song.id, like.at.takeIf { it > 0 } ?: System.currentTimeMillis())
                        if (settings.current.autoDownloadLiked) downloads.enqueue(listOf(like.song))
                    }
                }
                SyncTypes.FOLDER -> {
                    val local = ids.folders[c.k]
                    if (d == null) {
                        if (local != null) db.folders().delete(local)
                        ids = ids.copy(folders = ids.folders - c.k)
                    } else {
                        val folder = SyncJson.decode(FolderData.serializer(), d) ?: return
                        if (local != null && db.folders().all().any { it.id == local }) {
                            db.folders().rename(local, folder.name)
                        } else {
                            val id = db.folders().insert(PlaylistFolderEntity(name = folder.name, createdAt = folder.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
                            ids = ids.copy(folders = ids.folders + (c.k to id))
                        }
                    }
                }
                SyncTypes.PLAYLIST -> {
                    val local = ids.playlists[c.k]?.takeIf { db.playlists().get(it) != null }
                    if (d == null) {
                        if (local != null) library.deletePlaylist(local)
                        ids = ids.copy(playlists = ids.playlists - c.k)
                        return
                    }
                    val p = SyncJson.decode(PlaylistData.serializer(), d) ?: return
                    val id = local ?: db.playlists().insert(
                        PlaylistEntity(name = p.name, description = p.description, createdAt = p.createdAt.takeIf { it > 0 } ?: System.currentTimeMillis()),
                    ).also { ids = ids.copy(playlists = ids.playlists + (c.k to it)) }
                    db.playlists().rename(id, p.name, p.description)
                    db.playlists().setRemote(id, p.remoteId, p.coverUrl)
                    db.songs().saveAll(p.songs)
                    db.playlists().clear(id)
                    db.playlists().addSongs(id, p.songs.map { it.id }.distinct())
                    db.playlists().setFolder(id, p.folder?.let { ids.folders[it] })
                }
                SyncTypes.ALBUM -> {
                    if (d == null) {
                        db.library().removeAlbum(c.k)
                    } else {
                        val a = SyncJson.decode(AlbumData.serializer(), d) ?: return
                        db.library().saveAlbum(SavedAlbumEntity.from(a.album).copy(savedAt = a.savedAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
                    }
                }
                SyncTypes.ARTIST -> {
                    if (d == null) {
                        db.library().unfollow(c.k)
                    } else {
                        val a = SyncJson.decode(ArtistData.serializer(), d) ?: return
                        db.library().follow(FollowedArtistEntity(a.artist.id, a.artist.title, a.artist.thumbnailUrl, a.followedAt.takeIf { it > 0 } ?: System.currentTimeMillis()))
                    }
                }
            }
        }
    }
}
