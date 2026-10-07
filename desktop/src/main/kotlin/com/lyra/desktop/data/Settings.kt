package com.lyra.desktop.data

import com.lyra.music.data.source.soundcloud.AudioQuality
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/** Color de Lyra, los mismos que en el móvil (el rojo del corazón no cambia). */
enum class AccentColor(val label: String, val argb: Long) {
    HUESO("Blanco hueso", 0xFFE8E6DF),
    CORAL("Coral", 0xFFEE8A78),
    AMBAR("Ámbar", 0xFFF2C46D),
    VERDE("Verde", 0xFF86D6AB),
    AZUL("Azul", 0xFF8DB4F5),
    MORADO("Morado", 0xFFB9A0F4),
    ROSA("Rosa", 0xFFF4A3C8),
}

/** Orden de la biblioteca (como en el móvil). */
enum class LibrarySort(val label: String) {
    RECENT("Recientes"),
    PLAYS("Más escuchadas"),
    NAME("Nombre"),
    ADDED("Añadidas hace poco"),
}

/** Lo que se ve a la derecha: la cola, la letra o la canción que suena. */
enum class RightPanel { NONE, QUEUE, LYRICS, NOW_PLAYING }

enum class RepeatMode { OFF, ALL, ONE }

@Serializable
data class WindowBounds(val x: Int, val y: Int, val width: Int, val height: Int, val maximized: Boolean = false)

/** Emparejamiento con el móvil (biblioteca sincronizada por el servidor de Lyra). */
@Serializable
data class SyncConfig(
    val libraryId: String,
    val token: String,
    val deviceId: String,
    val lastRev: Long = 0,
    val lastSyncAt: Long = 0,
)

@Serializable
data class DesktopSettings(
    // Sonido
    val streamQuality: AudioQuality = AudioQuality.HIGH,
    /** Fundido entre canciones en segundos (0 = desactivado), como en Spotify. */
    val crossfadeSeconds: Int = 0,
    /** Los segundos elegidos la última vez (para volver a ellos al activarlo). */
    val crossfadeLast: Int = 5,
    val smartCrossfade: Boolean = true,
    val normalizeVolume: Boolean = true,
    val eqEnabled: Boolean = false,
    val eqPreset: String = "flat",
    val eqBands: List<Float> = List(10) { 0f },
    val eqPreamp: Float = 0f,
    val infiniteRadio: Boolean = true,
    val radioNoRepeat: Boolean = true,
    val volume: Float = 0.7f,
    val muted: Boolean = false,
    val shuffle: Boolean = false,
    val repeat: RepeatMode = RepeatMode.OFF,
    // Descargas
    val downloadQuality: AudioQuality = AudioQuality.HIGH,
    val downloadsFolder: String? = null,
    // Aspecto y ventana
    val accent: AccentColor = AccentColor.HUESO,
    val librarySort: LibrarySort = LibrarySort.RECENT,
    val libraryFilter: Int = 0,
    val rightPanel: RightPanel = RightPanel.QUEUE,
    val window: WindowBounds? = null,
    val closeToTray: Boolean = false,
    /** Ya se avisó (una vez) de que, al cerrar, Lyra sigue en la bandeja. */
    val trayHintShown: Boolean = false,
    val startWithWindows: Boolean = false,
    val miniX: Int? = null,
    val miniY: Int? = null,
    // Otros
    val discordPresence: Boolean = true,
    val showLyrics: Boolean = true,
    val cacheLimitMb: Int = 1024,
    val checkUpdates: Boolean = true,
    val lastSeenVersion: String = "",
    /** Ya se cerró la tarjeta del Inicio que invita a sincronizar con el móvil. */
    val syncHintDismissed: Boolean = false,
    val language: String = "es",
    val region: String = "ES",
    val sync: SyncConfig? = null,
)

/** Ajustes en %APPDATA%\Lyra\ajustes.json. Se guardan solos medio segundo después de cambiar. */
@OptIn(FlowPreview::class)
class SettingsStore(private val file: File, scope: CoroutineScope) {
    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        prettyPrint = true
        coerceInputValues = true
    }
    private val state = MutableStateFlow(load())
    val flow: StateFlow<DesktopSettings> = state.asStateFlow()
    val current: DesktopSettings get() = state.value

    init {
        scope.launch(Dispatchers.IO) {
            state.drop(1).debounce(500).collect { save(it) }
        }
    }

    fun update(transform: (DesktopSettings) -> DesktopSettings) = state.update(transform)

    private fun load(): DesktopSettings =
        runCatching { json.decodeFromString(DesktopSettings.serializer(), file.readText()) }.getOrNull() ?: DesktopSettings()

    fun saveNow() = save(state.value)

    @Synchronized
    private fun save(settings: DesktopSettings) {
        runCatching {
            val tmp = File(file.path + ".tmp")
            tmp.writeText(json.encodeToString(DesktopSettings.serializer(), settings))
            Files.move(tmp.toPath(), file.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
        }
    }
}
