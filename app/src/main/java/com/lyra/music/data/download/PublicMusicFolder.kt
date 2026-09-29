package com.lyra.music.data.download

import android.Manifest
import android.content.ContentValues
import android.content.Context
import android.content.pm.PackageManager
import android.media.MediaScannerConnection
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import com.lyra.music.data.model.Song
import java.io.File
import java.io.InputStream

/**
 * La carpeta visible `Música/Lyra`: ahí van las descargas con nombre legible
 * ("Artista - Canción.m4a") y las listas `.m3u8`, para verlas en el gestor de
 * archivos, en otras apps de música y al conectar el móvil al PC.
 *
 * En Android 10+ se escribe con MediaStore (no hace falta ningún permiso).
 * En Android 8-9 se escribe directamente, con el permiso de almacenamiento.
 */
class PublicMusicFolder(private val context: Context) {

    private val resolver get() = context.contentResolver

    val displayPath = "Música/Lyra"

    /** ¿Se puede escribir ahora mismo en la carpeta pública? */
    val available: Boolean
        get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q ||
            ContextCompat.checkSelfPermission(context, Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED

    /** Copia [source] a la carpeta y devuelve su ubicación (URI `content://` o ruta). */
    fun save(source: File, song: Song, extension: String, mimeType: String): String {
        val name = fileNameFor(song, extension)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val values = ContentValues().apply {
                put(MediaStore.Audio.Media.DISPLAY_NAME, name)
                put(MediaStore.Audio.Media.MIME_TYPE, mimeType)
                put(MediaStore.Audio.Media.RELATIVE_PATH, "${Environment.DIRECTORY_MUSIC}/Lyra/")
                put(MediaStore.Audio.Media.TITLE, song.title)
                put(MediaStore.Audio.Media.ARTIST, song.artistsText)
                song.album?.title?.let { put(MediaStore.Audio.Media.ALBUM, it) }
                put(MediaStore.Audio.Media.IS_PENDING, 1)
            }
            val collection = MediaStore.Audio.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = resolver.insert(collection, values) ?: error("No se pudo crear el archivo en $displayPath")
            try {
                resolver.openOutputStream(uri, "w")?.use { out -> source.inputStream().use { it.copyTo(out) } }
                    ?: error("No se pudo escribir en $displayPath")
                resolver.update(uri, ContentValues().apply { put(MediaStore.Audio.Media.IS_PENDING, 0) }, null, null)
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            uri.toString()
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Lyra").apply { mkdirs() }
            val target = uniqueFile(dir, name)
            source.copyTo(target, overwrite = false)
            MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf(mimeType), null)
            target.absolutePath
        }
    }

    fun delete(location: String) {
        if (location.startsWith("content://")) {
            runCatching { resolver.delete(Uri.parse(location), null, null) }
        } else {
            File(location).delete()
        }
    }

    fun open(location: String): InputStream? =
        if (location.startsWith("content://")) runCatching { resolver.openInputStream(Uri.parse(location)) }.getOrNull()
        else File(location).takeIf { it.exists() }?.inputStream()

    /** Nombre con el que quedó el archivo (MediaStore añade "(1)" si ya existía). */
    fun displayName(location: String): String? {
        if (!location.startsWith("content://")) return File(location).name
        return runCatching {
            resolver.query(Uri.parse(location), arrayOf(MediaStore.MediaColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) c.getString(0) else null
            }
        }.getOrNull()
    }

    fun exists(location: String): Boolean = open(location)?.use { true } ?: false

    /**
     * Escribe (o reescribe) `Música/Lyra/Playlists/<nombre>.m3u8` con las
     * canciones indicadas; las rutas son relativas para que funcione también en el PC.
     */
    fun writePlaylist(title: String, entries: List<Pair<Song, String>>) {
        if (entries.isEmpty()) return
        val content = buildString {
            append("#EXTM3U\n#PLAYLIST:").append(title).append('\n')
            entries.forEach { (song, fileName) ->
                append("#EXTINF:").append((song.durationMs ?: 0) / 1000).append(',')
                    .append(song.artistsText).append(" - ").append(song.title).append('\n')
                append("../").append(fileName).append('\n')
            }
        }.toByteArray(Charsets.UTF_8)
        val name = sanitize(title).ifEmpty { "Playlist" } + ".m3u8"
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            val relative = "${Environment.DIRECTORY_MUSIC}/Lyra/Playlists/"
            val files = MediaStore.Files.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val existing = resolver.query(
                files,
                arrayOf(MediaStore.MediaColumns._ID),
                "${MediaStore.MediaColumns.RELATIVE_PATH}=? AND ${MediaStore.MediaColumns.DISPLAY_NAME}=?",
                arrayOf(relative, name),
                null,
            )?.use { c -> if (c.moveToFirst()) Uri.withAppendedPath(files, c.getLong(0).toString()) else null }
            val uri = existing ?: resolver.insert(
                files,
                ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                    put(MediaStore.MediaColumns.MIME_TYPE, "audio/x-mpegurl")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, relative)
                },
            ) ?: return
            resolver.openOutputStream(uri, "wt")?.use { it.write(content) }
        } else {
            @Suppress("DEPRECATION")
            val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_MUSIC), "Lyra/Playlists").apply { mkdirs() }
            File(dir, name).writeBytes(content)
        }
    }

    private fun uniqueFile(dir: File, name: String): File {
        var file = File(dir, name)
        var n = 1
        while (file.exists()) {
            file = File(dir, name.substringBeforeLast('.') + " ($n)." + name.substringAfterLast('.'))
            n++
        }
        return file
    }

    companion object {
        private val FORBIDDEN = Regex("[\\\\/:*?\"<>|\\p{Cntrl}]")

        fun sanitize(text: String): String = text.replace(FORBIDDEN, " ").replace(Regex("\\s+"), " ").trim().take(120)

        /** "Artista - Canción.m4a" (con como mucho dos artistas). */
        fun fileNameFor(song: Song, extension: String): String {
            val artists = song.artists.take(2).joinToString(", ") { it.name }.ifBlank { "Desconocido" }
            return sanitize("$artists - ${song.title}") + "." + extension
        }
    }
}
