package com.lyra.music.playback

import android.content.Context
import android.net.Uri
import androidx.media3.common.C
import androidx.media3.common.util.UnstableApi
import androidx.media3.datasource.DataSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.DefaultDataSource
import androidx.media3.datasource.FileDataSource
import androidx.media3.datasource.ResolvingDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.datasource.cache.Cache
import androidx.media3.datasource.cache.CacheDataSource
import androidx.media3.datasource.okhttp.OkHttpDataSource
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.source.soundcloud.NewPipeDownloader
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient

/**
 * Cadena de lectura del reproductor:
 *   descargada → archivo local
 *   si no      → caché de reproducción → (trozos de 4 MB) → URL real resuelta al vuelo
 * La clave de caché es el id de la canción, no la URL (que caduca), así que lo
 * ya escuchado vuelve a sonar sin red.
 */
@UnstableApi
class LyraDataSourceFactory(
    context: Context,
    http: OkHttpClient,
    private val resolver: StreamResolver,
    private val downloads: DownloadRepository,
    cache: Cache,
) : DataSource.Factory {

    private val network = DefaultDataSource.Factory(
        context,
        OkHttpDataSource.Factory(http).setUserAgent(NewPipeDownloader.USER_AGENT),
    )

    private val resolving = ResolvingDataSource.Factory(network) { spec ->
        val songId = MediaItems.songIdFrom(spec.uri) ?: return@Factory spec
        val resolved = runBlocking { resolver.resolve(songId) }
        spec.withUri(Uri.parse(resolved.uri))
    }

    private val chunked = DataSource.Factory { ChunkedDataSource(resolving.createDataSource(), CHUNK_BYTES) }

    private val cached = CacheDataSource.Factory()
        .setCache(cache)
        .setUpstreamDataSourceFactory(chunked)
        .setFlags(CacheDataSource.FLAG_IGNORE_CACHE_ON_ERROR)

    private val files = FileDataSource.Factory()

    override fun createDataSource(): DataSource = RoutingDataSource()

    private inner class RoutingDataSource : DataSource {
        private val listeners = mutableListOf<TransferListener>()
        private var current: DataSource? = null

        override fun addTransferListener(transferListener: TransferListener) {
            listeners += transferListener
        }

        override fun open(dataSpec: DataSpec): Long {
            val songId = MediaItems.songIdFrom(dataSpec.uri)
            val local = songId?.let(downloads::localFile)
            val (source, spec) = when {
                local != null -> files.createDataSource() to dataSpec.withUri(Uri.fromFile(local))
                songId != null -> cached.createDataSource() to dataSpec.buildUpon().setKey(dataSpec.key ?: songId).build()
                else -> network.createDataSource() to dataSpec
            }
            listeners.forEach(source::addTransferListener)
            current = source
            return source.open(spec)
        }

        override fun read(buffer: ByteArray, offset: Int, length: Int): Int =
            current?.read(buffer, offset, length) ?: C.RESULT_END_OF_INPUT

        override fun getUri(): Uri? = current?.uri

        override fun getResponseHeaders(): Map<String, List<String>> = current?.responseHeaders ?: emptyMap()

        override fun close() {
            try {
                current?.close()
            } finally {
                current = null
            }
        }
    }

    companion object {
        private const val CHUNK_BYTES = 4L * 1024 * 1024
    }
}

/**
 * Lee un archivo remoto pidiendo trozos consecutivos con Range, pero se lo presenta
 * al reproductor como un único flujo continuo.
 */
@UnstableApi
class ChunkedDataSource(
    private val upstream: DataSource,
    private val chunkSize: Long,
) : DataSource {

    private var spec: DataSpec? = null
    private var position = 0L
    private var remaining = C.LENGTH_UNSET.toLong()
    private var chunkShort = false
    private var opened = false
    private var total: Long? = null

    override fun addTransferListener(transferListener: TransferListener) = upstream.addTransferListener(transferListener)

    override fun open(dataSpec: DataSpec): Long {
        spec = dataSpec
        position = dataSpec.position
        remaining = dataSpec.length
        total = null
        val firstLength = openChunk()
        if (dataSpec.length != C.LENGTH_UNSET.toLong()) return dataSpec.length
        val total = this.total
        return when {
            total != null -> total - dataSpec.position
            chunkShort && firstLength != C.LENGTH_UNSET.toLong() -> firstLength
            else -> C.LENGTH_UNSET.toLong()
        }
    }

    private fun openChunk(): Long {
        val base = spec ?: error("sin abrir")
        val length = if (remaining == C.LENGTH_UNSET.toLong()) chunkSize else minOf(chunkSize, remaining)
        val chunk = base.buildUpon().setPosition(position).setLength(length).build()
        val result = upstream.open(chunk)
        opened = true
        chunkShort = result != C.LENGTH_UNSET.toLong() && result < length
        if (total == null) total = totalFromHeaders()
        return result
    }

    private fun totalFromHeaders(): Long? {
        val range = upstream.responseHeaders.entries
            .firstOrNull { it.key.equals("Content-Range", ignoreCase = true) }?.value?.firstOrNull()
            ?: return null
        return range.substringAfter('/').toLongOrNull()
    }

    override fun read(buffer: ByteArray, offset: Int, length: Int): Int {
        if (length == 0) return 0
        if (remaining == 0L) return C.RESULT_END_OF_INPUT
        var read = upstream.read(buffer, offset, length)
        if (read == C.RESULT_END_OF_INPUT) {
            val end = total
            if (chunkShort || (end != null && position >= end)) return C.RESULT_END_OF_INPUT
            upstream.close()
            opened = false
            openChunk()
            read = upstream.read(buffer, offset, length)
            if (read == C.RESULT_END_OF_INPUT) return read
        }
        position += read
        if (remaining != C.LENGTH_UNSET.toLong()) remaining -= read
        return read
    }

    override fun getUri(): Uri? = upstream.uri

    override fun getResponseHeaders(): Map<String, List<String>> = upstream.responseHeaders

    override fun close() {
        if (opened) {
            opened = false
            upstream.close()
        }
    }
}
