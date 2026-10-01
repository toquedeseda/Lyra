package com.lyra.music.core

import android.content.Context
import android.os.Build
import com.lyra.music.BuildConfig
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class ErrorEntry(val time: Long, val kind: String, val message: String, val detail: String? = null)

/**
 * Informe de errores: cierres inesperados y fallos al reproducir, descargar,
 * actualizar o importar. Se guarda solo en el móvil y se comparte cuando tú
 * quieras (Ajustes → Informe de errores). No lleva cuentas ni claves: no hay.
 */
object ErrorLog {
    private const val MAX = 150
    private const val CRASH = "Cierre"
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private var file: File? = null
    private var crashFlag: File? = null

    private val _entries = MutableStateFlow<List<ErrorEntry>>(emptyList())
    val entries: StateFlow<List<ErrorEntry>> = _entries.asStateFlow()

    /** Se llama al arrancar la app: carga lo guardado y apunta los cierres inesperados. */
    fun install(context: Context) {
        val dir = File(context.filesDir, "errors").apply { mkdirs() }
        file = File(dir, "log.jsonl")
        crashFlag = File(dir, "crash_pending")
        _entries.value = runCatching {
            file!!.readLines().mapNotNull { line -> runCatching { json.decodeFromString(ErrorEntry.serializer(), line) }.getOrNull() }
        }.getOrDefault(emptyList())

        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            runCatching {
                add(ErrorEntry(System.currentTimeMillis(), CRASH, error.toString(), "Hilo: ${thread.name}\n" + error.stackTraceToString().take(8_000)))
                crashFlag?.writeText(System.currentTimeMillis().toString())
            }
            previous?.uncaughtException(thread, error)
        }
    }

    /** Apunta un fallo. [kind]: "Reproducción", "Descarga", "Actualización"… */
    fun record(kind: String, message: String, error: Throwable? = null, extra: String? = null) {
        val trace = error?.stackTraceToString()?.lineSequence()?.take(25)?.joinToString("\n")
        val detail = listOfNotNull(extra, trace).joinToString("\n").ifEmpty { null }
        runCatching { add(ErrorEntry(System.currentTimeMillis(), kind, message, detail)) }
    }

    private fun add(entry: ErrorEntry) {
        synchronized(lock) {
            val last = _entries.value.firstOrNull()
            // El mismo fallo repetido al momento (reintentos) se apunta una sola vez.
            if (last != null && last.kind == entry.kind && last.message == entry.message && entry.time - last.time < 60_000) return
            val list = (listOf(entry) + _entries.value).take(MAX)
            _entries.value = list
            file?.writeText(list.joinToString("\n") { json.encodeToString(ErrorEntry.serializer(), it) })
        }
    }

    /** El cierre de la última vez, si aún no se ha avisado de él. */
    fun takePendingCrash(): ErrorEntry? {
        val flag = crashFlag ?: return null
        if (!flag.exists()) return null
        flag.delete()
        return _entries.value.firstOrNull { it.kind == CRASH }
    }

    fun clear() = synchronized(lock) {
        _entries.value = emptyList()
        file?.delete()
    }

    /** Texto para copiar o compartir: versión, móvil y los últimos errores. */
    fun report(): String = buildString {
        appendLine("Informe de errores de Lyra ${BuildConfig.VERSION_NAME}")
        appendLine("Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT}) · ${Build.MANUFACTURER} ${Build.MODEL}")
        appendLine("Hecho el ${format(System.currentTimeMillis())}")
        val list = _entries.value
        if (list.isEmpty()) appendLine("\nSin errores guardados.")
        list.take(50).forEach { entry ->
            appendLine()
            appendLine("[${format(entry.time)}] ${entry.kind}: ${entry.message}")
            entry.detail?.let { appendLine(it.prependIndent("    ")) }
        }
    }

    fun format(time: Long): String = SimpleDateFormat("d MMM HH:mm", Locale.forLanguageTag("es")).format(Date(time))
}
