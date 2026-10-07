package com.lyra.desktop.audio

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.Closeable
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.TreeMap
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.locks.ReentrantLock
import kotlin.concurrent.thread
import kotlin.concurrent.withLock

/** Algo de lo que leer audio por posiciones: un archivo o una canción que se está bajando. */
interface AudioInput : Closeable {
    /** Tamaño total en bytes, si se sabe. */
    val length: Long?

    /** Lee hasta [len] bytes desde [position]; -1 al final. Si aún no han llegado, espera. */
    fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int
}

class FileInput(file: File) : AudioInput {
    private val raf = RandomAccessFile(file, "r")
    override val length: Long = raf.length()

    @Synchronized
    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (position >= length) return -1
        raf.seek(position)
        return raf.read(buffer, offset, len)
    }

    override fun close() = raf.close()
}

/** Dirección del audio de una canción (caduca: se puede pedir otra con refresh = true). */
data class RemoteStream(val url: String, val contentLength: Long?)

/** La URL de YouTube ha caducado o no vale: hay que pedir otra. */
class StaleUrlException(code: Int) : IOException("El servidor respondió $code")

/**
 * Caché del audio escuchado, por id de canción (como en el móvil): lo ya oído vuelve a sonar sin
 * red y al instante. Cuando pasa del tamaño elegido se borra lo que hace más tiempo que no suena.
 */
class AudioCache(private val dir: File, private val http: OkHttpClient, private val limitBytes: () -> Long) {
    private val open = ConcurrentHashMap<String, CachedAudio>()

    init {
        dir.mkdirs()
    }

    fun keyFor(songId: String): String = songId.replace(Regex("[^A-Za-z0-9._-]"), "_")

    /** El archivo entero, si ya se bajó del todo. */
    fun completeFile(songId: String): File? =
        File(dir, keyFor(songId) + ".audio").takeIf { it.isFile && it.length() > 0 }

    fun isComplete(songId: String): Boolean = completeFile(songId) != null

    /** Para reproducir: el archivo entero o una lectura que se va bajando. */
    fun open(songId: String, resolve: (refresh: Boolean) -> RemoteStream): AudioInput {
        completeFile(songId)?.let { file ->
            file.setLastModified(System.currentTimeMillis())
            return FileInput(file)
        }
        val key = keyFor(songId)
        open[key]?.let { existing -> if (existing.acquire()) return existing }
        val created = CachedAudio(dir, key, http, resolve) { done ->
            open.remove(key, done)
            if (done.isComplete) trim()
        }
        created.acquire()
        open[key] = created
        return created
    }

    /** Quita una canción de la caché (p. ej. porque su audio no se podía leer). */
    fun remove(songId: String) {
        val key = keyFor(songId)
        if (open.containsKey(key)) return
        File(dir, "$key.audio").delete()
        File(dir, "$key.part").delete()
        File(dir, "$key.ranges").delete()
    }

    fun sizeBytes(): Long = dir.listFiles()?.sumOf { it.length() } ?: 0L

    fun clear() {
        dir.listFiles()?.forEach { file ->
            val key = file.name.substringBeforeLast('.')
            if (!open.containsKey(key)) file.delete()
        }
    }

    /** Borra lo más antiguo hasta quedar por debajo del límite. */
    fun trim() {
        val limit = limitBytes()
        val files = dir.listFiles()?.filter { it.name.endsWith(".audio") || it.name.endsWith(".part") } ?: return
        var total = files.sumOf { it.length() }
        if (total <= limit) return
        for (file in files.sortedBy { it.lastModified() }) {
            if (total <= limit * 0.9) break
            val key = file.name.substringBeforeLast('.')
            if (open.containsKey(key)) continue
            total -= file.length()
            file.delete()
            File(dir, "$key.ranges").delete()
        }
    }
}

/**
 * Una canción que se baja a disco mientras suena, por trozos con Range (como en el móvil, porque
 * YouTube frena las descargas de golpe). Se puede leer desde cualquier punto: si ese trozo aún no
 * ha llegado, se pide primero. Si se corta a medias, lo bajado se aprovecha la próxima vez.
 */
