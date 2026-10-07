package com.lyra.desktop

import com.lyra.desktop.audio.AudioCache
import com.lyra.desktop.audio.StreamResolver
import com.lyra.desktop.data.Downloads
import com.lyra.desktop.data.HomeRepository
import com.lyra.desktop.data.Library
import com.lyra.desktop.data.LyricsRepository
import com.lyra.desktop.data.SettingsStore
import com.lyra.desktop.player.PlayerController
import com.lyra.music.data.repo.MusicRepository
import com.lyra.music.data.repo.SpotifyImporter
import com.lyra.music.data.share.PlaylistSharing
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.lyrics.Lrclib
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.playback.AlternativeSources
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import okhttp3.Cache
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/** Todo lo que necesita Lyra en el PC, creado una sola vez al abrir. */
class AppContainer {
    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e ->
            ErrorLog.record("Error interno", e.message ?: e.javaClass.simpleName, e)
        },
    )

    val http: OkHttpClient = OkHttpClient.Builder()
        .connectTimeout(15, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .writeTimeout(30, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .cache(Cache(Paths.httpCache, 32L * 1024 * 1024))
        .build()

    val settings = SettingsStore(Paths.settings, scope)
    val library = Library(Paths.library, scope)

    /** El `visitorData` de YouTube Music, guardado para que el Inicio siga personalizado. */
    private val visitorStore = object : InnerTube.VisitorStore {
        private val file = File(Paths.data, "visitor.txt")
        @Volatile private var value: String? = runCatching { file.readText().trim() }.getOrNull()?.ifBlank { null }
        override fun get() = value
        override fun set(value: String) {
            if (value == this.value) return
            this.value = value
            runCatching { file.writeText(value) }
        }
    }

    val innerTube = InnerTube(http, visitorStore).apply {
        language = settings.current.language
        region = settings.current.region
    }
    val newPipe = NewPipeSource(http)
    val music = MusicRepository(innerTube, newPipe)
    val alternatives = AlternativeSources(newPipe, innerTube, { id -> library.song(id) }, Paths.alternatives)
    val audioCache = AudioCache(Paths.audioCache, http) { settings.current.cacheLimitMb.toLong() * 1024 * 1024 }

    /** Lo de SoundCloud que llega en trozos (HLS) se junta directamente en la caché de lo escuchado. */
    val resolver = StreamResolver(newPipe, alternatives, http, audioCache::fileFor) { settings.current.streamQuality }
    val downloads = Downloads(File(Paths.data, "descargas.json"), resolver, http, settings, scope)
    val player = PlayerController(scope, settings, library, music, resolver, audioCache, downloads::localFile, Paths.queue)
    val lyrics = LyricsRepository(Lrclib(http), innerTube, Paths.lyricsCache)
    val home = HomeRepository(music, library, Paths.homeCache, scope)
    val sharing = PlaylistSharing(http)
    val spotify = SpotifyImporter(http, innerTube)
    val updater = com.lyra.desktop.update.Updater(http, scope) { settings.current.checkUpdates }
    val sync = com.lyra.desktop.sync.SyncManager(http, library, settings, File(Paths.data, "sincronizacion.json"), scope)

    init {
        // La carpeta antigua de SoundCloud (hasta la 1.10.1 crecía sin límite): ya no se usa.
        scope.launch(Dispatchers.IO) { runCatching { Paths.oldHlsCache.deleteRecursively() } }
    }

    /** Al cerrar: guarda todo ya (sin esperar a los guardados automáticos). */
    fun shutdown() {
        runCatching { player.shutdown() }
        runCatching { library.saveNow() }
        runCatching { settings.saveNow() }
        runCatching { downloads.saveNow() }
    }
}
