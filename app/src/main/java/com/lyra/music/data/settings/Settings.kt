package com.lyra.music.data.settings

import android.content.Context
import androidx.datastore.preferences.core.MutablePreferences
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.emptyPreferences
import androidx.datastore.preferences.core.floatPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.soundcloud.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.io.IOException

enum class IslandMode { AUTO, ACCESSIBILITY, OVERLAY }

data class AppSettings(
    val loaded: Boolean = false,
    // Audio
    val streamQuality: AudioQuality = AudioQuality.HIGH,
    val crossfadeSeconds: Int = 6,
    /** Los segundos elegidos la última vez (al volver a activar el fundido). */
    val crossfadeLast: Int = 6,
    /** Sin mezcla entre pistas seguidas de un álbum y mezcla cuando la canción acaba de verdad. */
    val smartCrossfade: Boolean = true,
    /** Aleatorio inteligente: en aleatorio se cuelan canciones recomendadas. */
    val smartShuffle: Boolean = false,
    val normalizeVolume: Boolean = true,
    val skipSilence: Boolean = false,
    val infiniteRadio: Boolean = true,
    /** La radio evita repetir artista seguido y lo escuchado hace poco. */
    val radioNoRepeat: Boolean = true,
    /** Al conectar auriculares o el Bluetooth del coche, sigue sonando lo último. */
    val resumeOnConnect: Boolean = true,
    // Ecualizador (10 bandas, en dB)
    val eqEnabled: Boolean = false,
    val eqPreset: String = "flat",
    val eqBands: List<Float> = List(10) { 0f },
    val eqPreamp: Float = 0f,
    // Descargas
    val downloadQuality: AudioQuality = AudioQuality.HIGH,
    val downloadWifiOnly: Boolean = false,
    val autoDownloadLiked: Boolean = false,
    /** Avisar de las descargas que no escuchas en estos meses (0 = nunca). */
    val staleDownloadMonths: Int = 3,
    /** Descargas en la carpeta visible Música/Lyra (M4A con carátula) en vez de ocultas. */
    val downloadsVisible: Boolean = true,
    val folderMigrationDone: Boolean = false,
    /** Ya se limpiaron los títulos de lo guardado antes de la 1.4 (y de sus archivos). */
    val titlesCleaned: Boolean = false,
    // Isla
    val islandEnabled: Boolean = false,
    val islandMode: IslandMode = IslandMode.AUTO,
    val islandOffsetX: Int = 0,
    val islandOffsetY: Int = 0,
    val islandWidth: Int = 128,
    val islandHeight: Int = 36,
    // Contenido
    val language: String = "es",
    val region: String = "ES",
    val showLyrics: Boolean = true,
    /** Orden de la biblioteca (playlists, carpetas, álbumes y artistas). */
    val librarySort: LibrarySort = LibrarySort.RECENT,
    // Aspecto
    /** Cómo se pasa de una pantalla a otra (se elige en Ajustes). */
    val screenTransition: ScreenTransition = ScreenTransition.SMOOTH,
    /** Barritas bajo la portada y pulso con el bajo en el reproductor. */
    val visualizer: Boolean = true,
    /** Color de los botones y detalles de toda la app. */
    val accentColor: AccentColor = AccentColor.HUESO,
    // App
    val checkUpdates: Boolean = true,
    val lastSeenVersion: String = "",
    val batteryTipDismissed: Boolean = false,
)

private val Context.settingsStore by preferencesDataStore(name = "settings")

class SettingsRepository(context: Context, scope: CoroutineScope) {

    private val appContext = context.applicationContext
    private val store = appContext.settingsStore

    val flow: StateFlow<AppSettings> = store.data
        .catch { if (it is IOException) emit(emptyPreferences()) else throw it }
        .map(::read)
        .stateIn(scope, SharingStarted.Eagerly, AppSettings())

    /** Valor actual (puede ser el de por defecto durante los primeros milisegundos). */
    val current: AppSettings get() = flow.value

    suspend fun loaded(): AppSettings = flow.first { it.loaded }

