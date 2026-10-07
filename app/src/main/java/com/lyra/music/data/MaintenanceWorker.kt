package com.lyra.music.data

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.lyra.music.LyraApp
import com.lyra.music.MainActivity
import com.lyra.music.R
import com.lyra.music.update.UpdateInfo
import java.util.concurrent.TimeUnit

/**
 * Tareas en segundo plano cada 6 horas (las actualizaciones van aparte, en [UpdateCheckWorker]):
 *  - Una vez al mes: avisar de las descargas que no escuchas.
 *  - Cada 12 horas: lanzamientos de tus artistas y playlists sincronizadas.
 */
class MaintenanceWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as LyraApp).container
    private val prefs = context.getSharedPreferences("maintenance", Context.MODE_PRIVATE)

    override suspend fun doWork(): Result {
        val settings = container.settings.loaded()
        val now = System.currentTimeMillis()
        // La biblioteca sincronizada con el PC se pone al día aunque no abras la app.
        if (container.sync.paired) runCatching { container.sync.syncNow() }
        runCatching { container.trimHlsCache() }
        // Una vez al mes como mucho: descargas que llevas meses sin escuchar.
        if (settings.staleDownloadMonths > 0 && now - prefs.getLong("cleanup_at", 0) > 30 * 86_400_000L) {
            val stale = runCatching { container.downloads.staleNow(settings.staleDownloadMonths) }.getOrDefault(emptyList())
            val bytes = stale.sumOf { it.totalBytes }
            if (stale.size >= 5 || bytes >= 100L * 1024 * 1024) {
                Notifier.cleanup(applicationContext, stale.size, bytes, settings.staleDownloadMonths)
                prefs.edit().putLong("cleanup_at", now).apply()
            }
        }
        if (now - prefs.getLong("library_at", 0) > 11 * 3_600_000L) {
            checkReleases()
            runCatching { container.playlistSync.syncAll() }.getOrNull()?.forEach { result ->
                Notifier.playlist(applicationContext, result.playlistId, result.name, result.added)
            }
            prefs.edit().putLong("library_at", now).apply()
        }
        return Result.success()
    }

    private suspend fun checkReleases() {
        val releases = runCatching { container.releases.releases(maxAgeMs = 0) }.getOrNull() ?: return
        val known = prefs.getStringSet("known_releases", null)
        val ids = releases.map { it.id }.toSet()
        // La primera vez solo se apunta lo que hay, para no avisar de todo de golpe.
        if (known != null) {
            releases.filter { it.id !in known }.take(3).forEach { album ->
                Notifier.release(applicationContext, album.id, album.artistsText, album.title)
            }
        }
        prefs.edit().putStringSet("known_releases", ids + known.orEmpty()).apply()
    }

    companion object {
        private const val NAME = "lyra-maintenance"

        fun schedule(context: Context) {
            val network = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            val request = PeriodicWorkRequestBuilder<MaintenanceWorker>(6, TimeUnit.HOURS)
                .setConstraints(network)
                .setInitialDelay(20, TimeUnit.MINUTES)
                .build()
            val manager = WorkManager.getInstance(context)
            manager.enqueueUniquePeriodicWork(NAME, ExistingPeriodicWorkPolicy.KEEP, request)
            // Las actualizaciones, cada 15 minutos (lo mínimo que deja Android) para avisar casi al momento.
            val updates = PeriodicWorkRequestBuilder<UpdateCheckWorker>(15, TimeUnit.MINUTES)
                .setConstraints(network)
                .build()
            manager.enqueueUniquePeriodicWork(UpdateCheckWorker.NAME, ExistingPeriodicWorkPolicy.KEEP, updates)
        }
    }
}

/** Mira si hay versión nueva de Lyra en GitHub y avisa con una notificación (una vez por versión). */
class UpdateCheckWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    private val container = (context.applicationContext as LyraApp).container

    override suspend fun doWork(): Result {
        if (!container.settings.loaded().checkUpdates) return Result.success()
        runCatching { container.updates.latestIfNewer() }.getOrNull()?.let { info ->
            if (container.updates.shouldNotify(info.version)) {
                Notifier.update(applicationContext, info)
                container.updates.markNotified(info.version)
            }
        }
        return Result.success()
    }

    companion object {
        const val NAME = "lyra-update-check"
    }
}

