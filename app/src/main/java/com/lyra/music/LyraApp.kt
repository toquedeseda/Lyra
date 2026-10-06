package com.lyra.music

import android.app.Application
import android.app.NotificationChannel
import android.app.NotificationManager
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.ProcessLifecycleOwner
import androidx.media3.common.util.UnstableApi
import androidx.media3.database.StandaloneDatabaseProvider
import androidx.media3.datasource.cache.LeastRecentlyUsedCacheEvictor
import androidx.media3.datasource.cache.SimpleCache
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.lyra.music.core.Http
import com.lyra.music.data.backup.BackupManager
import com.lyra.music.data.db.LyraDatabase
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.repo.HomeRepository
import com.lyra.music.data.repo.LibraryRepository
import com.lyra.music.data.repo.LyricsRepository
import com.lyra.music.data.repo.MusicRepository
import com.lyra.music.data.settings.SettingsRepository
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.lyrics.Lrclib
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.island.IslandController
import com.lyra.music.playback.LyraDataSourceFactory
import com.lyra.music.playback.PlayerConnection
import com.lyra.music.playback.StreamResolver
import com.lyra.music.update.UpdateRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.launch
import java.io.File

/** Peticiones que llegan desde fuera de la interfaz (notificación, isla, compartir…). */
sealed interface AppEvent {
    data object OpenPlayer : AppEvent
    data object OpenDownloads : AppEvent
    data class OpenLink(val url: String) : AppEvent
    data object OpenUpdate : AppEvent
    data object UpdateNow : AppEvent
    data class OpenAlbum(val id: String) : AppEvent
    data class OpenLocalPlaylist(val id: Long) : AppEvent
}

/** Todas las dependencias de la app, creadas una sola vez por proceso. */
@UnstableApi
class AppContainer(val app: Application) {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    val http = Http.create(app)
    val settings = SettingsRepository(app, scope)
    val network = com.lyra.music.core.NetworkMonitor(app)
    val database = LyraDatabase.build(app)
    val innerTube = InnerTube(http, settings.visitorStore)
    val newPipe by lazy { NewPipeSource(http) }
    val music by lazy { MusicRepository(innerTube, newPipe) }
    val downloads = DownloadRepository(app, database, settings, scope)
    val library = LibraryRepository(app, database, downloads, settings, scope)
    val lyrics by lazy { LyricsRepository(Lrclib(http), innerTube, database) }
    val home by lazy { HomeRepository(app, music, library, scope) { releases.releases() } }
    val alternatives by lazy {
        com.lyra.music.playback.AlternativeSources(newPipe, innerTube, { id -> database.songs().get(id)?.toSong() }, File(app.filesDir, "alternatives.json"))
    }
    val streamResolver by lazy { StreamResolver(newPipe, settings, http, app.cacheDir, alternatives) }
    val playerCache by lazy {
        SimpleCache(
            File(app.cacheDir, "player"),
            LeastRecentlyUsedCacheEvictor(768L * 1024 * 1024),
            StandaloneDatabaseProvider(app),
        )
    }
    val dataSourceFactory by lazy { LyraDataSourceFactory(app, http, streamResolver, downloads, playerCache) }
    val player by lazy { PlayerConnection(app, scope, downloads) }
    val island by lazy {
        IslandController(app, settings, scope, downloads.states, library.likedIds) { id -> database.songs().get(id)?.toSong() }
    }
    val updates by lazy { UpdateRepository(app, http, scope) }
    val sharing by lazy { com.lyra.music.data.share.PlaylistSharing(http) }
    val backup by lazy { BackupManager(app, database, settings, downloads) }
    val spotifyImport by lazy {
        com.lyra.music.data.repo.SpotifyImportManager(
            com.lyra.music.data.repo.SpotifyImporter(http, innerTube), library, scope, app.filesDir,
        )
    }
    val recommender by lazy { com.lyra.music.data.repo.Recommender(music) }
    val releases by lazy { com.lyra.music.data.repo.ReleasesRepository(app, music, library) }
    val playlistSync by lazy { com.lyra.music.data.repo.PlaylistSync(database, music, library, downloads, spotifyImport) }

