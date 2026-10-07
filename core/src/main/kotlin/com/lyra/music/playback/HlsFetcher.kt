package com.lyra.music.playback

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException

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
        try {
            partial.outputStream().buffered().use { out ->
                if (init != null) out.write(fetchBytes(resolve(url, init)))
                segments.forEachIndexed { index, segment ->
                    out.write(fetchBytes(resolve(url, segment)))
                    onProgress((index + 1f) / segments.size)
                }
            }
        } catch (e: Throwable) {
            // Cortada a medias: no se deja el trozo (ni en la caché ni en la carpeta de descargas).
            partial.delete()
            throw e
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
