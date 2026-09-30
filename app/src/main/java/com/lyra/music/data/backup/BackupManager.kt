package com.lyra.music.data.backup

import android.content.Context
import android.net.Uri
import com.lyra.music.BuildConfig
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.db.FollowedArtistEntity
import com.lyra.music.data.db.LyraDatabase
import com.lyra.music.data.db.PlayEventEntity
import com.lyra.music.data.db.PlaylistEntity
import com.lyra.music.data.db.PlaylistFolderEntity
import com.lyra.music.data.repo.COVERS_DIR
import com.lyra.music.data.db.SavedAlbumEntity
import com.lyra.music.data.db.SearchHistoryEntity
import com.lyra.music.data.db.SongEntity
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.settings.SettingsRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.io.IOException
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

@Serializable
data class BackupSong(
    val id: String,
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
)

@Serializable
data class BackupPlaylist(
    val name: String,
    val description: String? = null,
    val createdAt: Long,
    val remoteId: String? = null,
    val coverUrl: String? = null,
    val songIds: List<String>,
    /** Nombre de su carpeta en la biblioteca. */
    val folder: String? = null,
    /** Portada propia dentro del zip (playlist-covers/…). */
    val cover: String? = null,
    val syncEnabled: Boolean = false,
    val autoDownload: Boolean = false,
)

@Serializable
data class BackupAlbum(val id: String, val title: String, val artists: List<ArtistRef>, val year: String?, val thumbnailUrl: String?, val kind: String?, val savedAt: Long)

@Serializable
data class BackupArtist(val id: String, val name: String, val thumbnailUrl: String?, val followedAt: Long)

@Serializable
data class BackupEvent(val songId: String, val playedAt: Long, val playedMs: Long)

@Serializable
data class BackupDownload(val songId: String, val file: String, val cover: String? = null)

@Serializable
data class BackupData(
    val format: Int = 1,
    val exportedAt: Long,
    val appVersion: String,
    val songs: List<BackupSong>,
    val playlists: List<BackupPlaylist>,
    val albums: List<BackupAlbum>,
    val artists: List<BackupArtist>,
    val events: List<BackupEvent>,
    val searches: List<String>,
    val settings: Map<String, String>,
    val downloads: List<BackupDownload> = emptyList(),
)

data class BackupSummary(val songs: Int, val playlists: Int, val downloads: Int)

