package com.lyra.desktop.update

import com.lyra.desktop.BuildInfo
import com.lyra.desktop.ErrorLog
import com.lyra.desktop.Paths
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

sealed interface UpdateState {
    data object None : UpdateState
    data class Available(val version: String, val notes: String) : UpdateState
    data class Downloading(val version: String, val progress: Float) : UpdateState
    data class Ready(val version: String, val notes: String, val file: File) : UpdateState
    data class Failed(val message: String) : UpdateState
}

/**
 * Se actualiza sola desde GitHub, como el móvil: mira si hay versión nueva (al abrir y cada
 * media hora), baja el instalador por detrás y, al pulsar «Reiniciar para actualizar», lo pone
 * y vuelve a abrir Lyra. Sin permisos de administrador (se instala solo para tu usuario).
 */
class Updater(private val http: OkHttpClient, private val scope: CoroutineScope, private val enabled: () -> Boolean) {

    @Serializable
    private data class Asset(val name: String, val browser_download_url: String, val size: Long = 0)

    @Serializable
    private data class Release(val tag_name: String, val body: String? = null, val draft: Boolean = false, val prerelease: Boolean = false, val assets: List<Asset> = emptyList())

    private val json = Json { ignoreUnknownKeys = true }
    private val _state = MutableStateFlow<UpdateState>(UpdateState.None)
    val state: StateFlow<UpdateState> = _state.asStateFlow()
    private val lock = Mutex()
    private var etag: String? = null
    private var cached: Release? = null

    /** Desde el código (sin instalar) no se actualiza: no hay instalador al que volver. */
    val supported: Boolean = System.getProperty("lyra.dev") != "1" && launcherPath() != null

    fun start() {
        if (!supported) return
        scope.launch {
            delay(4_000)
            while (true) {
                if (enabled()) runCatching { check() }
                delay(30 * 60_000L)
            }
        }
    }

    /** Las notas de una versión ya publicada (para «Novedades» después de actualizar). */
    suspend fun notesFor(version: String): String? = withContext(Dispatchers.IO) {
        runCatching {
            val request = Request.Builder()
                .url("https://api.github.com/repos/${BuildInfo.UPDATE_REPO}/releases/tags/v$version")
                .header("Accept", "application/vnd.github+json")
                .header("User-Agent", "Lyra-Windows/${BuildInfo.VERSION}")
                .build()
            http.newCall(request).execute().use { response ->
                if (!response.isSuccessful) return@use null
                cleanNotes(json.decodeFromString(Release.serializer(), response.body.string()).body).ifBlank { null }
            }
        }.getOrNull()
    }

    /** Mira ahora mismo si hay versión nueva. Devuelve la versión encontrada (o null). */
    suspend fun check(): String? = lock.withLock {
        val release = latest() ?: return@withLock null
        val version = release.tag_name.removePrefix("v")
        if (!isNewer(version, BuildInfo.VERSION)) {
            _state.value = UpdateState.None
            return@withLock null
        }
        val current = _state.value
        if (current is UpdateState.Ready && current.version == version) return@withLock version
        val asset = release.assets.firstOrNull { it.name.endsWith("-Windows.msi", ignoreCase = true) } ?: return@withLock null
        val notes = cleanNotes(release.body)
        _state.value = UpdateState.Available(version, notes)
        download(version, notes, asset)
        version
    }

    private suspend fun latest(): Release? = withContext(Dispatchers.IO) {
        val builder = Request.Builder()
            .url("https://api.github.com/repos/${BuildInfo.UPDATE_REPO}/releases/latest")
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lyra-Windows/${BuildInfo.VERSION}")
        etag?.let { builder.header("If-None-Match", it) }
        http.newCall(builder.build()).execute().use { response ->
            // 304: no ha cambiado (y no gasta del límite de GitHub).
            if (response.code == 304) return@withContext cached
            if (!response.isSuccessful) return@withContext null
            etag = response.header("ETag")
            json.decodeFromString(Release.serializer(), response.body.string()).takeIf { !it.draft && !it.prerelease }.also { cached = it }
        }
    }

    private suspend fun download(version: String, notes: String, asset: Asset) = withContext(Dispatchers.IO) {
        Paths.updates.mkdirs()
        val target = File(Paths.updates, asset.name)
        if (target.isFile && (asset.size <= 0 || target.length() == asset.size)) {
            _state.value = UpdateState.Ready(version, notes, target)
            return@withContext
        }
        Paths.updates.listFiles()?.forEach { it.delete() }
        _state.value = UpdateState.Downloading(version, 0f)
        try {
            val partial = File(target.path + ".part")
            http.newCall(Request.Builder().url(asset.browser_download_url).build()).execute().use { response ->
                if (!response.isSuccessful) throw IOException("GitHub respondió ${response.code}")
                val total = response.body.contentLength().takeIf { it > 0 } ?: asset.size
                partial.outputStream().use { out ->
                    val input = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var done = 0L
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        out.write(buffer, 0, n)
                        done += n
                        if (total > 0) _state.value = UpdateState.Downloading(version, done.toFloat() / total)
                    }
                }
            }
            if (!partial.renameTo(target)) throw IOException("No se pudo guardar el instalador")
            _state.value = UpdateState.Ready(version, notes, target)
        } catch (e: Exception) {
            ErrorLog.record("Actualizar", e.message ?: "No se pudo bajar la actualización", e)
            _state.value = UpdateState.Failed(e.message ?: "No se pudo bajar la actualización")
        }
    }

    /**
     * Instala lo bajado y vuelve a abrir Lyra. [beforeExit] guarda todo; luego la app se cierra
     * para que el instalador pueda cambiar los archivos.
     */
    fun install(beforeExit: () -> Unit = {}, exit: () -> Unit = { kotlin.system.exitProcess(0) }) {
        val ready = _state.value as? UpdateState.Ready ?: run {
            scope.launch { check() }
            return
        }
        val launcher = launcherPath() ?: return
        beforeExit()
        // Espera a que Lyra se cierre, instala (barra de progreso pequeña, sin preguntas) y la abre.
        val script = "ping -n 3 127.0.0.1 >nul & msiexec /i \"${ready.file.absolutePath}\" /passive /norestart & start \"\" \"$launcher\""
        runCatching {
            ProcessBuilder("cmd.exe", "/c", script).redirectErrorStream(true).start()
        }.onFailure {
            ErrorLog.record("Actualizar", "No se pudo abrir el instalador", it)
            return
        }
        exit()
    }

    companion object {
        /** El Lyra.exe instalado (null si se está ejecutando desde el código). */
        fun launcherPath(): String? = ProcessHandle.current().info().command().orElse(null)
            ?.takeIf { it.endsWith("Lyra.exe", ignoreCase = true) }

        fun isNewer(candidate: String, current: String): Boolean {
            val a = candidate.split('.').map { it.toIntOrNull() ?: 0 }
            val b = current.split('.').map { it.toIntOrNull() ?: 0 }
            for (i in 0 until maxOf(a.size, b.size)) {
                val x = a.getOrElse(i) { 0 }
                val y = b.getOrElse(i) { 0 }
                if (x != y) return x > y
            }
            return false
        }

        /** Las notas de la versión, sin la guía de instalación del final. */
        fun cleanNotes(body: String?): String = body.orEmpty().substringBefore("<!-- instalar -->").trim()
    }
}