/** Notificaciones de actualizaciones, lanzamientos y playlists. */
object Notifier {
    const val CHANNEL_UPDATES = "updates"
    const val CHANNEL_RELEASES = "releases"
    const val CHANNEL_LIBRARY = "library"
    const val CHANNEL_STORAGE = "storage"

    fun createChannels(context: Context) {
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL_UPDATES, "Actualizaciones", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_RELEASES, "Lanzamientos de tus artistas", NotificationManager.IMPORTANCE_DEFAULT))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_LIBRARY, "Playlists sincronizadas", NotificationManager.IMPORTANCE_LOW))
        manager.createNotificationChannel(NotificationChannel(CHANNEL_STORAGE, "Espacio de las descargas", NotificationManager.IMPORTANCE_LOW))
    }

    fun update(context: Context, info: UpdateInfo) {
        val notes = info.notes.lines().map { it.trim().removePrefix("-").removePrefix("•").trim() }
            .filter { it.isNotEmpty() && !it.startsWith("#") }.take(4).joinToString("\n")
        val builder = NotificationCompat.Builder(context, CHANNEL_UPDATES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Lyra ${info.version} disponible")
            .setContentText(notes.lines().firstOrNull() ?: "Toca para ver las novedades")
            .setStyle(NotificationCompat.BigTextStyle().bigText(notes.ifEmpty { "Toca para ver las novedades" }))
            .setContentIntent(activity(context, MainActivity.ACTION_OPEN_UPDATE, 10))
            .addAction(0, "Actualizar", activity(context, MainActivity.ACTION_UPDATE_NOW, 11))
            .setAutoCancel(true)
        post(context, 5001, builder)
    }

    fun release(context: Context, albumId: String, artist: String, title: String) {
        val builder = NotificationCompat.Builder(context, CHANNEL_RELEASES)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Nuevo de $artist")
            .setContentText(title)
            .setContentIntent(activity(context, MainActivity.ACTION_OPEN_ALBUM, albumId.hashCode()) { putExtra(MainActivity.EXTRA_ID, albumId) })
            .setAutoCancel(true)
        post(context, 6000 + (albumId.hashCode() and 0xFFF), builder)
    }

    fun playlist(context: Context, playlistId: Long, name: String, added: Int) {
        val builder = NotificationCompat.Builder(context, CHANNEL_LIBRARY)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle(name)
            .setContentText(if (added == 1) "1 canción nueva" else "$added canciones nuevas")
            .setContentIntent(activity(context, MainActivity.ACTION_OPEN_PLAYLIST, playlistId.toInt()) { putExtra(MainActivity.EXTRA_ID, playlistId.toString()) })
            .setAutoCancel(true)
        post(context, 7000 + playlistId.toInt(), builder)
    }

    fun cleanup(context: Context, count: Int, bytes: Long, months: Int) {
        val size = if (bytes >= 1L shl 30) "%.1f GB".format(bytes / (1L shl 30).toDouble()) else "${bytes / (1L shl 20)} MB"
        val period = if (months == 12) "un año" else "$months meses"
        val builder = NotificationCompat.Builder(context, CHANNEL_STORAGE)
            .setSmallIcon(R.drawable.ic_notification)
            .setContentTitle("Puedes liberar $size")
            .setContentText("$count descargas que no escuchas desde hace $period")
            .setContentIntent(activity(context, MainActivity.ACTION_OPEN_DOWNLOADS, 12))
            .setAutoCancel(true)
        post(context, 8001, builder)
    }

    private fun activity(context: Context, action: String, code: Int, extras: Intent.() -> Unit = {}): PendingIntent =
        PendingIntent.getActivity(
            context,
            code,
            Intent(context, MainActivity::class.java).setAction(action).apply(extras),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )

    private fun post(context: Context, id: Int, builder: NotificationCompat.Builder) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU &&
            ContextCompat.checkSelfPermission(context, android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        NotificationManagerCompat.from(context).notify(id, builder.build())
    }
}
