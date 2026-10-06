package com.lyra.music.playback

import android.content.ContentProvider
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.net.Uri
import android.os.Build
import android.os.ParcelFileDescriptor
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Download
import androidx.compose.material.icons.rounded.Explore
import androidx.compose.material.icons.rounded.Favorite
import androidx.compose.material.icons.rounded.Folder
import androidx.compose.material.icons.rounded.History
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Radio
import androidx.compose.material.icons.rounded.Shuffle
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.asAndroidPath
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.VectorGroup
import androidx.compose.ui.graphics.vector.VectorPath
import androidx.compose.ui.graphics.vector.toPath
import androidx.core.content.res.ResourcesCompat
import com.lyra.music.LyraApp
import com.lyra.music.R
import com.lyra.music.data.repo.COVERS_DIR
import okhttp3.Request
import java.io.File
import java.security.MessageDigest

/**
 * Android Auto solo carga carátulas desde `content://`. Este proveedor sirve:
 *  - `f`: carátulas descargadas y portadas propias de playlists (solo de esas carpetas),
 *  - `a`: carátulas de internet (solo de los servidores de imágenes de YouTube y SoundCloud),
 *  - `i`: iconos de Lyra (pestañas del coche, Me gusta, Descargas, Aleatorio…),
 *  - `m`: tarjetas de color con el nombre de un género o estado de ánimo (Explorar).
 */
class ArtworkProvider : ContentProvider() {

    override fun onCreate() = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor? {
        val context = context ?: return null
        val file = when (uri.path?.trim('/')) {
            "f" -> localFile(context, uri.getQueryParameter("f") ?: return null)
            "i" -> icon(context, uri.getQueryParameter("n") ?: return null)
            "m" -> moodTile(context, uri.getQueryParameter("t")?.take(80) ?: return null, uri.getQueryParameter("c")?.toIntOrNull())
            else -> remoteFile(context, uri.getQueryParameter("u") ?: return null)
        } ?: return null
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    private fun localFile(context: Context, path: String): File? {
        val container = (context.applicationContext as LyraApp).container
        val file = File(path)
        val allowed = listOf(container.downloads.coversDirectory, File(context.filesDir, COVERS_DIR))
            .any { file.canonicalPath.startsWith(it.canonicalPath + File.separator) }
        return file.takeIf { allowed && it.exists() }
    }

    private fun remoteFile(context: Context, remote: String): File? {
        val host = Uri.parse(remote).host.orEmpty()
        if (ALLOWED_HOSTS.none { host.endsWith(it) }) return null
        val dir = File(context.cacheDir, "artwork").apply { mkdirs() }
        // Las de vídeo se guardan ya recortadas (nombre aparte para no servir las antiguas).
        val file = File(dir, sha1(remote) + if (host.endsWith("ytimg.com")) "-sq.jpg" else ".jpg")
        if (!file.exists()) {
            val container = (context.applicationContext as LyraApp).container
            val temp = File(dir, file.name + ".part" + System.nanoTime())
            container.http.newCall(Request.Builder().url(remote).build()).execute().use { response ->
                if (!response.isSuccessful) return null
                temp.outputStream().use { response.body.byteStream().copyTo(it) }
            }
            if (host.endsWith("ytimg.com")) runCatching { squareCrop(temp) }
            if (!temp.renameTo(file)) temp.delete()
        }
        return file.takeIf { it.exists() }
    }

    /**
     * Las miniaturas de vídeo de YouTube son apaisadas (y las 4:3 traen bandas negras):
     * en el coche se ven mejor recortadas en cuadrado por el centro, como una carátula.
     */
    private fun squareCrop(file: File) {
        val source = BitmapFactory.decodeFile(file.path) ?: return
        val (w, h) = source.width to source.height
        if (w == h) return source.recycle()
        // En las 4:3 el vídeo ocupa la franja 16:9 del medio.
        val contentHeight = if (kotlin.math.abs(w * 3 - h * 4) <= 4) w * 9 / 16 else h
        val side = minOf(w, contentHeight)
        val square = Bitmap.createBitmap(source, (w - side) / 2, (h - side) / 2, side, side)
        file.outputStream().use { square.compress(Bitmap.CompressFormat.JPEG, 92, it) }
        if (square !== source) square.recycle()
        source.recycle()
    }

    // ------------------------------------------------------------ iconos y tarjetas

    private fun icon(context: Context, name: String): File? {
        val spec = ICONS[name] ?: return null
        return cached(context, "icon-$name") {
            if (spec.tile) tile(spec) else glyph(spec.vector, TAB_SIZE, android.graphics.Color.WHITE, TAB_SIZE * 0.8f)
        }
    }

    /** Portada cuadrada con degradado y el icono en el centro (como "Canciones que te gustan" en Spotify). */
    private fun tile(spec: IconSpec): Bitmap {
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, TILE_SIZE.toFloat(), TILE_SIZE.toFloat(), spec.from, spec.to, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, TILE_SIZE.toFloat(), TILE_SIZE.toFloat(), paint)
        val glyphSize = TILE_SIZE * 0.42f
        val offset = (TILE_SIZE - glyphSize) / 2f
        canvas.save()
        canvas.translate(offset, offset)
        drawVector(canvas, spec.vector, glyphSize, spec.glyph)
        canvas.restore()
        return bitmap
    }

