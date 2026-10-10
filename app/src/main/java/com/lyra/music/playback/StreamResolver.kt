package com.lyra.music.playback

import android.net.Uri
import com.lyra.music.data.model.Song
import com.lyra.music.data.settings.SettingsRepository
import com.lyra.music.data.source.soundcloud.AudioQuality
import com.lyra.music.data.source.soundcloud.AudioStreamInfo
import com.lyra.music.data.source.soundcloud.NewPipeSource
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Traduce una canción (`yt:…` / `sc:…`) a una URL de audio reproducible.
 * Las URLs de YouTube caducan a las pocas horas, así que se recuerdan hasta
 * entonces y se piden de nuevo cuando hace falta.
 */
class StreamResolver(
    private val newPipe: NewPipeSource,
    private val settings: SettingsRepository,
    private val http: OkHttpClient,
    private val cacheDir: File,
    private val alternatives: AlternativeSources,
) {
    data class Resolved(
        val uri: String,
        val mimeType: String,
        val extension: String,
        val bitrate: Int,
        val contentLength: Long?,
        val isLocalFile: Boolean,
        val expiresAt: Long,
    )

    private val memo = ConcurrentHashMap<String, Resolved>()

    /** Canciones cuyo audio de YouTube (WebM) no se pudo leer: se piden en el formato de siempre (M4A). */
    private val portableOnly = ConcurrentHashMap.newKeySet<String>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** [hint]: título y artista, para buscar otra versión si YouTube no deja la original. */
    suspend fun resolve(songId: String, quality: AudioQuality = settings.current.streamQuality, hint: Song? = null): Resolved {
        val lock = locks.getOrPut(songId) { Mutex() }
        return lock.withLock {
            // Lo juntado de SoundCloud vale mientras siga en la caché (se puede vaciar o borrar por viejo).
            memo[songId]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 && (!it.isLocalFile || localFileOf(it)?.isFile == true) }
                ?.let { cached ->
                    if (cached.isLocalFile) localFileOf(cached)?.setLastModified(System.currentTimeMillis())
                    return@withLock cached
                }
            // Lo de SoundCloud ya juntado suena sin preguntar a internet (también sin cobertura).
            localCopy(songId)?.let { copy ->
                memo[songId] = copy
                return@withLock copy
            }
            // Si YouTube no deja sacar ese vídeo, se usa otra versión de la misma canción.
            val streams = alternatives.audioStreams(songId, hint)
            val chosen = NewPipeSource.pick(streams, quality, portable = songId in portableOnly)
                ?: throw IOException("Esta canción no tiene audio disponible")
            val resolved = if (chosen.isHls) {
                val file = File(cacheDir, "hls/${safeName(songId)}.${chosen.extension}")
                if (!file.exists() || file.length() == 0L) HlsFetcher(http).download(chosen.url, file) { }
                file.setLastModified(System.currentTimeMillis())
                Resolved(Uri.fromFile(file).toString(), chosen.mimeType, chosen.extension, chosen.bitrate,
                    file.length(), isLocalFile = true, expiresAt = Long.MAX_VALUE)
            } else {
                chosen.toResolved()
            }
            memo[songId] = resolved
            resolved
        }
    }

    /** Para descargar se piden siempre las URLs frescas, con la calidad de descarga. */
    suspend fun freshStream(songId: String, quality: AudioQuality, portable: Boolean = false, hint: Song? = null): AudioStreamInfo {
        val streams = alternatives.audioStreams(songId, hint)
        return NewPipeSource.pick(streams, quality, portable) ?: throw IOException("Esta canción no tiene audio disponible")
    }

    fun invalidate(songId: String) {
        memo.remove(songId)
    }

    /**
     * Lo de SoundCloud que ya se juntó en la caché (`hls/`), si sigue ahí: suena sin internet.
     * Solo se mira el nombre de cada formato posible (rápido, sin listar la carpeta).
     */
    fun localCopy(songId: String): Resolved? {
        val name = safeName(songId)
        for ((extension, mimeType) in HLS_FORMATS) {
            val file = File(cacheDir, "hls/$name.$extension")
            if (file.isFile && file.length() > 0) {
                file.setLastModified(System.currentTimeMillis())
                return Resolved(Uri.fromFile(file).toString(), mimeType, extension, 0, file.length(), isLocalFile = true, expiresAt = Long.MAX_VALUE)
            }
        }
        return null
    }

    private fun safeName(songId: String) = songId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** El archivo de un [Resolved] local (lo juntado de SoundCloud). */
    private fun localFileOf(resolved: Resolved): File? = Uri.parse(resolved.uri).path?.let(::File)

    /** La próxima vez se pide en M4A. Devuelve false si ya se había probado (para no repetir sin fin). */
    fun preferPortable(songId: String): Boolean {
        if (!portableOnly.add(songId)) return false
        memo.remove(songId)
        return true
    }

    private fun AudioStreamInfo.toResolved() = Resolved(
        uri = url,
        mimeType = mimeType,
        extension = extension,
        bitrate = bitrate,
        contentLength = contentLength,
        isLocalFile = false,
        expiresAt = expiryOf(url),
    )

    private companion object {
        /** Los formatos en los que llega lo de SoundCloud por trozos (HLS). */
        val HLS_FORMATS = listOf(
            "mp3" to "audio/mpeg", "opus" to "audio/ogg", "ogg" to "audio/ogg", "m4a" to "audio/mp4",
            "aac" to "audio/aac", "webm" to "audio/webm", "audio" to "audio/*",
        )
    }

    private fun expiryOf(url: String): Long {
        val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (expire != null) expire * 1000 - 5 * 60_000 else System.currentTimeMillis() + 15 * 60_000
    }
}