    // Canal con búfer: si el evento llega antes de que la interfaz escuche
    // (p. ej. al abrir la app desde la notificación), se guarda hasta entonces.
    private val _events = Channel<AppEvent>(Channel.BUFFERED)
    val events: Flow<AppEvent> = _events.receiveAsFlow()
    fun send(event: AppEvent) {
        _events.trySend(event)
    }

    /** Vacía la caché de reproducción (no toca las descargas). */
    fun clearPlayerCache() {
        playerCache.keys.toList().forEach { runCatching { playerCache.removeResource(it) } }
    }
}

@UnstableApi
class LyraApp : Application(), SingletonImageLoader.Factory {

    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        // Lo primero: así también se apunta un cierre que pase al arrancar.
        com.lyra.music.core.ErrorLog.install(this)
        // En la versión de pruebas, Android avisa (en el registro) de lo que hace esperar a la pantalla:
        // leer el disco o usar internet en el hilo principal, y cosas que se quedan abiertas.
        if (BuildConfig.DEBUG) {
            android.os.StrictMode.setThreadPolicy(
                android.os.StrictMode.ThreadPolicy.Builder().detectDiskReads().detectDiskWrites().detectNetwork().penaltyLog().build(),
            )
            android.os.StrictMode.setVmPolicy(
                android.os.StrictMode.VmPolicy.Builder()
                    .detectLeakedClosableObjects().detectLeakedSqlLiteObjects().detectActivityLeaks().detectLeakedRegistrationObjects()
                    .penaltyLog().build(),
            )
        }
        container = AppContainer(this)
        createChannels()
        com.lyra.music.data.Notifier.createChannels(this)
        // WorkManager tarda en prepararse: se hace fuera del hilo de la pantalla.
        container.scope.launch(kotlinx.coroutines.Dispatchers.Default) {
            com.lyra.music.data.MaintenanceWorker.schedule(this@LyraApp)
        }

        // La isla no se muestra mientras Lyra está en pantalla.
        ProcessLifecycleOwner.get().lifecycle.addObserver(
            LifecycleEventObserver { _, event ->
                when (event) {
                    Lifecycle.Event.ON_START -> container.island.setAppVisible(true)
                    Lifecycle.Event.ON_STOP -> container.island.setAppVisible(false)
                    else -> Unit
                }
            },
        )

        container.scope.launch {
            container.settings.flow.collect {
                container.innerTube.language = it.language
                container.innerTube.region = it.region
                com.lyra.music.ui.theme.LyraColors.setAccent(androidx.compose.ui.graphics.Color(it.accentColor.argb))
            }
        }
        // Si quedaron descargas a medias, se retoman.
        container.scope.launch {
            if (container.database.downloads().pendingCount() > 0) container.downloads.start()
        }
        // Una vez: títulos limpios en lo que ya estaba guardado (y en los archivos descargados).
        container.scope.launch {
            val settings = container.settings.loaded()
            if (!settings.titlesCleaned) {
                runCatching { container.downloads.relabel(container.library.cleanStoredTitles()) }
                container.settings.update { it.copy(titlesCleaned = true) }
            }
        }
        // Una vez: lo que estaba descargado a escondidas pasa a Música/Lyra.
        container.scope.launch {
            val settings = container.settings.loaded()
            if (settings.downloadsVisible && !settings.folderMigrationDone) {
                container.downloads.migrateHiddenToFolder()
                container.settings.update { it.copy(folderMigrationDone = true) }
            }
        }
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = { container.http })) }
            .crossfade(true)
            .build()

    private fun createChannels() {
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_DOWNLOADS, getString(R.string.channel_downloads), NotificationManager.IMPORTANCE_LOW),
        )
    }

    companion object {
        const val CHANNEL_PLAYBACK = "playback"
        const val CHANNEL_DOWNLOADS = "downloads"
    }
}