    /** Icono blanco sobre transparente (las pestañas: el coche lo tiñe a su gusto). */
    private fun glyph(vector: ImageVector, size: Int, color: Int, glyphSize: Float): Bitmap {
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val offset = (size - glyphSize) / 2f
        canvas.translate(offset, offset)
        drawVector(canvas, vector, glyphSize, color)
        return bitmap
    }

    private fun drawVector(canvas: Canvas, vector: ImageVector, size: Float, color: Int) {
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color; style = Paint.Style.FILL }
        canvas.save()
        canvas.scale(size / vector.viewportWidth, size / vector.viewportHeight)
        fun draw(group: VectorGroup) {
            group.forEach { node ->
                when (node) {
                    is VectorGroup -> draw(node)
                    is VectorPath -> {
                        val path = node.pathData.toPath().asAndroidPath()
                        path.fillType = if (node.pathFillType == PathFillType.EvenOdd) {
                            android.graphics.Path.FillType.EVEN_ODD
                        } else {
                            android.graphics.Path.FillType.WINDING
                        }
                        canvas.drawPath(path, paint)
                    }
                }
            }
        }
        draw(vector.root)
        canvas.restore()
    }

    /** Tarjeta de color con el nombre del género, como las de "Explorar todo" de Spotify. */
    private fun moodTile(context: Context, title: String, colorIndex: Int?): File? = cached(context, "mood-" + sha1("$title|$colorIndex").take(16)) {
        val bitmap = Bitmap.createBitmap(TILE_SIZE, TILE_SIZE, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val (from, to) = MOOD_COLORS[Math.floorMod(colorIndex ?: title.lowercase().hashCode(), MOOD_COLORS.size)]
        val background = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            shader = LinearGradient(0f, 0f, TILE_SIZE.toFloat(), TILE_SIZE.toFloat(), from, to, Shader.TileMode.CLAMP)
        }
        canvas.drawRect(0f, 0f, TILE_SIZE.toFloat(), TILE_SIZE.toFloat(), background)
        // Un disco a medio asomar en la esquina, como las portadas inclinadas de Spotify.
        val disc = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x26F3F3F1 }
        canvas.drawOval(RectF(TILE_SIZE * 0.52f, TILE_SIZE * 0.52f, TILE_SIZE * 1.18f, TILE_SIZE * 1.18f), disc)
        canvas.drawOval(
            RectF(TILE_SIZE * 0.78f, TILE_SIZE * 0.78f, TILE_SIZE * 0.92f, TILE_SIZE * 0.92f),
            Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x33141416 },
        )
        val typeface = runCatching { ResourcesCompat.getFont(context, R.font.outfit) }.getOrNull() ?: Typeface.DEFAULT
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = 0xFFF3F3F1.toInt()
            this.typeface = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) Typeface.create(typeface, 700, false) else Typeface.create(typeface, Typeface.BOLD)
            textSize = TILE_SIZE * 0.13f
        }
        val padding = TILE_SIZE * 0.09f
        var y = padding - text.ascent()
        wrap(title, text, TILE_SIZE - padding * 2).take(3).forEach { line ->
            canvas.drawText(line, padding, y, text)
            y += text.fontSpacing
        }
        bitmap
    }

    private fun wrap(title: String, paint: Paint, width: Float): List<String> {
        val lines = mutableListOf<String>()
        var line = ""
        title.split(' ').filter { it.isNotBlank() }.forEach { word ->
            val candidate = if (line.isEmpty()) word else "$line $word"
            if (paint.measureText(candidate) <= width || line.isEmpty()) {
                line = candidate
            } else {
                lines += line
                line = word
            }
        }
        if (line.isNotEmpty()) lines += line
        return lines
    }

    /** Dibuja una vez y lo guarda: las siguientes veces se sirve el PNG. */
    private fun cached(context: Context, key: String, render: () -> Bitmap): File? {
        val dir = File(context.cacheDir, "artwork_icons").apply { mkdirs() }
        val file = File(dir, "$key-v$ICON_VERSION.png")
        if (file.exists()) return file
        return synchronized(lock) {
            if (!file.exists()) {
                runCatching {
                    val bitmap = render()
                    val temp = File(dir, file.name + ".part")
                    temp.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
                    bitmap.recycle()
                    temp.renameTo(file)
                }
            }
            file.takeIf { it.exists() }
        }
    }

    override fun getType(uri: Uri) = when (uri.path?.trim('/')) {
        "i", "m" -> "image/png"
        else -> "image/jpeg"
    }

    override fun query(uri: Uri, p: Array<out String>?, s: String?, a: Array<out String>?, o: String?): Cursor? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, s: String?, a: Array<out String>?) = 0
    override fun update(uri: Uri, v: ContentValues?, s: String?, a: Array<out String>?) = 0

    private class IconSpec(val vector: ImageVector, val tile: Boolean, val from: Int = 0, val to: Int = 0, val glyph: Int = 0)

    companion object {
        private val ALLOWED_HOSTS = listOf("googleusercontent.com", "ytimg.com", "ggpht.com", "sndcdn.com")
        private val lock = Any()

        private const val TILE_SIZE = 320
        private const val TAB_SIZE = 96

        /** Súbelo si cambia el dibujo de los iconos, para no servir los antiguos. */
        private const val ICON_VERSION = 1

        private val ICONS: Map<String, IconSpec> by lazy {
            val dark = 0xFF26262A.toInt() to 0xFF141416.toInt()
            mapOf(
                "liked" to IconSpec(Icons.Rounded.Favorite, true, 0xFFC45C5C.toInt(), 0xFF5E2626.toInt(), 0xFFF3F3F1.toInt()),
                "downloads" to IconSpec(Icons.Rounded.Download, true, 0xFF3A3A40.toInt(), 0xFF141416.toInt(), 0xFFF3F3F1.toInt()),
                "shuffle" to IconSpec(Icons.Rounded.Shuffle, true, 0xFFE8E6DF.toInt(), 0xFF9C9C96.toInt(), 0xFF0A0A0B.toInt()),
                "radio" to IconSpec(Icons.Rounded.Radio, true, dark.first, dark.second, 0xFFE8E6DF.toInt()),
                "folder" to IconSpec(Icons.Rounded.Folder, true, dark.first, dark.second, 0xFF9C9C96.toInt()),
                "recent" to IconSpec(Icons.Rounded.History, true, dark.first, dark.second, 0xFFE8E6DF.toInt()),
                "playlist" to IconSpec(Icons.Rounded.MusicNote, true, dark.first, dark.second, 0xFF9C9C96.toInt()),
                "tab_home" to IconSpec(Icons.Rounded.Home, false),
                "tab_recent" to IconSpec(Icons.Rounded.History, false),
                "tab_library" to IconSpec(Icons.Rounded.LibraryMusic, false),
                "tab_explore" to IconSpec(Icons.Rounded.Explore, false),
            )
        }

        /** Degradados de las tarjetas de Explorar: tonos apagados que casan con el negro de Lyra. */
        private val MOOD_COLORS = listOf(
            0xFFC45C5C to 0xFF6E2A2A,
            0xFF5C7CC4 to 0xFF27365E,
            0xFF4E9C80 to 0xFF1F4A3B,
            0xFFC4955C to 0xFF6B4A22,
            0xFF8A5CC4 to 0xFF3F2763,
            0xFFC45C95 to 0xFF62254A,
            0xFF4F9AB5 to 0xFF1E4757,
            0xFF8C9455 to 0xFF434725,
        ).map { (a, b) -> a.toInt() to b.toInt() }

        private fun authority(context: Context) = context.packageName + ".artwork"

        private fun base(context: Context, path: String) = Uri.Builder().scheme("content").authority(authority(context)).path(path)

        fun remoteUri(context: Context, url: String): Uri = base(context, "a").appendQueryParameter("u", url).build()

        fun fileUri(context: Context, file: File): Uri = base(context, "f").appendQueryParameter("f", file.absolutePath).build()

        fun iconUri(context: Context, name: String): Uri = base(context, "i").appendQueryParameter("n", name).build()

        fun moodUri(context: Context, title: String, color: Int): Uri =
            base(context, "m").appendQueryParameter("t", title).appendQueryParameter("c", color.toString()).build()

        private fun sha1(text: String): String =
            MessageDigest.getInstance("SHA-1").digest(text.toByteArray()).joinToString("") { "%02x".format(it) }
    }
}
