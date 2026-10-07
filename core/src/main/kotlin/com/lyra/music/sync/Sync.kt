package com.lyra.music.sync

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.share.PlaylistSharing
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.decodeFromJsonElement
import kotlinx.serialization.json.encodeToJsonElement
import kotlinx.serialization.json.jsonObject
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.IOException
import java.security.MessageDigest

/*
 * Biblioteca sincronizada entre el móvil y el PC (por el servidor de la web de Lyra).
 *
 * Cada cosa de la biblioteca es un "registro": un Me gusta, una playlist, una carpeta, un álbum
 * guardado o un artista seguido. Cada dispositivo recuerda cómo estaba cada registro la última vez
 * que sincronizó; lo que ha cambiado desde entonces se sube, y luego se baja lo que han cambiado los
 * demás. Si dos cambian lo mismo, gana el más reciente.
 */

object SyncTypes {
    const val LIKE = "like"
    const val PLAYLIST = "playlist"
    const val FOLDER = "folder"
    const val ALBUM = "album"
    const val ARTIST = "artist"
}

/** Un registro tal como viaja: tipo, clave, datos (null = borrado), hora del cambio y revisión. */
@Serializable
data class SyncRecord(val t: String, val k: String, val d: JsonObject? = null, val u: Long = 0, val r: Long = 0) {
    val id: String get() = "$t:$k"
}

@Serializable
data class SyncKey(val biblioteca: String, val token: String)

@Serializable
data class SyncCode(val codigo: String, val caduca: Long)

@Serializable
data class SyncPull(val rev: Long, val registros: List<SyncRecord>)

@Serializable
data class SyncPushResult(val rev: Long, val aceptados: Int)

// ---------------------------------------------------------------------- datos de cada tipo

@Serializable
data class LikeData(val song: Song, val at: Long)

@Serializable
data class PlaylistData(
    val name: String,
    val description: String? = null,
    val songs: List<Song> = emptyList(),
    val createdAt: Long = 0,
    val remoteId: String? = null,
    val coverUrl: String? = null,
    /** Clave de la carpeta en la que está (o null). */
    val folder: String? = null,
)

@Serializable
data class FolderData(val name: String, val createdAt: Long = 0)

@Serializable
data class AlbumData(val album: AlbumItem, val savedAt: Long)

@Serializable
data class ArtistData(val artist: ArtistItem, val followedAt: Long)

object SyncJson {
    val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = false
        explicitNulls = false
    }

    fun <T> encode(serializer: KSerializer<T>, value: T): JsonObject = json.encodeToJsonElement(serializer, value).jsonObject
    fun <T> decode(serializer: KSerializer<T>, data: JsonObject): T? = runCatching { json.decodeFromJsonElement(serializer, data) }.getOrNull()

    fun like(song: Song, at: Long) = SyncRecord(SyncTypes.LIKE, song.id, encode(LikeData.serializer(), LikeData(song, at)))
    fun playlist(key: String, data: PlaylistData) = SyncRecord(SyncTypes.PLAYLIST, key, encode(PlaylistData.serializer(), data))
    fun folder(key: String, data: FolderData) = SyncRecord(SyncTypes.FOLDER, key, encode(FolderData.serializer(), data))
    fun album(data: AlbumData) = SyncRecord(SyncTypes.ALBUM, data.album.id, encode(AlbumData.serializer(), data))
    fun artist(data: ArtistData) = SyncRecord(SyncTypes.ARTIST, data.artist.id, encode(ArtistData.serializer(), data))
}

// ---------------------------------------------------------------------- servidor

/** Lo que hace falta del servidor (en las pruebas se usa uno en memoria). */
interface SyncApi {
    suspend fun create(): SyncKey
    suspend fun pairCode(key: SyncKey): SyncCode
    suspend fun join(code: String): SyncKey
    suspend fun pull(key: SyncKey, since: Long): SyncPull
    suspend fun push(key: SyncKey, records: List<SyncRecord>): SyncPushResult
    suspend fun leave(key: SyncKey)
}

/** Este dispositivo ya no está emparejado (se le quitó desde otro o se borró la biblioteca). */
class SyncUnpairedException : IOException("Este dispositivo ya no está emparejado")

class HttpSyncApi(private val http: OkHttpClient, private val base: String = PlaylistSharing.BASE) : SyncApi {
    private val json = SyncJson.json

