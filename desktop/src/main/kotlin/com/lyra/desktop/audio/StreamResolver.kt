package com.lyra.desktop.audio

import com.lyra.music.data.model.Song
import com.lyra.music.data.source.soundcloud.AudioQuality
import com.lyra.music.data.source.soundcloud.AudioStreamInfo
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.playback.AlternativeSources
import com.lyra.music.playback.HlsFetcher
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import okhttp3.OkHttpClient
import java.io.File
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap

/**
 * Traduce una canción (`yt:…` / `sc:…`) a algo que sonar, como en el móvil: la URL del audio
 * (que en YouTube caduca a las pocas horas) o, en SoundCloud con HLS, el archivo ya juntado.
 */
class StreamResolver(
    private val newPipe: NewPipeSource,
    private val alternatives: AlternativeSources,
    private val http: OkHttpClient,
    /** Dónde juntar lo que llega en trozos (HLS): la caché de lo escuchado, que tiene límite. */
    private val hlsFile: (songId: String) -> File,
    private val quality: () -> AudioQuality,
) {
    data class Resolved(
        val url: String?,
        val file: File?,
        val contentLength: Long?,
        val mimeType: String,
        val bitrate: Int,
        val expiresAt: Long,
    )

    private val memo = ConcurrentHashMap<String, Resolved>()
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** Canciones cuyo audio (WebM) no se pudo leer: se piden en M4A. */
    private val portableOnly = ConcurrentHashMap.newKeySet<String>()

    suspend fun resolve(songId: String, hint: Song? = null): Resolved {
        val lock = locks.getOrPut(songId) { Mutex() }
        return lock.withLock {
            // Lo juntado de SoundCloud vale mientras siga en la caché (que se vacía sola si se llena).
            memo[songId]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 && (it.file == null || it.file.isFile) }?.let { return@withLock it }
            val streams = alternatives.audioStreams(songId, hint)
            val chosen = NewPipeSource.pick(streams, quality(), portable = songId in portableOnly)
                ?: throw IOException("Esta canción no tiene audio disponible")
            val resolved = if (chosen.isHls) {
                val file = hlsFile(songId)
                if (!file.isFile || file.length() == 0L) HlsFetcher(http).download(chosen.url, file) { }
                file.setLastModified(System.currentTimeMillis())
                Resolved(null, file, file.length(), chosen.mimeType, chosen.bitrate, Long.MAX_VALUE)
            } else {
                chosen.toResolved()
            }
            memo[songId] = resolved
            resolved
        }
    }

    /** Lo mismo pero esperando (para los hilos de audio). */
    fun resolveBlocking(songId: String, hint: Song?): Resolved = runBlocking { resolve(songId, hint) }

    /** Para descargar: siempre una URL fresca, en la calidad pedida. */
    suspend fun freshStream(songId: String, quality: AudioQuality, portable: Boolean, hint: Song?): AudioStreamInfo {
        val streams = alternatives.audioStreams(songId, hint)
        return NewPipeSource.pick(streams, quality, portable) ?: throw IOException("Esta canción no tiene audio disponible")
    }

    fun invalidate(songId: String) {
        memo.remove(songId)
    }

    /** La próxima vez se pide en M4A. False si ya se había probado (para no repetir sin fin). */
    fun preferPortable(songId: String): Boolean {
        if (!portableOnly.add(songId)) return false
        memo.remove(songId)
        return true
    }

    private fun AudioStreamInfo.toResolved() = Resolved(
        url = url,
        file = null,
        contentLength = contentLength,
        mimeType = mimeType,
        bitrate = bitrate,
        expiresAt = expiryOf(url),
    )

    private fun expiryOf(url: String): Long {
        val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (expire != null) expire * 1000 - 5 * 60_000 else System.currentTimeMillis() + 15 * 60_000
    }
}