    suspend fun update(transform: (AppSettings) -> AppSettings) {
        store.edit { prefs -> write(prefs, transform(read(prefs))) }
    }

    /** El visitorData de YouTube se lee de forma síncrona desde hilos de red. */
    val visitorStore: InnerTube.VisitorStore = object : InnerTube.VisitorStore {
        private val prefs = appContext.getSharedPreferences("innertube", Context.MODE_PRIVATE)
        override fun get(): String? = prefs.getString("visitor", null)
        override fun set(value: String) = prefs.edit().putString("visitor", value).apply()
    }

    private fun read(p: Preferences) = AppSettings(
        loaded = true,
        streamQuality = enumOr(p[K.streamQuality], AudioQuality.HIGH),
        crossfadeSeconds = p[K.crossfade] ?: 6,
        crossfadeLast = p[K.crossfadeLast] ?: 6,
        smartCrossfade = p[K.smartCrossfade] ?: true,
        smartShuffle = p[K.smartShuffle] ?: false,
        normalizeVolume = p[K.normalize] ?: true,
        skipSilence = p[K.skipSilence] ?: false,
        infiniteRadio = p[K.infiniteRadio] ?: true,
        radioNoRepeat = p[K.radioNoRepeat] ?: true,
        resumeOnConnect = p[K.resumeOnConnect] ?: true,
        eqEnabled = p[K.eqEnabled] ?: false,
        eqPreset = p[K.eqPreset] ?: "flat",
        eqBands = p[K.eqBands]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }
            ?: List(10) { 0f },
        eqPreamp = p[K.eqPreamp] ?: 0f,
        downloadQuality = enumOr(p[K.downloadQuality], AudioQuality.HIGH),
        downloadWifiOnly = p[K.wifiOnly] ?: false,
        autoDownloadLiked = p[K.autoDownloadLiked] ?: false,
        staleDownloadMonths = p[K.staleMonths] ?: 3,
        downloadsVisible = p[K.downloadsVisible] ?: true,
        folderMigrationDone = p[K.folderMigrationDone] ?: false,
        titlesCleaned = p[K.titlesCleaned] ?: false,
        islandEnabled = p[K.islandEnabled] ?: false,
        islandMode = enumOr(p[K.islandMode], IslandMode.AUTO),
        islandOffsetX = p[K.islandX] ?: 0,
        islandOffsetY = p[K.islandY] ?: 0,
        islandWidth = p[K.islandWidth] ?: 128,
        islandHeight = p[K.islandHeight] ?: 36,
        language = p[K.language] ?: "es",
        region = p[K.region] ?: "ES",
        showLyrics = p[K.showLyrics] ?: true,
        librarySort = enumOr(p[K.librarySort], LibrarySort.RECENT),
        screenTransition = enumOr(p[K.screenTransition], ScreenTransition.SMOOTH),
        visualizer = p[K.visualizer] ?: true,
        accentColor = enumOr(p[K.accentColor], AccentColor.HUESO),
        checkUpdates = p[K.checkUpdates] ?: true,
        lastSeenVersion = p[K.lastSeenVersion] ?: "",
        batteryTipDismissed = p[K.batteryTip] ?: false,
    )

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.streamQuality] = s.streamQuality.name
        p[K.crossfade] = s.crossfadeSeconds
        p[K.crossfadeLast] = s.crossfadeLast
        p[K.smartCrossfade] = s.smartCrossfade
        p[K.smartShuffle] = s.smartShuffle
        p[K.normalize] = s.normalizeVolume
        p[K.skipSilence] = s.skipSilence
        p[K.infiniteRadio] = s.infiniteRadio
        p[K.radioNoRepeat] = s.radioNoRepeat
        p[K.resumeOnConnect] = s.resumeOnConnect
        p[K.eqEnabled] = s.eqEnabled
        p[K.eqPreset] = s.eqPreset
        p[K.eqBands] = s.eqBands.joinToString(",")
        p[K.eqPreamp] = s.eqPreamp
        p[K.downloadQuality] = s.downloadQuality.name
        p[K.wifiOnly] = s.downloadWifiOnly
        p[K.autoDownloadLiked] = s.autoDownloadLiked
        p[K.staleMonths] = s.staleDownloadMonths
        p[K.downloadsVisible] = s.downloadsVisible
        p[K.folderMigrationDone] = s.folderMigrationDone
        p[K.titlesCleaned] = s.titlesCleaned
        p[K.islandEnabled] = s.islandEnabled
        p[K.islandMode] = s.islandMode.name
        p[K.islandX] = s.islandOffsetX
        p[K.islandY] = s.islandOffsetY
        p[K.islandWidth] = s.islandWidth
        p[K.islandHeight] = s.islandHeight
        p[K.language] = s.language
        p[K.region] = s.region
        p[K.showLyrics] = s.showLyrics
        p[K.librarySort] = s.librarySort.name
        p[K.screenTransition] = s.screenTransition.name
        p[K.visualizer] = s.visualizer
        p[K.accentColor] = s.accentColor.name
        p[K.checkUpdates] = s.checkUpdates
        p[K.lastSeenVersion] = s.lastSeenVersion
        p[K.batteryTip] = s.batteryTipDismissed
    }

    /** Todos los ajustes como texto, para la copia de seguridad. */
    suspend fun export(): Map<String, String> =
        store.data.first().asMap().mapKeys { it.key.name }.mapValues { it.value.toString() }

    suspend fun import(values: Map<String, String>) {
        store.edit { prefs ->
            val current = read(prefs)
            val restored = current.copy(
                streamQuality = enumOr(values["streamQuality"], current.streamQuality),
                crossfadeSeconds = values["crossfade"]?.toIntOrNull() ?: current.crossfadeSeconds,
                crossfadeLast = values["crossfadeLast"]?.toIntOrNull() ?: current.crossfadeLast,
                smartCrossfade = values["smartCrossfade"]?.toBooleanStrictOrNull() ?: current.smartCrossfade,
                smartShuffle = values["smartShuffle"]?.toBooleanStrictOrNull() ?: current.smartShuffle,
                normalizeVolume = values["normalize"]?.toBooleanStrictOrNull() ?: current.normalizeVolume,
                skipSilence = values["skipSilence"]?.toBooleanStrictOrNull() ?: current.skipSilence,
                infiniteRadio = values["infiniteRadio"]?.toBooleanStrictOrNull() ?: current.infiniteRadio,
                radioNoRepeat = values["radioNoRepeat"]?.toBooleanStrictOrNull() ?: current.radioNoRepeat,
                resumeOnConnect = values["resumeOnConnect"]?.toBooleanStrictOrNull() ?: current.resumeOnConnect,
                eqEnabled = values["eqEnabled"]?.toBooleanStrictOrNull() ?: current.eqEnabled,
                eqPreset = values["eqPreset"] ?: current.eqPreset,
                eqBands = values["eqBands"]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }
                    ?: current.eqBands,
                eqPreamp = values["eqPreamp"]?.toFloatOrNull() ?: current.eqPreamp,
                downloadQuality = enumOr(values["downloadQuality"], current.downloadQuality),
                downloadWifiOnly = values["wifiOnly"]?.toBooleanStrictOrNull() ?: current.downloadWifiOnly,
                autoDownloadLiked = values["autoDownloadLiked"]?.toBooleanStrictOrNull() ?: current.autoDownloadLiked,
                staleDownloadMonths = values["staleMonths"]?.toIntOrNull() ?: current.staleDownloadMonths,
                downloadsVisible = values["downloadsVisible"]?.toBooleanStrictOrNull() ?: current.downloadsVisible,
                islandOffsetX = values["islandX"]?.toIntOrNull() ?: current.islandOffsetX,
                islandOffsetY = values["islandY"]?.toIntOrNull() ?: current.islandOffsetY,
                islandWidth = values["islandWidth"]?.toIntOrNull() ?: current.islandWidth,
                islandHeight = values["islandHeight"]?.toIntOrNull() ?: current.islandHeight,
                showLyrics = values["showLyrics"]?.toBooleanStrictOrNull() ?: current.showLyrics,
                librarySort = enumOr(values["librarySort"], current.librarySort),
                screenTransition = enumOr(values["screenTransition"], current.screenTransition),
                visualizer = values["visualizer"]?.toBooleanStrictOrNull() ?: current.visualizer,
                accentColor = enumOr(values["accentColor"], current.accentColor),
            )
            write(prefs, restored)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private object K {
        val streamQuality = stringPreferencesKey("streamQuality")
        val crossfade = intPreferencesKey("crossfade")
        val smartCrossfade = booleanPreferencesKey("smartCrossfade")
        val crossfadeLast = intPreferencesKey("crossfadeLast")
        val smartShuffle = booleanPreferencesKey("smartShuffle")
        val normalize = booleanPreferencesKey("normalize")
        val skipSilence = booleanPreferencesKey("skipSilence")
        val infiniteRadio = booleanPreferencesKey("infiniteRadio")
        val radioNoRepeat = booleanPreferencesKey("radioNoRepeat")
        val resumeOnConnect = booleanPreferencesKey("resumeOnConnect")
        val eqEnabled = booleanPreferencesKey("eqEnabled")
        val eqPreset = stringPreferencesKey("eqPreset")
        val eqBands = stringPreferencesKey("eqBands")
        val eqPreamp = floatPreferencesKey("eqPreamp")
        val downloadQuality = stringPreferencesKey("downloadQuality")
        val wifiOnly = booleanPreferencesKey("wifiOnly")
        val autoDownloadLiked = booleanPreferencesKey("autoDownloadLiked")
        val staleMonths = intPreferencesKey("staleMonths")
        val downloadsVisible = booleanPreferencesKey("downloadsVisible")
        val folderMigrationDone = booleanPreferencesKey("folderMigrationDone")
        val titlesCleaned = booleanPreferencesKey("titlesCleaned")
        val islandEnabled = booleanPreferencesKey("islandEnabled")
        val islandMode = stringPreferencesKey("islandMode")
        val islandX = intPreferencesKey("islandX")
        val islandY = intPreferencesKey("islandY")
        val islandWidth = intPreferencesKey("islandWidth")
        val islandHeight = intPreferencesKey("islandHeight")
        val language = stringPreferencesKey("language")
        val region = stringPreferencesKey("region")
        val showLyrics = booleanPreferencesKey("showLyrics")
        val librarySort = stringPreferencesKey("librarySort")
        val screenTransition = stringPreferencesKey("screenTransition")
        val visualizer = booleanPreferencesKey("visualizer")
        val accentColor = stringPreferencesKey("accentColor")
        val checkUpdates = booleanPreferencesKey("checkUpdates")
        val lastSeenVersion = stringPreferencesKey("lastSeenVersion")
        val batteryTip = booleanPreferencesKey("batteryTip")
    }
}

