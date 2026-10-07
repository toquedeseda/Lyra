package com.lyra.desktop.data

import com.lyra.desktop.ErrorLog
import com.lyra.desktop.Paths
import com.lyra.desktop.audio.StreamResolver
import com.lyra.music.data.download.AudioTags
import com.lyra.music.data.download.Id3Tagger
import com.lyra.music.data.download.Mp4Tagger
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.soundcloud.NewPipeDownloader
import com.lyra.music.playback.HlsFetcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import okhttp3.OkHttpClient
import okhttp3.Request
import java.io.File
import java.io.IOException
import java.io.RandomAccessFile
import java.util.concurrent.ConcurrentHashMap
import kotlin.coroutines.coroutineContext

enum class DownloadStatus { QUEUED, DOWNLOADING, DONE, FAILED }

@Serializable
data class DownloadEntry(
    val song: Song,
    val status: DownloadStatus,
    val file: String? = null,
    val progress: Float = 0f,
    val error: String? = null,
    val addedAt: Long = System.currentTimeMillis(),
)

/**
 * Descargas en el PC, como en el móvil: «Artista - Canción.m4a» en Música\Lyra, con título,
 * artista, álbum y carátula dentro, para que se vean bien en cualquier reproductor.
 */
@OptIn(FlowPreview::class)
class Downloads(
    private val stateFile: File,
    private val resolver: StreamResolver,
    private val http: OkHttpClient,
    private val settings: SettingsStore,
    private val scope: CoroutineScope,
) {
    private val json = Json { ignoreUnknownKeys = true; coerceInputValues = true }
    private val state = MutableStateFlow(load())
    val entries: StateFlow<Map<String, DownloadEntry>> = state.asStateFlow()
    private val running = ConcurrentHashMap<String, Job>()
    private val slots = Semaphore(2)

    init {
        scope.launch(Dispatchers.IO) { state.drop(1).debounce(800).collect { save(it) } }
        // Lo que se quedó a medias al cerrar, sigue.
        state.value.values.filter { it.status == DownloadStatus.QUEUED || it.status == DownloadStatus.DOWNLOADING }
            .forEach { enqueue(it.song) }
    }

    private fun load(): Map<String, DownloadEntry> = runCatching {
        json.decodeFromString<Map<String, DownloadEntry>>(stateFile.readText())
    }.getOrDefault(emptyMap())

    @Synchronized
    private fun save(entries: Map<String, DownloadEntry>) {
        runCatching { stateFile.writeText(json.encodeToString(entries)) }
    }

    fun saveNow() = save(state.value)

    /** El archivo descargado de esta canción, si sigue en su sitio. */
    fun localFile(songId: String): File? {
        val entry = state.value[songId] ?: return null
        if (entry.status != DownloadStatus.DONE) return null
        return entry.file?.let(::File)?.takeIf { it.isFile && it.length() > 0 }
    }

    fun isDownloaded(songId: String) = localFile(songId) != null

    fun download(songs: List<Song>) {
        songs.distinctBy { it.id }.forEach { song ->
            val existing = state.value[song.id]
            if (existing?.status == DownloadStatus.DONE && localFile(song.id) != null) return@forEach
            if (existing?.status == DownloadStatus.QUEUED || existing?.status == DownloadStatus.DOWNLOADING) return@forEach
            state.update { it + (song.id to DownloadEntry(song, DownloadStatus.QUEUED)) }
            enqueue(song)
        }
    }

    fun retry(songId: String) {
        val entry = state.value[songId] ?: return
        state.update { it + (songId to entry.copy(status = DownloadStatus.QUEUED, error = null, progress = 0f)) }
        enqueue(entry.song)
    }

    /** Quita la descarga y borra el archivo. */
    fun remove(songId: String) {
        running.remove(songId)?.cancel()
        val entry = state.value[songId]
        entry?.file?.let { runCatching { File(it).delete() } }
        state.update { it - songId }
    }

    private fun enqueue(song: Song) {
        running[song.id]?.let { if (it.isActive) return }
        running[song.id] = scope.launch(Dispatchers.IO) {
            slots.withPermit {
                runCatching { downloadOne(song) }.onFailure { e ->
                    if (!coroutineContext.isActive) return@onFailure
                    ErrorLog.record("Descargar", "«${song.title}»: ${e.message ?: "error"}", e, extra = song.id)
                    state.update { it + (song.id to (it[song.id] ?: DownloadEntry(song, DownloadStatus.FAILED)).copy(status = DownloadStatus.FAILED, error = e.message ?: "No se pudo descargar")) }
                }
            }
            running.remove(song.id)
        }
    }

    private suspend fun downloadOne(song: Song) {
        setStatus(song, DownloadStatus.DOWNLOADING, 0f)
        val folder = Paths.downloadsFolder(settings.current.downloadsFolder)
        // M4A o MP3: llevan la carátula dentro y se abren en cualquier sitio.
        val stream = resolver.freshStream(song.id, settings.current.downloadQuality, portable = true, hint = song)
        val partial = File(folder, ".lyra-" + song.id.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".part")
        if (stream.isHls) {
            HlsFetcher(http).download(stream.url, partial) { p -> setStatus(song, DownloadStatus.DOWNLOADING, p) }
        } else {
            fetch(stream.url, stream.contentLength, partial) { p -> setStatus(song, DownloadStatus.DOWNLOADING, p) }
        }
        val extension = when {
            stream.mimeType == "audio/mp4" || stream.extension == "m4a" -> "m4a"
            stream.mimeType == "audio/mpeg" || stream.extension == "mp3" -> "mp3"
            else -> stream.extension.ifBlank { "audio" }
        }
        val target = uniqueFile(folder, fileNameFor(song, extension), song.id)
        val tags = AudioTags(
            title = song.title,
            artist = song.artistsText.ifBlank { "Desconocido" },
            album = song.album?.title,
            albumArtist = song.artists.firstOrNull()?.name,
            cover = song.thumbnailUrl?.let(::coverBytes),
        )
        val tagged = when (extension) {
            "m4a" -> Mp4Tagger.tag(partial, target, tags)
            "mp3" -> Id3Tagger.tag(partial, target, tags)
            else -> false
        }
        if (tagged) partial.delete() else if (!partial.renameTo(target)) {
            partial.copyTo(target, overwrite = true)
            partial.delete()
        }
        state.update { it + (song.id to DownloadEntry(song, DownloadStatus.DONE, target.absolutePath, 1f, addedAt = it[song.id]?.addedAt ?: System.currentTimeMillis())) }
    }

    private fun setStatus(song: Song, status: DownloadStatus, progress: Float) {
        state.update { it + (song.id to (it[song.id] ?: DownloadEntry(song, status)).copy(status = status, progress = progress, error = null)) }
    }

    /** Baja por trozos de 4 MB con Range (YouTube frena las descargas de golpe). */
    private suspend fun fetch(url: String, knownLength: Long?, target: File, onProgress: (Float) -> Unit) {
        target.parentFile?.mkdirs()
        target.delete()
        RandomAccessFile(target, "rw").use { out ->
            var position = 0L
            var total = knownLength
            var lastReport = 0L
            while (total == null || position < total) {
                if (!coroutineContext.isActive) throw IOException("Cancelada")
                val end = position + CHUNK - 1
                val request = Request.Builder().url(url)
                    .header("Range", "bytes=$position-${if (total != null) minOf(end, total - 1) else end}")
                    .header("User-Agent", NewPipeDownloader.USER_AGENT)
                    .build()
                val done = http.newCall(request).execute().use { response ->
                    if (response.code == 416) return@use true
                    if (!response.isSuccessful) throw IOException("El servidor respondió ${response.code}")
                    response.header("Content-Range")?.substringAfter('/')?.toLongOrNull()?.let { total = it }
                    if (response.code == 200) {
                        position = 0
                        out.setLength(0)
                    }
                    val input = response.body.byteStream()
                    val buffer = ByteArray(64 * 1024)
                    var readAny = false
                    while (true) {
                        val n = input.read(buffer)
                        if (n < 0) break
                        readAny = true
                        out.seek(position)
                        out.write(buffer, 0, n)
                        position += n
                        val t = total
                        if (t != null && position - lastReport > 256 * 1024) {
                            lastReport = position
                            onProgress((position.toFloat() / t).coerceIn(0f, 0.99f))
                        }
                    }
                    response.code == 200 || !readAny
                }
                if (done) break
            }
        }
    }

    private fun coverBytes(url: String): ByteArray? = runCatching {
        // En JPEG (los reproductores no entienden WebP dentro del archivo).
        val request = Request.Builder().url(url).header("Accept", "image/jpeg,image/png").build()
        http.newCall(request).execute().use { response ->
            if (!response.isSuccessful) return null
            val bytes = response.body.bytes()
            val isJpeg = bytes.size > 3 && bytes[0] == 0xFF.toByte() && bytes[1] == 0xD8.toByte()
            if (isJpeg) bytes else null
        }
    }.getOrNull()

    private fun uniqueFile(folder: File, name: String, songId: String): File {
        var file = File(folder, name)
        var n = 2
        // Otra canción con el mismo nombre: «… (2).m4a».
        while (file.exists() && state.value.values.none { it.song.id == songId && it.file == file.absolutePath }) {
            file = File(folder, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'))
            n++
        }
        return file
    }

    companion object {
        private const val CHUNK = 4L * 1024 * 1024
        private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun sanitize(text: String): String = text.replace(FORBIDDEN, " ").replace(Regex("\\s+"), " ").trim().trimEnd('.').take(120)

        /** «Artista - Canción.m4a» (con como mucho dos artistas), como en el móvil. */
        fun fileNameFor(song: Song, extension: String): String {
            val artists = song.artists.take(2).joinToString(", ") { it.name }.ifBlank { "Desconocido" }
            return sanitize("$artists - ${song.title}") + "." + extension
        }
    }
}
