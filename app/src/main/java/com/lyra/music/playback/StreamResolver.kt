package com.lyra.music.playback

import android.net.Uri
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
    private val locks = ConcurrentHashMap<String, Mutex>()

    suspend fun resolve(songId: String, quality: AudioQuality = settings.current.streamQuality): Resolved {
        val lock = locks.getOrPut(songId) { Mutex() }
        return lock.withLock {
            memo[songId]?.takeIf { it.expiresAt > System.currentTimeMillis() + 60_000 }?.let { return@withLock it }
            val streams = newPipe.audioStreams(songId)
            val chosen = NewPipeSource.pick(streams, quality) ?: throw IOException("Esta canción no tiene audio disponible")
            val resolved = if (chosen.isHls) {
                val file = File(cacheDir, "hls/${songId.replace(Regex("[^A-Za-z0-9._-]"), "_")}.${chosen.extension}")
                if (!file.exists() || file.length() == 0L) HlsFetcher(http).download(chosen.url, file) { }
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
    suspend fun freshStream(songId: String, quality: AudioQuality, portable: Boolean = false): AudioStreamInfo {
        val streams = newPipe.audioStreams(songId)
        return NewPipeSource.pick(streams, quality, portable) ?: throw IOException("Esta canción no tiene audio disponible")
    }

    fun invalidate(songId: String) {
        memo.remove(songId)
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

    private fun expiryOf(url: String): Long {
        val expire = Regex("[?&]expire=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        return if (expire != null) expire * 1000 - 5 * 60_000 else System.currentTimeMillis() + 15 * 60_000
    }
}

/** Baja una lista HLS (SoundCloud) y junta los segmentos en un único archivo reproducible. */
class HlsFetcher(private val http: OkHttpClient) {

    suspend fun download(playlistUrl: String, target: File, onProgress: (Float) -> Unit) = withContext(Dispatchers.IO) {
        var url = playlistUrl
        var text = fetchText(url)
        if (text.contains("#EXT-X-STREAM-INF")) {
            val variant = text.lines().firstOrNull { it.isNotBlank() && !it.startsWith("#") }
                ?: throw IOException("Lista HLS sin variantes")
            url = resolve(url, variant.trim())
            text = fetchText(url)
        }
        if (Regex("#EXT-X-KEY:METHOD=(?!NONE)").containsMatchIn(text)) {
            throw IOException("Esta pista está protegida y no se puede descargar")
        }
        val init = Regex("#EXT-X-MAP:URI=\"([^\"]+)\"").find(text)?.groupValues?.get(1)
        val segments = text.lines().map { it.trim() }.filter { it.isNotEmpty() && !it.startsWith("#") }
        if (segments.isEmpty()) throw IOException("Lista HLS vacía")

        target.parentFile?.mkdirs()
        val partial = File(target.path + ".part")
        partial.outputStream().buffered().use { out ->
            if (init != null) out.write(fetchBytes(resolve(url, init)))
            segments.forEachIndexed { index, segment ->
                out.write(fetchBytes(resolve(url, segment)))
                onProgress((index + 1f) / segments.size)
            }
        }
        if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
    }

    private fun resolve(base: String, relative: String): String = java.net.URI(base).resolve(relative).toString()

    private fun fetchText(url: String): String =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HLS respondió ${response.code}")
            response.body.string()
        }

    private fun fetchBytes(url: String): ByteArray =
        http.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("Segmento HLS respondió ${response.code}")
            response.body.bytes()
        }
}
