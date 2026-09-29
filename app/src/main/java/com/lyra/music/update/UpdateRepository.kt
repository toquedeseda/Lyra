package com.lyra.music.update

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import com.lyra.music.BuildConfig
import com.lyra.music.LyraApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

data class UpdateInfo(
    val version: String,
    val notes: String,
    val apkUrl: String,
    val apkSize: Long,
    val pageUrl: String,
)

sealed interface UpdateState {
    data object Idle : UpdateState
    data object Checking : UpdateState
    data object UpToDate : UpdateState
    data class Available(val info: UpdateInfo) : UpdateState
    data class Downloading(val info: UpdateInfo, val progress: Float) : UpdateState
    data class Installing(val info: UpdateInfo) : UpdateState
    data class NeedsPermission(val info: UpdateInfo) : UpdateState
    data class Failed(val reason: String, val info: UpdateInfo? = null) : UpdateState
}

/** Compara versiones tipo 1.2.10 (numéricamente, tramo a tramo). */
object VersionComparator {
    fun isNewer(current: String, other: String): Boolean = compare(other, current) > 0

    fun compare(a: String, b: String): Int {
        val pa = parts(a)
        val pb = parts(b)
        for (i in 0 until maxOf(pa.size, pb.size)) {
            val diff = pa.getOrElse(i) { 0 } - pb.getOrElse(i) { 0 }
            if (diff != 0) return diff
        }
        return 0
    }

    private fun parts(version: String): List<Int> =
        version.removePrefix("v").substringBefore('-').split('.').map { it.filter(Char::isDigit).toIntOrNull() ?: 0 }
}

/**
 * Actualizaciones desde las releases de GitHub (igual que IR Universal):
 * al abrir la app se mira la última release; si es más nueva, sale un aviso
 * con las novedades y un botón que la descarga e instala.
 */
