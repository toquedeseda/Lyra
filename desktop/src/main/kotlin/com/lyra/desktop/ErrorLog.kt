package com.lyra.desktop

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.PrintWriter
import java.io.StringWriter
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Serializable
data class ErrorEntry(val time: Long, val kind: String, val message: String, val detail: String? = null)

/**
 * Informe de errores, como en el móvil: cierres y fallos al reproducir, descargar, actualizar o
 * sincronizar. Se guarda solo en el PC y se copia cuando tú quieras (Ajustes → Informe de errores).
 */
object ErrorLog {
    private const val MAX = 200
    private val json = Json { ignoreUnknownKeys = true }
    private val lock = Any()
    private val _entries = MutableStateFlow<List<ErrorEntry>>(emptyList())
    val entries: StateFlow<List<ErrorEntry>> = _entries.asStateFlow()

    fun install() {
        _entries.value = runCatching {
            Paths.errors.readLines().mapNotNull { line -> runCatching { json.decodeFromString(ErrorEntry.serializer(), line) }.getOrNull() }
        }.getOrDefault(emptyList()).takeLast(MAX)
        val previous = Thread.getDefaultUncaughtExceptionHandler()
        Thread.setDefaultUncaughtExceptionHandler { thread, error ->
            record("Cierre", error.message ?: error.javaClass.simpleName, error, extra = "hilo ${thread.name}")
            previous?.uncaughtException(thread, error)
        }
    }

    fun record(kind: String, message: String, error: Throwable? = null, extra: String? = null) {
        val detail = buildString {
            extra?.let { appendLine(it) }
            error?.let {
                val writer = StringWriter()
                it.printStackTrace(PrintWriter(writer))
                append(writer.toString().lines().take(30).joinToString("\n"))
            }
        }.ifBlank { null }
        val entry = ErrorEntry(System.currentTimeMillis(), kind, message, detail)
        synchronized(lock) {
            val updated = (_entries.value + entry).takeLast(MAX)
            _entries.value = updated
            runCatching {
                if (updated.size == MAX) {
                    Paths.errors.writeText(updated.joinToString("\n", postfix = "\n") { json.encodeToString(ErrorEntry.serializer(), it) })
                } else {
                    Paths.errors.appendText(json.encodeToString(ErrorEntry.serializer(), entry) + "\n")
                }
            }
        }
    }

    fun clear() = synchronized(lock) {
        _entries.value = emptyList()
        runCatching { Paths.errors.delete() }
    }

    /** Texto para pegar en un mensaje (sin datos personales: no hay cuentas ni claves). */
    fun report(): String {
        val format = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.forLanguageTag("es"))
        return buildString {
            appendLine("Lyra para Windows ${BuildInfo.VERSION} · ${System.getProperty("os.name")} ${System.getProperty("os.version")} · Java ${System.getProperty("java.version")}")
            appendLine()
            entries.value.reversed().forEach { entry ->
                appendLine("[${format.format(Date(entry.time))}] ${entry.kind}: ${entry.message}")
                entry.detail?.let { appendLine(it.prependIndent("    ")) }
            }
        }
    }
}
