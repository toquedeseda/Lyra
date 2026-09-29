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
    val normalizeVolume: Boolean = true,
    val skipSilence: Boolean = false,
    val infiniteRadio: Boolean = true,
    // Ecualizador (10 bandas, en dB)
    val eqEnabled: Boolean = false,
    val eqPreset: String = "flat",
    val eqBands: List<Float> = List(10) { 0f },
    val eqPreamp: Float = 0f,
    // Descargas
    val downloadQuality: AudioQuality = AudioQuality.HIGH,
    val downloadWifiOnly: Boolean = false,
    val autoDownloadLiked: Boolean = false,
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
        normalizeVolume = p[K.normalize] ?: true,
        skipSilence = p[K.skipSilence] ?: false,
        infiniteRadio = p[K.infiniteRadio] ?: true,
        eqEnabled = p[K.eqEnabled] ?: false,
        eqPreset = p[K.eqPreset] ?: "flat",
        eqBands = p[K.eqBands]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }
            ?: List(10) { 0f },
        eqPreamp = p[K.eqPreamp] ?: 0f,
        downloadQuality = enumOr(p[K.downloadQuality], AudioQuality.HIGH),
        downloadWifiOnly = p[K.wifiOnly] ?: false,
        autoDownloadLiked = p[K.autoDownloadLiked] ?: false,
        islandEnabled = p[K.islandEnabled] ?: false,
        islandMode = enumOr(p[K.islandMode], IslandMode.AUTO),
        islandOffsetX = p[K.islandX] ?: 0,
        islandOffsetY = p[K.islandY] ?: 0,
        islandWidth = p[K.islandWidth] ?: 128,
        islandHeight = p[K.islandHeight] ?: 36,
        language = p[K.language] ?: "es",
        region = p[K.region] ?: "ES",
        showLyrics = p[K.showLyrics] ?: true,
        checkUpdates = p[K.checkUpdates] ?: true,
        lastSeenVersion = p[K.lastSeenVersion] ?: "",
        batteryTipDismissed = p[K.batteryTip] ?: false,
    )

    private fun write(p: MutablePreferences, s: AppSettings) {
        p[K.streamQuality] = s.streamQuality.name
        p[K.crossfade] = s.crossfadeSeconds
        p[K.normalize] = s.normalizeVolume
        p[K.skipSilence] = s.skipSilence
        p[K.infiniteRadio] = s.infiniteRadio
        p[K.eqEnabled] = s.eqEnabled
        p[K.eqPreset] = s.eqPreset
        p[K.eqBands] = s.eqBands.joinToString(",")
        p[K.eqPreamp] = s.eqPreamp
        p[K.downloadQuality] = s.downloadQuality.name
        p[K.wifiOnly] = s.downloadWifiOnly
        p[K.autoDownloadLiked] = s.autoDownloadLiked
        p[K.islandEnabled] = s.islandEnabled
        p[K.islandMode] = s.islandMode.name
        p[K.islandX] = s.islandOffsetX
        p[K.islandY] = s.islandOffsetY
        p[K.islandWidth] = s.islandWidth
        p[K.islandHeight] = s.islandHeight
        p[K.language] = s.language
        p[K.region] = s.region
        p[K.showLyrics] = s.showLyrics
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
                normalizeVolume = values["normalize"]?.toBooleanStrictOrNull() ?: current.normalizeVolume,
                skipSilence = values["skipSilence"]?.toBooleanStrictOrNull() ?: current.skipSilence,
                infiniteRadio = values["infiniteRadio"]?.toBooleanStrictOrNull() ?: current.infiniteRadio,
                eqEnabled = values["eqEnabled"]?.toBooleanStrictOrNull() ?: current.eqEnabled,
                eqPreset = values["eqPreset"] ?: current.eqPreset,
                eqBands = values["eqBands"]?.split(',')?.mapNotNull { it.toFloatOrNull() }?.takeIf { it.size == 10 }
                    ?: current.eqBands,
                eqPreamp = values["eqPreamp"]?.toFloatOrNull() ?: current.eqPreamp,
                downloadQuality = enumOr(values["downloadQuality"], current.downloadQuality),
                downloadWifiOnly = values["wifiOnly"]?.toBooleanStrictOrNull() ?: current.downloadWifiOnly,
                autoDownloadLiked = values["autoDownloadLiked"]?.toBooleanStrictOrNull() ?: current.autoDownloadLiked,
                islandOffsetX = values["islandX"]?.toIntOrNull() ?: current.islandOffsetX,
                islandOffsetY = values["islandY"]?.toIntOrNull() ?: current.islandOffsetY,
                islandWidth = values["islandWidth"]?.toIntOrNull() ?: current.islandWidth,
                islandHeight = values["islandHeight"]?.toIntOrNull() ?: current.islandHeight,
                showLyrics = values["showLyrics"]?.toBooleanStrictOrNull() ?: current.showLyrics,
            )
            write(prefs, restored)
        }
    }

    private inline fun <reified E : Enum<E>> enumOr(name: String?, default: E): E =
        name?.let { runCatching { enumValueOf<E>(it) }.getOrNull() } ?: default

    private object K {
        val streamQuality = stringPreferencesKey("streamQuality")
        val crossfade = intPreferencesKey("crossfade")
        val normalize = booleanPreferencesKey("normalize")
        val skipSilence = booleanPreferencesKey("skipSilence")
        val infiniteRadio = booleanPreferencesKey("infiniteRadio")
        val eqEnabled = booleanPreferencesKey("eqEnabled")
        val eqPreset = stringPreferencesKey("eqPreset")
        val eqBands = stringPreferencesKey("eqBands")
        val eqPreamp = floatPreferencesKey("eqPreamp")
        val downloadQuality = stringPreferencesKey("downloadQuality")
        val wifiOnly = booleanPreferencesKey("wifiOnly")
        val autoDownloadLiked = booleanPreferencesKey("autoDownloadLiked")
        val islandEnabled = booleanPreferencesKey("islandEnabled")
        val islandMode = stringPreferencesKey("islandMode")
        val islandX = intPreferencesKey("islandX")
        val islandY = intPreferencesKey("islandY")
        val islandWidth = intPreferencesKey("islandWidth")
        val islandHeight = intPreferencesKey("islandHeight")
        val language = stringPreferencesKey("language")
        val region = stringPreferencesKey("region")
        val showLyrics = booleanPreferencesKey("showLyrics")
        val checkUpdates = booleanPreferencesKey("checkUpdates")
        val lastSeenVersion = stringPreferencesKey("lastSeenVersion")
        val batteryTip = booleanPreferencesKey("batteryTip")
    }
}
