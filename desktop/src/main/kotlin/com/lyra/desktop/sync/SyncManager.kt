package com.lyra.desktop.sync

import com.lyra.desktop.ErrorLog
import com.lyra.desktop.data.Library
import com.lyra.desktop.data.SettingsStore
import com.lyra.desktop.data.SyncConfig
import com.lyra.music.sync.HttpSyncApi
import com.lyra.music.sync.SyncCode
import com.lyra.music.sync.SyncEngine
import com.lyra.music.sync.SyncKey
import com.lyra.music.sync.SyncRecord
import com.lyra.music.sync.SyncState
import com.lyra.music.sync.SyncStore
import com.lyra.music.sync.SyncUnpairedException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import java.io.File
import java.util.UUID

sealed interface SyncStatus {
    /** Sin emparejar. [message]: por qué (si se quitó desde otro dispositivo). */
    data class Off(val message: String? = null) : SyncStatus
    data object Syncing : SyncStatus
    data class Done(val at: Long) : SyncStatus
    data class Failed(val message: String, val lastAt: Long) : SyncStatus
}

/**
 * La biblioteca sincronizada con el móvil (por el servidor de la web de Lyra): al abrir, al cambiar
 * algo (a los pocos segundos) y cada dos minutos. Si no hay internet, lo intenta más tarde.
 */
@OptIn(FlowPreview::class)
class SyncManager(
    http: OkHttpClient,
    private val library: Library,
    private val settings: SettingsStore,
    private val stateFile: File,
    private val scope: CoroutineScope,
) {
    private val api = HttpSyncApi(http)
    private val json = Json { ignoreUnknownKeys = true }
    private val mutex = Mutex()
    private val _status = MutableStateFlow<SyncStatus>(
        settings.current.sync?.let { SyncStatus.Done(it.lastSyncAt) } ?: SyncStatus.Off(),
    )
    val status: StateFlow<SyncStatus> = _status.asStateFlow()

    /** Tras aplicar lo que llega, los cambios de la biblioteca no vuelven a lanzar otra sincronización. */
    @Volatile private var quietUntil = 0L

    private val store = object : SyncStore {
        override suspend fun snapshot(): List<SyncRecord> = library.syncSnapshot()
        override suspend fun apply(changes: List<SyncRecord>) {
            quietUntil = System.currentTimeMillis() + 5_000
            library.applySync(changes)
        }
    }
    private val engine = SyncEngine(api, store)

    val paired: Boolean get() = settings.current.sync != null

    fun start() {
        scope.launch {
            delay(3_000)
            syncNow()
        }
        scope.launch {
            library.data.drop(1).debounce(4_000).collect {
                if (paired && System.currentTimeMillis() > quietUntil) syncNow()
            }
        }
        scope.launch {
            while (isActive) {
                delay(120_000)
                if (paired) syncNow()
            }
        }
    }

    private fun key(): SyncKey? = settings.current.sync?.let { SyncKey(it.libraryId, it.token) }

    private fun loadState(): SyncState = runCatching { json.decodeFromString(SyncState.serializer(), stateFile.readText()) }.getOrDefault(SyncState())

    private fun saveState(state: SyncState) {
        runCatching { stateFile.writeText(json.encodeToString(SyncState.serializer(), state)) }
    }

    /** Sincroniza ya (si está emparejado). Devuelve false si no se pudo. */
    suspend fun syncNow(): Boolean = withContext(Dispatchers.IO) {
        val key = key() ?: return@withContext false
        mutex.withLock {
            _status.value = SyncStatus.Syncing
            try {
                val result = engine.sync(key, loadState())
                saveState(result.state)
                val now = System.currentTimeMillis()
                settings.update { s -> s.copy(sync = s.sync?.copy(lastRev = result.state.lastRev, lastSyncAt = now)) }
                _status.value = SyncStatus.Done(now)
                true
            } catch (e: CancellationException) {
                throw e
            } catch (e: SyncUnpairedException) {
                forget("Este PC se quitó de la sincronización desde otro dispositivo.")
                false
            } catch (e: Exception) {
                _status.value = SyncStatus.Failed(e.message ?: "Sin conexión", settings.current.sync?.lastSyncAt ?: 0L)
                false
            }
        }
    }

    /** Empieza la sincronización desde este PC y devuelve el código para el móvil. */
    suspend fun createLibrary(): SyncCode = withContext(Dispatchers.IO) {
        val key = api.create()
        remember(key)
        syncNow()
        api.pairCode(key)
    }

    /** Se une a la biblioteca del móvil con su código. */
    suspend fun join(code: String) = withContext(Dispatchers.IO) {
        val key = api.join(code.trim())
        remember(key)
        if (!syncNow()) throw IllegalStateException((status.value as? SyncStatus.Failed)?.message ?: "No se pudo sincronizar")
    }

    /** Otro código para emparejar otro dispositivo. */
    suspend fun newCode(): SyncCode = withContext(Dispatchers.IO) {
        val key = key() ?: throw IllegalStateException("No está emparejado")
        api.pairCode(key)
    }

    /** Deja de sincronizar en este PC (la biblioteca se queda como está). */
    suspend fun leave() = withContext(Dispatchers.IO) {
        val key = key()
        if (key != null) runCatching { api.leave(key) }.onFailure { ErrorLog.record("Sincronizar", "No se pudo avisar al servidor", it) }
        forget(null)
    }

    private fun remember(key: SyncKey) {
        saveState(SyncState())
        settings.update { it.copy(sync = SyncConfig(key.biblioteca, key.token, UUID.randomUUID().toString())) }
        settings.saveNow()
    }

    private fun forget(message: String?) {
        stateFile.delete()
        settings.update { it.copy(sync = null) }
        _status.value = SyncStatus.Off(message)
    }
}
