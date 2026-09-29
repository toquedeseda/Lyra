package com.lyra.music.data.download

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import com.lyra.music.LyraApp
import com.lyra.music.MainActivity
import com.lyra.music.R
import com.lyra.music.data.db.DownloadEntity
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.playback.HlsFetcher
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import kotlin.coroutines.coroutineContext

/**
 * Procesa la cola de descargas mientras quede algo pendiente. Baja dos canciones
 * a la vez y muestra el progreso en una notificación.
 */
class DownloadWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as LyraApp).container
    private val db = container.database
    private val dao = db.downloads()
    private val claimLock = Mutex()
    private var completedInRun = 0

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        runCatching { setForeground(foregroundInfo("Preparando descargas…", null)) }
        // Si la app murió a medias, lo que estaba "descargando" vuelve a la cola.
        dao.requeueInterrupted()
        coroutineScope {
            repeat(PARALLEL) {
                launch {
                    while (true) {
                        val next = claimNext() ?: break
                        process(next)
                    }
                }
            }
        }
        if (completedInRun > 0) runCatching { container.downloads.writePlaylistExports() }
        Result.success()
    }

    private suspend fun claimNext(): DownloadEntity? = claimLock.withLock {
        val next = dao.nextQueued() ?: return@withLock null
        dao.setState(next.songId, DownloadState.DOWNLOADING)
        next
    }

    private suspend fun process(entity: DownloadEntity) {
        val songId = entity.songId
        val song = db.songs().get(songId)?.toSong()
        if (song == null) {
            dao.delete(songId)
            return
        }
        try {
            val settings = container.settings.current
            val visible = settings.downloadsVisible && container.downloads.folder.available
            val stream = container.streamResolver.freshStream(songId, settings.downloadQuality, portable = visible)
            val base = DownloadRepository.fileNameFor(songId)
            val tempDir = File(applicationContext.cacheDir, "downloading").apply { mkdirs() }
            val target = File(tempDir, "$base.${stream.extension}")
            var lastUpdate = 0L
            val onProgress: suspend (Long, Long) -> Unit = { done, total ->
                val now = System.currentTimeMillis()
                if (now - lastUpdate > 400) {
                    lastUpdate = now
                    val fraction = if (total > 0) done.toFloat() / total else 0f
                    dao.setProgress(songId, fraction, done, total)
                    val pending = dao.pendingCount()
                    runCatching {
                        setForeground(foregroundInfo(song.title, fraction, pending))
                    }
                }
            }
            if (stream.isHls) {
                HlsFetcher(container.http).download(stream.url, target) { _: Float -> }
            } else {
                downloadHttp(container.http, stream.url, target, stream.contentLength, onProgress)
            }
            val coverFile = File(container.downloads.coversDirectory, "$base.jpg")
            val coverBytes = downloadCover(song.thumbnailUrl, coverFile)
            val size = target.length()
            val location = container.downloads.store(song, target, stream, coverBytes, visible)
            // Si había una copia anterior (p. ej. oculta, antes de la carpeta visible), se borra.
            entity.filePath?.takeIf { it != location }?.let(container.downloads::deleteLocation)
            dao.upsert(
                entity.copy(
                    state = DownloadState.COMPLETED,
                    progress = 1f,
                    downloadedBytes = size,
                    totalBytes = size,
                    filePath = location,
                    coverPath = coverFile.takeIf { coverBytes != null }?.absolutePath,
                    mimeType = stream.mimeType,
                    bitrate = stream.bitrate,
                    completedAt = System.currentTimeMillis(),
                    error = null,
                ),
            )
            completedInRun++
        } catch (e: CancellationException) {
            dao.setState(songId, DownloadState.QUEUED)
            throw e
        } catch (e: Exception) {
            dao.setState(songId, DownloadState.FAILED, e.message ?: "Error al descargar")
        }
    }

    /**
     * Baja la carátula y la deja como JPEG cuadrado (las de vídeo son 16:9): se guarda
     * para verla sin conexión y se mete dentro del archivo de audio.
     */
    private fun downloadCover(url: String?, target: File): ByteArray? {
        val hiRes = hiResArtwork(url, 720) ?: return null
        return runCatching {
            val bytes = container.http.newCall(Request.Builder().url(hiRes).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                response.body.bytes()
            }
            val bitmap = android.graphics.BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
            val side = minOf(bitmap.width, bitmap.height)
            val square = android.graphics.Bitmap.createBitmap(bitmap, (bitmap.width - side) / 2, (bitmap.height - side) / 2, side, side)
            val scaled = if (side > 600) android.graphics.Bitmap.createScaledBitmap(square, 600, 600, true) else square
            val jpeg = java.io.ByteArrayOutputStream().also { scaled.compress(android.graphics.Bitmap.CompressFormat.JPEG, 90, it) }.toByteArray()
            target.writeBytes(jpeg)
            jpeg
        }.getOrNull()
    }

    private fun foregroundInfo(title: String, progress: Float?, pending: Int = 0): ForegroundInfo {
        val open = PendingIntent.getActivity(
            applicationContext, 0,
            Intent(applicationContext, MainActivity::class.java).setAction(MainActivity.ACTION_OPEN_DOWNLOADS),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
        val notification = NotificationCompat.Builder(applicationContext, LyraApp.CHANNEL_DOWNLOADS)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(if (pending > 1) "Descargando · quedan $pending" else "Descargando")
            .setContentText(title)
            .setOnlyAlertOnce(true)
            .setOngoing(true)
            .setSilent(true)
            .setContentIntent(open)
            .setProgress(100, ((progress ?: 0f) * 100).toInt(), progress == null)
            .build()
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            ForegroundInfo(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            ForegroundInfo(NOTIFICATION_ID, notification)
        }
    }

    companion object {
        private const val PARALLEL = 2
        private const val NOTIFICATION_ID = 4201
        private const val CHUNK = 4L * 1024 * 1024

        /**
         * Descarga por trozos con cabecera Range: YouTube limita la velocidad de las
         * peticiones de archivo completo, pero sirve rápido los trozos pequeños.
         */
        suspend fun downloadHttp(
            http: OkHttpClient,
            url: String,
            target: File,
            knownLength: Long?,
            onProgress: suspend (Long, Long) -> Unit,
        ) {
            target.parentFile?.mkdirs()
            val partial = File(target.path + ".part")
            partial.delete()
            try {
                if (knownLength == null || knownLength <= 0) {
                    http.newCall(Request.Builder().url(url).build()).execute().use { response ->
                        if (!response.isSuccessful) throw IOException("El servidor respondió ${response.code}")
                        val total = response.body.contentLength()
                        partial.outputStream().use { out -> copyWithProgress(response.body.byteStream(), out, 0, total, onProgress) }
                    }
                } else {
                    var start = 0L
                    partial.outputStream().use { out ->
                        while (start < knownLength) {
                            coroutineContext.ensureActive()
                            val end = minOf(start + CHUNK, knownLength) - 1
                            val request = Request.Builder().url(url).header("Range", "bytes=$start-$end").build()
                            http.newCall(request).execute().use { response ->
                                if (!response.isSuccessful) throw IOException("El servidor respondió ${response.code}")
                                if (response.code == 200 && start > 0) throw IOException("El servidor no admite descargas por partes")
                                start = copyWithProgress(response.body.byteStream(), out, start, knownLength, onProgress)
                                if (response.code == 200) start = knownLength
                            }
                        }
                    }
                }
                if (partial.length() == 0L) throw IOException("El archivo descargado está vacío")
                target.delete()
                if (!partial.renameTo(target)) {
                    partial.copyTo(target, overwrite = true)
                    partial.delete()
                }
            } catch (e: Exception) {
                partial.delete()
                throw e
            }
        }

        private suspend fun copyWithProgress(
            input: java.io.InputStream,
            output: java.io.OutputStream,
            startAt: Long,
            total: Long,
            onProgress: suspend (Long, Long) -> Unit,
        ): Long {
            var done = startAt
            val buffer = ByteArray(64 * 1024)
            input.use {
                while (true) {
                    coroutineContext.ensureActive()
                    val read = it.read(buffer)
                    if (read == -1) break
                    output.write(buffer, 0, read)
                    done += read
                    onProgress(done, total)
                }
            }
            return done
        }
    }
}