/**
 * Color de Lyra (botones, interruptores, barras…). Tonos claros y suaves para que casen con el
 * negro y los iconos oscuros encima se lean bien. El rojo del corazón de "Me gusta" no cambia.
 */
enum class AccentColor(val label: String, val argb: Long) {
    HUESO("Blanco hueso", 0xFFE8E6DF),
    CORAL("Coral", 0xFFEE8A78),
    AMBAR("Ámbar", 0xFFF2C46D),
    VERDE("Verde", 0xFF86D6AB),
    AZUL("Azul", 0xFF8DB4F5),
    MORADO("Morado", 0xFFB9A0F4),
    ROSA("Rosa", 0xFFF4A3C8),
}

/** Cómo se ordena la biblioteca. */
enum class LibrarySort(val label: String) {
    /** Lo último que escuchaste (o guardaste, o cambiaste) arriba, como en Spotify. */
    RECENT("Recientes"),
    PLAYS("Más escuchadas"),
    NAME("Nombre"),
    ADDED("Añadidas hace poco"),
}

/** Animación al cambiar de pantalla. */
enum class ScreenTransition {
    /** La de siempre, más suave: la pantalla nueva entra deslizándose un poco y la anterior se aparta. */
    SMOOTH,

    /** La portada que tocas vuela hasta la cabecera de la pantalla nueva. */
    COVER,

    /** Sin animación: cambio instantáneo. */
    NONE,
}
