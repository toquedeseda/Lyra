package com.lyra.music.data.download

import kotlinx.coroutines.flow.first

import com.lyra.music.data.db.StaleDownload

import android.content.Context
import android.net.Uri
import androidx.work.Constraints
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.OutOfQuotaPolicy
import androidx.work.WorkManager
import com.lyra.music.data.db.DownloadEntity
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.db.LyraDatabase
import com.lyra.music.data.db.SongWithDownload
import com.lyra.music.data.model.Song
import com.lyra.music.data.settings.SettingsRepository
import com.lyra.music.data.source.soundcloud.AudioStreamInfo
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.MapSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Descargas. Por defecto van a la carpeta visible `Música/Lyra` (M4A/MP3 con
 * nombre, carátula y datos dentro); si se desactiva en Ajustes, van a la carpeta
 * privada de la app, ocultas como en Spotify.
 */
class DownloadRepository(
    private val context: Context,
    private val db: LyraDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val directory = File(context.filesDir, "downloads").apply { mkdirs() }
    val coversDirectory = File(context.filesDir, "covers").apply { mkdirs() }
    val folder = PublicMusicFolder(context)

    private val dao = db.downloads()

    val states: StateFlow<Map<String, DownloadEntity>> = dao.states()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val downloads: Flow<List<SongWithDownload>> = dao.all()
    val totalBytes: Flow<Long> = dao.totalBytes()
    val pendingCount: Flow<Int> = dao.pendingCountFlow()

    /**
     * Índice síncrono de archivos (ruta o `content://`), para el reproductor. Se
     * sustituye entero de una vez: antes se vaciaba y se volvía a llenar, y si el
     * reproductor preguntaba justo entonces creía que la canción no estaba descargada.
     */
    private class Index(val locations: Map<String, String>, val covers: Map<String, String>)

    @Volatile private var index = Index(emptyMap(), emptyMap())
    @Volatile private var indexLoaded = false

    /** Cambios hechos al momento (borrados, archivos que faltan) hasta que llega el índice nuevo. */
    private val locations = ConcurrentHashMap<String, String>()
    private val removed = ConcurrentHashMap.newKeySet<String>()

    init {
        scope.launch {
            states.collect { map ->
                // También las que se están volviendo a descargar: siguen sonando hasta que llega la nueva.
                index = Index(
                    map.values.mapNotNull { e -> e.filePath?.let { e.songId to it } }.toMap(),
                    map.values.mapNotNull { e -> e.coverPath?.let { e.songId to it } }.toMap(),
                )
                locations.clear()
                removed.clear()
                indexLoaded = true
            }
        }
    }

    private fun ensureIndex() {
        if (indexLoaded) return
        val completed = runBlocking(Dispatchers.IO) { dao.completed() }
        if (!indexLoaded) {
            index = Index(
                completed.mapNotNull { e -> e.filePath?.let { e.songId to it } }.toMap(),
                completed.mapNotNull { e -> e.coverPath?.let { e.songId to it } }.toMap(),
            )
            indexLoaded = true
        }
    }

    private fun locationOf(songId: String): String? {
        ensureIndex()
        if (songId in removed) return null
        return locations[songId] ?: index.locations[songId]
    }

    /** Dónde está la canción descargada, o null si no lo está. */
    fun localUri(songId: String): Uri? {
        val location = locationOf(songId) ?: return null
        if (location.startsWith("content://")) return Uri.parse(location)
        return File(location).takeIf { it.exists() && it.length() > 0 }?.let(Uri::fromFile)
    }

    fun localCover(songId: String): File? {
        ensureIndex()
        if (songId in removed) return null
        return index.covers[songId]?.let(::File)?.takeIf { it.exists() }
    }

    fun isDownloaded(songId: String): Boolean = localUri(songId) != null

    /** El archivo ya no existe (p. ej. se borró desde el gestor de archivos). */
    fun markMissing(songId: String) {
        removed += songId
        locations.remove(songId)
        scope.launch(Dispatchers.IO) { dao.delete(songId) }
    }

    fun openLocation(location: String) = folder.open(location)

    fun deleteLocation(location: String) {
        if (location.startsWith("content://") || !location.startsWith(directory.absolutePath)) folder.delete(location)
        else File(location).delete()
    }

    /** [collectionTitle]: si se descarga una playlist o álbum entero, también se crea su .m3u8. */
    suspend fun enqueue(songs: List<Song>, collectionTitle: String? = null) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        db.songs().saveAll(songs)
        dao.insertIgnore(songs.map { DownloadEntity(songId = it.id) })
        songs.forEach { song ->
            val existing = dao.get(song.id)
            if (existing != null && (existing.state == DownloadState.FAILED || existing.state == DownloadState.PAUSED)) {
                dao.setState(song.id, DownloadState.QUEUED)
            }
        }
        if (collectionTitle != null) rememberExport(collectionTitle, songs.map { it.id })
        start()
    }

    suspend fun remove(songId: String) = withContext(Dispatchers.IO) {
        dao.get(songId)?.let { entity ->
            entity.filePath?.let(::deleteLocation)
            entity.coverPath?.let { File(it).delete() }
        }
        dao.delete(songId)
        removed += songId
        locations.remove(songId)
    }

    suspend fun removeAll(songIds: List<String>) = songIds.forEach { remove(it) }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        dao.completed().forEach { entity -> entity.filePath?.let(::deleteLocation) }
        dao.clear()
        directory.listFiles()?.forEach { it.delete() }
        coversDirectory.listFiles()?.forEach { it.delete() }
        index = Index(emptyMap(), emptyMap())
        locations.clear()
    }

    suspend fun retryFailed() {
        dao.retryFailed()
        start()
    }

    fun start() {
        val network = if (settings.current.downloadWifiOnly) NetworkType.UNMETERED else NetworkType.CONNECTED
        val request = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(network).build())
            .setExpedited(OutOfQuotaPolicy.RUN_AS_NON_EXPEDITED_WORK_REQUEST)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(WORK_NAME, ExistingWorkPolicy.APPEND_OR_REPLACE, request)
    }

    /**
     * Guarda el audio ya descargado en su sitio definitivo y devuelve la ubicación.
     * En la carpeta visible se escriben antes título, artista, álbum y carátula.
     */
    fun store(song: Song, audio: File, stream: AudioStreamInfo, cover: ByteArray?, visible: Boolean): String {
        val extension = stream.extension.lowercase()
        if (visible && folder.available && (extension == "m4a" || extension == "mp3")) {
            val tags = AudioTags(
                title = song.title,
                artist = song.artistsText,
                album = song.album?.title,
                albumArtist = song.artists.firstOrNull()?.name,
                cover = cover,
            )
            val tagged = File(audio.path + ".tag")
            val ok = if (extension == "m4a") Mp4Tagger.tag(audio, tagged, tags) else Id3Tagger.tag(audio, tagged, tags)
            val source = if (ok) tagged else audio
            val mime = if (extension == "m4a") "audio/mp4" else "audio/mpeg"
            return try {
                folder.save(source, song, extension, mime)
            } finally {
                tagged.delete()
                audio.delete()
            }
        }
        val target = File(directory, "${fileNameFor(song.id)}.$extension")
        target.delete()
        if (!audio.renameTo(target)) {
            audio.copyTo(target, overwrite = true)
            audio.delete()
        }
        return target.absolutePath
    }

    /** Las descargas ocultas de antes se vuelven a bajar a la carpeta visible (siguen sonando mientras). */
    suspend fun migrateHiddenToFolder(): Int = withContext(Dispatchers.IO) {
        val hidden = dao.completed().filter { entity ->
            val path = entity.filePath ?: return@filter false
            !path.startsWith("content://") && path.startsWith(directory.absolutePath)
        }
        hidden.forEach { dao.setState(it.songId, DownloadState.QUEUED) }
        if (hidden.isNotEmpty()) start()
        hidden.size
    }

    /** Descargas que no escuchas desde hace [months] meses (sin las de "Me gusta"). */
    fun stale(months: Int): Flow<List<StaleDownload>> = dao.stale(staleCutoff(months))

    suspend fun staleNow(months: Int): List<StaleDownload> = dao.stale(staleCutoff(months)).first()

    private fun staleCutoff(months: Int) = System.currentTimeMillis() - months * 30L * 86_400_000L

    /** Tras limpiar títulos: corrige etiquetas y nombre de las descargas visibles de esas canciones. */
    suspend fun relabel(songs: List<Song>) = withContext(Dispatchers.IO) {
        var changed = false
        songs.forEach { song ->
            val entity = dao.get(song.id) ?: return@forEach
            val location = entity.filePath ?: return@forEach
            // Las ocultas no tienen nombre legible ni etiquetas que corregir.
            if (entity.state != DownloadState.COMPLETED || location.startsWith(directory.absolutePath)) return@forEach
            val extension = folder.displayName(location)?.substringAfterLast('.', "")?.lowercase() ?: return@forEach
            if (extension == "m4a" || extension == "mp3") {
                runCatching {
                    val bytes = folder.open(location)?.use { it.readBytes() } ?: return@runCatching
                    val tags = AudioTags(
                        title = song.title,
                        artist = song.artistsText,
                        album = song.album?.title,
                        albumArtist = song.artists.firstOrNull()?.name,
                        cover = entity.coverPath?.let(::File)?.takeIf { it.exists() }?.readBytes(),
                    )
                    val tagged = if (extension == "m4a") Mp4Tagger.tag(bytes, tags) else Id3Tagger.tag(bytes, tags)
                    if (tagged != null) folder.rewrite(location, tagged)
                }
            }
            val renamed = folder.rename(location, song)
            if (renamed != null && renamed != location) {
                dao.upsert(entity.copy(filePath = renamed))
                locations[song.id] = renamed
            }
            changed = true
        }
        if (changed) writePlaylistExports()
    }

    /** Registra un archivo que ya existe (al restaurar una copia de seguridad). */
    suspend fun registerRestored(songId: String, file: File, cover: File?) {
        dao.upsert(
            DownloadEntity(
                songId = songId,
                state = DownloadState.COMPLETED,
                progress = 1f,
                downloadedBytes = file.length(),
                totalBytes = file.length(),
                filePath = file.absolutePath,
                coverPath = cover?.absolutePath,
                completedAt = System.currentTimeMillis(),
            ),
        )
    }

    // ------------------------------------------------------------- listas .m3u8

    private val json = Json { ignoreUnknownKeys = true }
    private val exportsFile = File(context.filesDir, "playlist_exports.json")
    private val exportsSerializer = MapSerializer(String.serializer(), ListSerializer(String.serializer()))
    private val exportsLock = Mutex()

    private suspend fun rememberExport(title: String, songIds: List<String>) = exportsLock.withLock {
        val current = readExports().toMutableMap()
        current[title] = (current[title].orEmpty() + songIds).distinct()
        runCatching { exportsFile.writeText(json.encodeToString(exportsSerializer, current)) }
    }

    private fun readExports(): Map<String, List<String>> =
        runCatching { json.decodeFromString(exportsSerializer, exportsFile.readText()) }.getOrDefault(emptyMap())

    /** Escribe en Música/Lyra/Playlists un .m3u8 por cada playlist o álbum descargado entero. */
    suspend fun writePlaylistExports() = exportsLock.withLock {
        withContext(Dispatchers.IO) {
            val completed = dao.completed().associateBy { it.songId }
            readExports().forEach { (title, ids) ->
                val songs = db.songs().getAll(ids).associateBy { it.id }
                val entries = ids.mapNotNull { id ->
                    val location = completed[id]?.filePath ?: return@mapNotNull null
                    if (location.startsWith(directory.absolutePath)) return@mapNotNull null
                    val name = folder.displayName(location) ?: return@mapNotNull null
                    val song = songs[id]?.toSong() ?: return@mapNotNull null
                    song to name
                }
                runCatching { folder.writePlaylist(title, entries) }
            }
        }
    }

    companion object {
        const val WORK_NAME = "lyra-downloads"

        fun fileNameFor(songId: String): String = songId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