class CachedAudio(
    private val dir: File,
    private val key: String,
    private val http: OkHttpClient,
    private val resolve: (refresh: Boolean) -> RemoteStream,
    private val onReleased: (CachedAudio) -> Unit,
) : AudioInput {

    @Serializable
    private data class SavedRanges(val total: Long? = null, val ranges: List<List<Long>> = emptyList())

    private val partFile = File(dir, "$key.part")
    private val rangesFile = File(dir, "$key.ranges")
    private val lock = ReentrantLock()
    private val changed = lock.newCondition()
    private val raf: RandomAccessFile
    private val ranges = TreeMap<Long, Long>() // inicio → fin (exclusivo)

    @Volatile private var total: Long? = null
    @Volatile private var failure: IOException? = null
    @Volatile private var closed = false
    @Volatile var isComplete = false
        private set
    private var users = 0
    private var wanted = 0L
    private var fetchStart = -1L
    private var fetchEnd = -1L
    @Volatile private var restart = false
    private var reachedEnd = false
    private var url: RemoteStream? = null
    private var worker: Thread? = null

    init {
        if (partFile.exists() && rangesFile.exists()) {
            runCatching { json.decodeFromString(SavedRanges.serializer(), rangesFile.readText()) }.getOrNull()?.let { saved ->
                total = saved.total
                saved.ranges.forEach { if (it.size == 2 && it[1] > it[0]) addRange(it[0], it[1]) }
            }
        } else {
            partFile.delete()
        }
        raf = RandomAccessFile(partFile, "rw")
        partFile.setLastModified(System.currentTimeMillis())
    }

    override val length: Long? get() = total

    fun acquire(): Boolean = lock.withLock {
        if (closed) return false
        users++
        if (worker == null) {
            worker = thread(name = "lyra-descarga-$key", isDaemon = true) { download() }
        }
        true
    }

    override fun read(position: Long, buffer: ByteArray, offset: Int, len: Int): Int {
        if (len == 0) return 0
        val available: Int
        lock.withLock {
            while (true) {
                if (closed) throw IOException("Lectura cerrada")
                val end = total
                if (end != null && position >= end) return -1
                val contiguous = contiguousFrom(position)
                if (contiguous > 0) {
                    available = minOf(len.toLong(), contiguous).toInt()
                    break
                }
                failure?.let { throw it }
                if (end == null && reachedEnd && position >= (ranges.lastEntry()?.value ?: 0L)) return -1
                // Si lo que se está bajando no llega a esta posición, se salta allí.
                if (position < fetchStart || position >= fetchEnd) restart = true
                wanted = position
                changed.signalAll()
                changed.await(250, TimeUnit.MILLISECONDS)
            }
        }
        synchronized(raf) {
            raf.seek(position)
            return raf.read(buffer, offset, available)
        }
    }

    /** Bytes seguidos ya bajados desde [position]. */
    private fun contiguousFrom(position: Long): Long {
        val entry = ranges.floorEntry(position) ?: return 0
        return if (entry.value > position) entry.value - position else 0
    }

    private fun addRange(start: Long, end: Long) {
        var s = start
        var e = end
        ranges.floorEntry(s)?.let { if (it.value >= s) { s = it.key; e = maxOf(e, it.value) } }
        while (true) {
            val next = ranges.ceilingEntry(s) ?: break
            if (next.key > e) break
            e = maxOf(e, next.value)
            ranges.remove(next.key)
        }
        ranges[s] = e
    }

    /** Primer byte que falta a partir de [from] (o null si de ahí al final está todo). */
    private fun firstMissing(from: Long): Long? {
        var position = from
        while (true) {
            val entry = ranges.floorEntry(position)
            if (entry != null && entry.value > position) position = entry.value else break
        }
        val end = total
        return if (end != null && position >= end) null else position
    }

    private fun download() {
        var failures = 0
        while (!closed) {
            val start: Long
            val end: Long
            lock.withLock {
                restart = false
                val from = firstMissing(wanted) ?: firstMissing(0)
                if (from == null || (total == null && reachedEnd)) {
                    finish()
                    return
                }
                start = from
                val nextKnown = ranges.ceilingEntry(start)?.key
                end = listOfNotNull(start + CHUNK_BYTES, nextKnown, total).min()
                fetchStart = start
                fetchEnd = end
            }
            try {
                fetch(start, end)
                failures = 0
            } catch (e: StaleUrlException) {
                url = null
                needFreshUrl = true
                failures++
                if (failures > 4) fail(e)
            } catch (e: IOException) {
                if (closed) return
                failures++
                if (failures > 5) {
                    fail(e)
                    return
                }
                Thread.sleep(400L * failures)
            } catch (e: Exception) {
                fail(IOException(e.message ?: "No se pudo bajar el audio", e))
                return
            }
            lock.withLock { if (failure != null) return }
        }
    }

    private fun fetch(start: Long, end: Long) {
        val stream = url ?: resolve(needFreshUrl).also {
            url = it
            needFreshUrl = false
            if (total == null && it.contentLength != null && it.contentLength > 0) total = it.contentLength
        }
        val request = Request.Builder()
            .url(stream.url)
            .header("Range", "bytes=$start-${end - 1}")
            .header("User-Agent", com.lyra.music.data.source.soundcloud.NewPipeDownloader.USER_AGENT)
            .build()
        http.newCall(request).execute().use { response ->
            if (response.code == 403 || response.code == 410 || response.code == 404) throw StaleUrlException(response.code)
            if (response.code == 416) {
                lock.withLock {
                    if (total == null) total = ranges.lastEntry()?.value ?: start
                    reachedEnd = true
                    changed.signalAll()
                }
                return
            }
            if (!response.isSuccessful) throw IOException("El servidor de audio respondió ${response.code}")
            val partial = response.code == 206
            response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { if (total == null) total = it }
            if (!partial) {
                // Sin Range: llega el archivo entero desde el principio.
                response.body.contentLength().takeIf { it > 0 }?.let { if (total == null) total = it }
            }
            var position = if (partial) start else 0L
            val stopAt = if (partial) end else Long.MAX_VALUE
            val input = response.body.byteStream()
            val buffer = ByteArray(64 * 1024)
            var sinceSignal = 0
            while (position < stopAt) {
                if (closed || restart) break
                val n = input.read(buffer, 0, minOf(buffer.size.toLong(), stopAt - position).toInt())
                if (n < 0) {
                    if (!partial || total == null) lock.withLock { reachedEnd = true }
                    break
                }
                synchronized(raf) {
                    raf.seek(position)
                    raf.write(buffer, 0, n)
                }
                lock.withLock {
                    addRange(position, position + n)
                    position += n
                    sinceSignal += n
                    if (sinceSignal >= 16 * 1024) {
                        sinceSignal = 0
                        changed.signalAll()
                    }
                }
            }
            lock.withLock {
                if (!partial && total == null) total = position
                changed.signalAll()
            }
            saveRanges()
        }
    }

    private var needFreshUrl = false

    private fun saveRanges() {
        val snapshot = lock.withLock { SavedRanges(total, ranges.map { listOf(it.key, it.value) }) }
        runCatching { rangesFile.writeText(json.encodeToString(SavedRanges.serializer(), snapshot)) }
    }

    /** Todo bajado: pasa a ser un archivo normal de la caché. */
    private fun finish() {
        isComplete = true
        changed.signalAll()
    }

    private fun fail(e: IOException) {
        lock.withLock {
            failure = e
            changed.signalAll()
        }
    }

    override fun close() {
        val last = lock.withLock {
            users--
            if (users > 0) return
            closed = true
            changed.signalAll()
            true
        }
        if (!last) return
        // Sin esperar al hilo de descarga: si está a medias, ve que se ha cerrado y se para solo.
        synchronized(raf) { runCatching { raf.close() } }
        if (isComplete && total != null && partFile.length() >= total!!) {
            val target = File(dir, "$key.audio")
            target.delete()
            if (partFile.renameTo(target)) rangesFile.delete()
        } else {
            saveRanges()
        }
        onReleased(this)
    }

    private companion object {
        const val CHUNK_BYTES = 4L * 1024 * 1024
        val json = Json { ignoreUnknownKeys = true }
    }
}