/** Exporta e importa todo a un .zip (biblioteca, playlists, historial, ajustes y, si quieres, las descargas). */
class BackupManager(
    private val context: Context,
    private val db: LyraDatabase,
    private val settings: SettingsRepository,
    private val downloads: DownloadRepository,
) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    suspend fun export(target: Uri, includeDownloads: Boolean): BackupSummary = withContext(Dispatchers.IO) {
        val songs = db.songs().all()
        val playlists = db.playlists().all()
        val entries = db.playlists().allEntries().groupBy { it.playlistId }
        val folderNames = db.folders().all().associate { it.id to it.name }
        val playlistCovers = playlists.mapNotNull { p ->
            val file = p.customCover?.let { Uri.parse(it).path }?.let(::File)?.takeIf { it.exists() } ?: return@mapNotNull null
            p.id to ("playlist-covers/${file.name}" to file)
        }.toMap()
        val completed = if (includeDownloads) db.downloads().completed() else emptyList()
        val downloadEntries = completed.mapNotNull { d ->
            val location = d.filePath ?: return@mapNotNull null
            val name = downloads.folder.displayName(location) ?: return@mapNotNull null
            val extension = name.substringAfterLast('.', "audio")
            BackupDownload(
                d.songId,
                "downloads/${DownloadRepository.fileNameFor(d.songId)}.$extension",
                d.coverPath?.let(::File)?.takeIf { it.exists() }?.let { "covers/${it.name}" },
            )
        }
        val data = BackupData(
            exportedAt = System.currentTimeMillis(),
            appVersion = BuildConfig.VERSION_NAME,
            songs = songs.map {
                BackupSong(it.id, it.title, it.artists, it.albumTitle, it.albumId, it.durationMs, it.thumbnailUrl,
                    it.isVideo, it.explicit, it.likedAt, it.playCount, it.totalPlayMs, it.lastPlayedAt)
            },
            playlists = playlists.map { p ->
                BackupPlaylist(
                    p.name, p.description, p.createdAt, p.remoteId, p.coverUrl,
                    entries[p.id].orEmpty().sortedBy { it.position }.map { it.songId },
                    folder = p.folderId?.let(folderNames::get),
                    cover = playlistCovers[p.id]?.first,
                    syncEnabled = p.syncEnabled,
                    autoDownload = p.autoDownload,
                )
            },
            albums = db.library().albumList().map { BackupAlbum(it.id, it.title, it.artists, it.year, it.thumbnailUrl, it.kind, it.savedAt) },
            artists = db.library().artistList().map { BackupArtist(it.id, it.name, it.thumbnailUrl, it.followedAt) },
            events = db.history().latest(20_000).map { BackupEvent(it.songId, it.playedAt, it.playedMs) },
            searches = db.searches().all().map { it.query },
            settings = settings.export(),
            downloads = downloadEntries,
        )
        val stream = context.contentResolver.openOutputStream(target) ?: throw IOException("No se pudo crear el archivo")
        ZipOutputStream(stream.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("backup.json"))
            zip.write(json.encodeToString(BackupData.serializer(), data).toByteArray())
            zip.closeEntry()
            playlistCovers.values.forEach { (name, file) -> addFile(zip, name, file) }
            downloadEntries.forEach { entry ->
                val original = completed.first { it.songId == entry.songId }
                // Puede estar en Música/Lyra (content://) o en la carpeta oculta.
                downloads.openLocation(original.filePath!!)?.use { input ->
                    zip.putNextEntry(ZipEntry(entry.file))
                    input.copyTo(zip)
                    zip.closeEntry()
                }
                entry.cover?.let { addFile(zip, it, File(original.coverPath!!)) }
            }
        }
        BackupSummary(data.songs.size, data.playlists.size, downloadEntries.size)
    }

    private fun addFile(zip: ZipOutputStream, name: String, file: File) {
        zip.putNextEntry(ZipEntry(name))
        file.inputStream().use { it.copyTo(zip) }
        zip.closeEntry()
    }

    suspend fun import(source: Uri): BackupSummary = withContext(Dispatchers.IO) {
        var data: BackupData? = null
        val extracted = mutableMapOf<String, File>()
        val stream = context.contentResolver.openInputStream(source) ?: throw IOException("No se pudo abrir el archivo")
        ZipInputStream(stream.buffered()).use { zip ->
            while (true) {
                val entry = zip.nextEntry ?: break
                when {
                    entry.name == "backup.json" -> data = json.decodeFromString(BackupData.serializer(), zip.readBytes().decodeToString())
                    entry.name.startsWith("playlist-covers/") -> {
                        val dir = File(context.filesDir, COVERS_DIR).apply { mkdirs() }
                        val out = File(dir, File(entry.name).name)
                        if (out.canonicalPath.startsWith(dir.canonicalPath)) {
                            out.outputStream().use { zip.copyTo(it) }
                            extracted[entry.name] = out
                        }
                    }
                    entry.name.startsWith("downloads/") || entry.name.startsWith("covers/") -> {
                        val name = File(entry.name).name
                        val dir = if (entry.name.startsWith("downloads/")) downloads.directory else downloads.coversDirectory
                        val out = File(dir, name)
                        // Evita rutas raras dentro del zip.
                        if (out.canonicalPath.startsWith(dir.canonicalPath)) {
                            out.outputStream().use { zip.copyTo(it) }
                            extracted[entry.name] = out
                        }
                    }
                }
                zip.closeEntry()
            }
        }
        val backup = data ?: throw IOException("El archivo no es una copia de Lyra")

        // Canciones: se conserva la fecha de "Me gusta" más antigua y las estadísticas mayores.
        val existing = db.songs().all().associateBy { it.id }
        db.songs().upsertAll(backup.songs.map { s ->
            val old = existing[s.id]
            SongEntity(
                id = s.id, title = s.title, artists = s.artists, albumTitle = s.albumTitle, albumId = s.albumId,
                durationMs = s.durationMs, thumbnailUrl = s.thumbnailUrl, isVideo = s.isVideo, explicit = s.explicit,
                likedAt = listOfNotNull(old?.likedAt, s.likedAt).minOrNull(),
                playCount = maxOf(old?.playCount ?: 0, s.playCount),
                totalPlayMs = maxOf(old?.totalPlayMs ?: 0, s.totalPlayMs),
                lastPlayedAt = listOfNotNull(old?.lastPlayedAt, s.lastPlayedAt).maxOrNull(),
            )
        })

        val currentPlaylists = db.playlists().all()
        val folders = db.folders().all().associate { it.name to it.id }.toMutableMap()
        backup.playlists.forEach { p ->
            val match = currentPlaylists.firstOrNull { it.name == p.name && (it.createdAt == p.createdAt || it.remoteId == p.remoteId && p.remoteId != null) }
            val id = match?.id ?: db.playlists().insert(
                PlaylistEntity(
                    name = p.name, description = p.description, createdAt = p.createdAt, remoteId = p.remoteId, coverUrl = p.coverUrl,
                    syncEnabled = p.syncEnabled, autoDownload = p.autoDownload,
                ),
            )
            db.playlists().addSongs(id, p.songIds)
            // Carpeta (se crea si no existe) y portada propia, si las tenía.
            p.folder?.let { name ->
                val folderId = folders.getOrPut(name) { db.folders().insert(PlaylistFolderEntity(name = name)) }
                if (match?.folderId == null) db.playlists().setFolder(id, folderId)
            }
            p.cover?.let(extracted::get)?.let { file ->
                if (match?.customCover == null) db.playlists().setCustomCover(id, Uri.fromFile(file).toString())
            }
        }

        db.library().saveAlbums(backup.albums.map { SavedAlbumEntity(it.id, it.title, it.artists, it.year, it.thumbnailUrl, it.kind, it.savedAt) })
        db.library().followAll(backup.artists.map { FollowedArtistEntity(it.id, it.name, it.thumbnailUrl, it.followedAt) })

        val known = db.history().latest(50_000).map { it.songId to it.playedAt }.toSet()
        db.history().insertAll(backup.events.filter { (it.songId to it.playedAt) !in known }
            .map { PlayEventEntity(songId = it.songId, playedAt = it.playedAt, playedMs = it.playedMs) })
        db.searches().saveAll(backup.searches.mapIndexed { index, query -> SearchHistoryEntity(query, backup.exportedAt - index) })
        settings.import(backup.settings)

        var restored = 0
        backup.downloads.forEach { d ->
            val file = extracted[d.file] ?: return@forEach
            val current = db.downloads().get(d.songId)
            if (current?.state != DownloadState.COMPLETED) {
                downloads.registerRestored(d.songId, file, d.cover?.let { extracted[it] })
                restored++
            }
        }
        BackupSummary(backup.songs.size, backup.playlists.size, restored)
    }
}
