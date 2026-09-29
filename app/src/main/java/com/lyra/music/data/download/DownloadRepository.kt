package com.lyra.music.data.download

import android.content.Context
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
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Descargas "ocultas": los archivos van a la carpeta privada de la app
 * (files/downloads), como en Spotify. Se conservan al actualizar la app
 * y se borran solo al desinstalarla o desde Ajustes.
 */
class DownloadRepository(
    private val context: Context,
    private val db: LyraDatabase,
    private val settings: SettingsRepository,
    private val scope: CoroutineScope,
) {
    val directory = File(context.filesDir, "downloads").apply { mkdirs() }
    val coversDirectory = File(context.filesDir, "covers").apply { mkdirs() }

    private val dao = db.downloads()

    val states: StateFlow<Map<String, DownloadEntity>> = dao.states()
        .map { list -> list.associateBy { it.songId } }
        .stateIn(scope, SharingStarted.Eagerly, emptyMap())

    val downloads: Flow<List<SongWithDownload>> = dao.all()
    val totalBytes: Flow<Long> = dao.totalBytes()
    val pendingCount: Flow<Int> = dao.pendingCountFlow()

    /** Índice síncrono de archivos descargados, para el reproductor (que no puede esperar). */
    private val files = ConcurrentHashMap<String, String>()
    private val covers = ConcurrentHashMap<String, String>()
    @Volatile private var indexLoaded = false

    init {
        scope.launch {
            states.collect { map ->
                files.clear()
                covers.clear()
                map.values.filter { it.state == DownloadState.COMPLETED }.forEach { entity ->
                    entity.filePath?.let { files[entity.songId] = it }
                    entity.coverPath?.let { covers[entity.songId] = it }
                }
                indexLoaded = true
            }
        }
    }

    private fun ensureIndex() {
        if (indexLoaded) return
        runBlocking(Dispatchers.IO) {
            dao.completed().forEach { entity ->
                entity.filePath?.let { files[entity.songId] = it }
                entity.coverPath?.let { covers[entity.songId] = it }
            }
        }
        indexLoaded = true
    }

    fun localFile(songId: String): File? {
        ensureIndex()
        return files[songId]?.let(::File)?.takeIf { it.exists() && it.length() > 0 }
    }

    fun localCover(songId: String): File? {
        ensureIndex()
        return covers[songId]?.let(::File)?.takeIf { it.exists() }
    }

    fun isDownloaded(songId: String): Boolean = localFile(songId) != null

    suspend fun enqueue(songs: List<Song>) = withContext(Dispatchers.IO) {
        if (songs.isEmpty()) return@withContext
        db.songs().saveAll(songs)
        dao.insertIgnore(songs.map { DownloadEntity(songId = it.id) })
        // Las que fallaron antes vuelven a la cola.
        songs.forEach { song ->
            val existing = dao.get(song.id)
            if (existing != null && (existing.state == DownloadState.FAILED || existing.state == DownloadState.PAUSED)) {
                dao.setState(song.id, DownloadState.QUEUED)
            }
        }
        start()
    }

    suspend fun remove(songId: String) = withContext(Dispatchers.IO) {
        dao.get(songId)?.let { entity ->
            entity.filePath?.let { File(it).delete() }
            entity.coverPath?.let { File(it).delete() }
        }
        dao.delete(songId)
        files.remove(songId)
        covers.remove(songId)
    }

    suspend fun removeAll(songIds: List<String>) = songIds.forEach { remove(it) }

    suspend fun clearAll() = withContext(Dispatchers.IO) {
        WorkManager.getInstance(context).cancelUniqueWork(WORK_NAME)
        dao.clear()
        directory.listFiles()?.forEach { it.delete() }
        coversDirectory.listFiles()?.forEach { it.delete() }
        files.clear()
        covers.clear()
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

    companion object {
        const val WORK_NAME = "lyra-downloads"

        fun fileNameFor(songId: String): String = songId.replace(Regex("[^A-Za-z0-9._-]"), "_")
    }
}