    /** Para lo que se manda: con "d": null explícito en los borrados (si no, el servidor no lo entiende). */
    private val wire = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        explicitNulls = true
    }
    private val type = "application/json".toMediaType()

    private suspend fun call(path: String, key: SyncKey?, body: String?): String = withContext(Dispatchers.IO) {
        val builder = Request.Builder().url("$base/api/sync/$path").header("X-Lyra", "1")
        if (key != null) {
            builder.header("X-Biblioteca", key.biblioteca)
            builder.header("Authorization", "Bearer ${key.token}")
        }
        if (body != null) builder.post(body.toRequestBody(type))
        http.newCall(builder.build()).execute().use { response ->
            val text = response.body.string()
            if (response.code == 401) throw SyncUnpairedException()
            if (!response.isSuccessful) {
                val message = runCatching { json.parseToJsonElement(text).jsonObject["error"]?.toString()?.trim('"') }.getOrNull()
                throw IOException(message ?: "El servidor respondió ${response.code}")
            }
            text
        }
    }

    override suspend fun create(): SyncKey = json.decodeFromString(SyncKey.serializer(), call("crear", null, "{}"))

    override suspend fun pairCode(key: SyncKey): SyncCode = json.decodeFromString(SyncCode.serializer(), call("codigo", key, "{}"))

    override suspend fun join(code: String): SyncKey {
        val body = json.encodeToString(JsonObject.serializer(), JsonObject(mapOf("codigo" to kotlinx.serialization.json.JsonPrimitive(code))))
        return json.decodeFromString(SyncKey.serializer(), call("unir", null, body))
    }

    override suspend fun pull(key: SyncKey, since: Long): SyncPull =
        json.decodeFromString(SyncPull.serializer(), call("cambios?desde=$since", key, null))

    override suspend fun push(key: SyncKey, records: List<SyncRecord>): SyncPushResult {
        val body = wire.encodeToString(PushBody.serializer(), PushBody(records))
        return json.decodeFromString(SyncPushResult.serializer(), call("cambios", key, body))
    }

    override suspend fun leave(key: SyncKey) {
        call("salir", key, "{}")
    }

    @Serializable
    private data class PushBody(val registros: List<SyncRecord>)
}

// ---------------------------------------------------------------------- el motor

/** La biblioteca de cada app, vista como registros. */
interface SyncStore {
    /** Todo lo que hay ahora en la biblioteca. */
    suspend fun snapshot(): List<SyncRecord>

    /** Aplica lo que llega de otros dispositivos (d == null: se borró). */
    suspend fun apply(changes: List<SyncRecord>)
}

/** Lo que recuerda cada dispositivo: hasta qué revisión ha bajado y cómo estaba cada registro. */
@Serializable
data class SyncState(val lastRev: Long = 0, val hashes: Map<String, String> = emptyMap())

data class SyncResult(val state: SyncState, val pushed: Int, val applied: Int)

class SyncEngine(private val api: SyncApi, private val store: SyncStore, private val clock: () -> Long = System::currentTimeMillis) {

    suspend fun sync(key: SyncKey, state: SyncState): SyncResult {
        val local = store.snapshot().associateBy { it.id }
        val now = clock()

        // 1. Subir lo cambiado aquí desde la última vez (y lo borrado).
        val changes = mutableListOf<SyncRecord>()
        for ((id, record) in local) {
            if (state.hashes[id] != essence(record)) changes += record.copy(u = now, r = 0)
        }
        for (id in state.hashes.keys - local.keys) {
            changes += SyncRecord(id.substringBefore(':'), id.substringAfter(':'), null, now)
        }
        changes.chunked(PUSH_CHUNK).forEach { api.push(key, it) }

        // 2. Bajar lo que ha cambiado (incluidos los cambios propios, que se ignoran solos).
        val pulled = api.pull(key, state.lastRev)
        val toApply = pulled.registros.filter { remote ->
            val mine = local[remote.id]
            if (remote.d == null) mine != null else mine == null || essence(mine) != essence(remote)
        }
        // Lo propio recién subido no se vuelve a aplicar (llega igual que está).
        val pushedIds = changes.associateBy { it.id }
        val effective = toApply.filterNot { remote ->
            val sent = pushedIds[remote.id] ?: return@filterNot false
            (sent.d == null && remote.d == null) || (sent.d != null && remote.d != null && essence(sent) == essence(remote))
        }
        if (effective.isNotEmpty()) store.apply(effective)

        // 3. Recordar cómo queda cada registro (lo que se subió y lo que se bajó).
        val hashes = state.hashes.toMutableMap()
        for (record in changes) if (record.d == null) hashes.remove(record.id) else hashes[record.id] = essence(record)
        for (record in pulled.registros) if (record.d == null) hashes.remove(record.id) else hashes[record.id] = essence(record)
        return SyncResult(SyncState(pulled.rev, hashes), changes.size, effective.size)
    }

    companion object {
        private const val PUSH_CHUNK = 400

        /**
         * Lo que importa de cada registro (para saber si ha cambiado): el nombre y las canciones de
         * una playlist, que exista un Me gusta… No cuentan las carátulas ni otros datos que cada
         * app puede guardar un poco distinto (así no se suben cambios que no son cambios).
         */
        fun essence(record: SyncRecord): String {
            val d = record.d ?: return "-"
            val text = when (record.t) {
                SyncTypes.PLAYLIST -> SyncJson.decode(PlaylistData.serializer(), d)?.let { p ->
                    listOf(p.name, p.description.orEmpty(), p.folder.orEmpty(), p.remoteId.orEmpty(), p.coverUrl.orEmpty())
                        .joinToString("\u0001") + "\u0002" + p.songs.joinToString("\u0001") { it.id }
                } ?: d.toString()
                SyncTypes.FOLDER -> SyncJson.decode(FolderData.serializer(), d)?.name ?: d.toString()
                else -> "+"
            }
            val digest = MessageDigest.getInstance("SHA-1").digest(text.toByteArray(Charsets.UTF_8))
            return digest.joinToString("") { "%02x".format(it) }
        }
    }
}

/** «K7P2MX» → «K7P-2MX» (más fácil de leer y de copiar). */
fun formatPairCode(code: String): String = if (code.length == 6) code.substring(0, 3) + "-" + code.substring(3) else code