class UpdateRepository(
    private val context: Context,
    private val http: OkHttpClient,
    private val scope: CoroutineScope,
) {
    @Serializable
    private data class Release(
        val tag_name: String = "",
        val name: String? = null,
        val body: String? = null,
        val html_url: String = "",
        val draft: Boolean = false,
        val prerelease: Boolean = false,
        val assets: List<Asset> = emptyList(),
    )

    @Serializable
    private data class Asset(val name: String = "", val browser_download_url: String = "", val size: Long = 0)

    private val json = Json { ignoreUnknownKeys = true }
    private val prefs = context.getSharedPreferences("updates", Context.MODE_PRIVATE)
    private val _state = MutableStateFlow<UpdateState>(UpdateState.Idle)
    val state: StateFlow<UpdateState> = _state.asStateFlow()

    val currentVersion: String get() = BuildConfig.VERSION_NAME.substringBefore("-")

    /** Sin [force], como mucho una consulta cada 12 h (el límite anónimo de GitHub es bajo). */
    fun check(force: Boolean = false) {
        if (_state.value is UpdateState.Checking || _state.value is UpdateState.Downloading) return
        val last = prefs.getLong("checked_at", 0)
        if (!force && System.currentTimeMillis() - last < 12 * 3_600_000L) {
            cachedAvailable()?.let { _state.value = UpdateState.Available(it) }
            return
        }
        _state.value = UpdateState.Checking
        scope.launch {
            _state.value = try {
                val release = fetch("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/latest")
                prefs.edit().putLong("checked_at", System.currentTimeMillis()).apply()
                val info = release.toInfo()
                if (info != null && VersionComparator.isNewer(currentVersion, info.version)) {
                    remember(info)
                    UpdateState.Available(info)
                } else {
                    forget()
                    UpdateState.UpToDate
                }
            } catch (e: Exception) {
                UpdateState.Failed(e.message ?: "No se pudo comprobar")
            }
        }
    }

    fun dismiss() {
        if (_state.value !is UpdateState.Downloading && _state.value !is UpdateState.Installing) {
            _state.value = UpdateState.Idle
        }
    }

    /** Notas de la versión instalada, para el "Novedades" tras actualizar. */
    suspend fun notesFor(version: String): String? = runCatching {
        fetch("https://api.github.com/repos/${BuildConfig.UPDATE_REPO}/releases/tags/v$version").body
    }.getOrNull()?.takeIf { it.isNotBlank() }

    fun downloadAndInstall(info: UpdateInfo) {
        if (!canInstall()) {
            _state.value = UpdateState.NeedsPermission(info)
            return
        }
        scope.launch {
            try {
                _state.value = UpdateState.Downloading(info, 0f)
                val file = download(info)
                _state.value = UpdateState.Installing(info)
                install(file)
            } catch (e: Exception) {
                _state.value = UpdateState.Failed(e.message ?: "Error al actualizar", info)
            }
        }
    }

    fun canInstall(): Boolean =
        Build.VERSION.SDK_INT < Build.VERSION_CODES.O || context.packageManager.canRequestPackageInstalls()

    fun permissionIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

    private suspend fun download(info: UpdateInfo): File = withContext(Dispatchers.IO) {
        val dir = File(context.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val file = File(dir, "Lyra-${info.version}.apk")
        http.newCall(Request.Builder().url(info.apkUrl).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("GitHub respondió ${response.code}")
            val total = response.body.contentLength().takeIf { it > 0 } ?: info.apkSize
            var done = 0L
            var lastEmit = 0L
            file.outputStream().use { out ->
                response.body.byteStream().use { input ->
                    val buffer = ByteArray(64 * 1024)
                    while (true) {
                        val read = input.read(buffer)
                        if (read == -1) break
                        out.write(buffer, 0, read)
                        done += read
                        val now = System.currentTimeMillis()
                        if (total > 0 && now - lastEmit > 150) {
                            lastEmit = now
                            _state.value = UpdateState.Downloading(info, done.toFloat() / total)
                        }
                    }
                }
            }
        }
        if (file.length() == 0L) throw IOException("El APK descargado está vacío")
        file
    }

    private suspend fun install(apk: File) = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(context.packageName)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                // Al actualizarse a sí misma, Android 12+ no pide confirmar otra vez.
                setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
            }
        }
        val sessionId = installer.createSession(params)
        installer.openSession(sessionId).use { session ->
            session.openWrite("lyra.apk", 0, apk.length()).use { out ->
                apk.inputStream().use { it.copyTo(out) }
                session.fsync(out)
            }
            val intent = Intent(context, InstallResultReceiver::class.java).setAction(InstallResultReceiver.ACTION)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
            val pending = PendingIntent.getBroadcast(context, sessionId, intent, flags)
            session.commit(pending.intentSender)
        }
    }

    internal fun onInstallResult(status: Int, message: String?) {
        val info = (_state.value as? UpdateState.Installing)?.info
        when (status) {
            PackageInstaller.STATUS_SUCCESS -> Unit // Android reinicia la app con la versión nueva.
            PackageInstaller.STATUS_PENDING_USER_ACTION -> Unit
            PackageInstaller.STATUS_FAILURE_ABORTED -> _state.value = info?.let { UpdateState.Available(it) } ?: UpdateState.Idle
            PackageInstaller.STATUS_FAILURE_CONFLICT, PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                _state.value = UpdateState.Failed(
                    "La versión nueva está firmada con otra clave. Desinstala Lyra e instala el APK a mano.", info,
                )
            PackageInstaller.STATUS_FAILURE_STORAGE -> _state.value = UpdateState.Failed("No hay espacio suficiente", info)
            else -> _state.value = UpdateState.Failed(message ?: "La instalación falló", info)
        }
    }

    private fun fetch(url: String): Release {
        val request = Request.Builder().url(url)
            .header("Accept", "application/vnd.github+json")
            .header("User-Agent", "Lyra-Updater")
            .build()
        http.newCall(request).execute().use { response ->
            when (response.code) {
                200 -> Unit
                403, 429 -> throw IOException("GitHub ha limitado las consultas, prueba más tarde")
                404 -> throw IOException("Todavía no hay versiones publicadas")
                else -> throw IOException("GitHub respondió ${response.code}")
            }
            return json.decodeFromString(Release.serializer(), response.body.string())
        }
    }

    private fun Release.toInfo(): UpdateInfo? {
        if (draft || prerelease) return null
        val apk = assets.firstOrNull { it.name.endsWith(".apk", ignoreCase = true) } ?: return null
        return UpdateInfo(tag_name.removePrefix("v"), body.orEmpty().trim(), apk.browser_download_url, apk.size, html_url)
    }

    private fun remember(info: UpdateInfo) {
        prefs.edit()
            .putString("version", info.version).putString("notes", info.notes)
            .putString("apk", info.apkUrl).putLong("size", info.apkSize).putString("page", info.pageUrl)
            .apply()
    }

    private fun forget() {
        prefs.edit().remove("version").apply()
    }

    private fun cachedAvailable(): UpdateInfo? {
        val version = prefs.getString("version", null) ?: return null
        // Se vuelve a comparar con la versión instalada AHORA (evita el aviso fantasma tras actualizar).
        if (!VersionComparator.isNewer(currentVersion, version)) return null
        return UpdateInfo(
            version,
            prefs.getString("notes", "").orEmpty(),
            prefs.getString("apk", null) ?: return null,
            prefs.getLong("size", 0),
            prefs.getString("page", "").orEmpty(),
        )
    }
}

/** Recibe el resultado del instalador del sistema. */
class InstallResultReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            @Suppress("DEPRECATION")
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)?.let(context::startActivity)
        }
        val message = intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE)
        (context.applicationContext as LyraApp).container.updates.onInstallResult(status, message)
    }

    companion object {
        const val ACTION = "com.lyra.music.INSTALL_RESULT"
    }
}
