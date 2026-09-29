package com.lyra.music.ui.share

import android.content.ContentValues
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import android.graphics.Typeface
import android.media.MediaScannerConnection
import android.os.Build
import android.os.Environment
import android.provider.MediaStore
import android.text.StaticLayout
import android.text.TextPaint
import android.text.TextUtils
import androidx.core.content.res.ResourcesCompat
import androidx.core.graphics.withTranslation
import coil3.SingletonImageLoader
import coil3.request.ImageRequest
import coil3.request.SuccessResult
import coil3.request.allowHardware
import coil3.toBitmap
import com.lyra.music.R
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Tarjeta para compartir una canción en formato historia (1080×1920): la portada
 * grande sobre su versión difuminada, el título en Instrument Serif y el artista
 * en Outfit, como en Lira'.
 */
object ShareCard {
    private const val W = 1080
    private const val H = 1920
    private const val MARGIN = 120f

    private const val BG = 0xFF0A0A0B.toInt()
    private const val SURFACE_HIGHER = 0xFF26262A.toInt()
    private const val FG = 0xFFF3F3F1.toInt()
    private const val SOFT = 0xFFC9C8C2.toInt()
    private const val MUTED = 0xFF9C9C96.toInt()

    class Card(val file: File, val bitmap: Bitmap)

    /** Dibuja la tarjeta y la guarda en caché (JPEG) para compartirla. */
    suspend fun create(context: Context, song: Song, cover: Any?): Card {
        val art = cover?.let { loadArt(context, it) }
        val bitmap = withContext(Dispatchers.Default) { render(context, song, art) }
        val file = withContext(Dispatchers.IO) {
            val dir = File(context.cacheDir, "share").apply { mkdirs() }
            // Las de hace más de una hora ya no hacen falta.
            dir.listFiles()?.filter { System.currentTimeMillis() - it.lastModified() > 3_600_000 }?.forEach { it.delete() }
            File(dir, "lyra-${fileSafe(song.title)}.jpg").also { out ->
                out.outputStream().use { bitmap.compress(Bitmap.CompressFormat.JPEG, 94, it) }
            }
        }
        return Card(file, bitmap)
    }

    /** Copia la tarjeta a Imágenes/Lyra para verla en la galería. */
    suspend fun saveToGallery(context: Context, card: Card): Boolean = withContext(Dispatchers.IO) {
        runCatching {
            val name = card.file.name
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val resolver = context.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.Images.Media.DISPLAY_NAME, name)
                    put(MediaStore.Images.Media.MIME_TYPE, "image/jpeg")
                    put(MediaStore.Images.Media.RELATIVE_PATH, Environment.DIRECTORY_PICTURES + "/Lyra")
                    put(MediaStore.Images.Media.IS_PENDING, 1)
                }
                val uri = resolver.insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values) ?: error("sin acceso a la galería")
                resolver.openOutputStream(uri)?.use { out -> card.file.inputStream().use { it.copyTo(out) } }
                resolver.update(uri, ContentValues().apply { put(MediaStore.Images.Media.IS_PENDING, 0) }, null, null)
            } else {
                @Suppress("DEPRECATION")
                val dir = File(Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES), "Lyra").apply { mkdirs() }
                val target = File(dir, name)
                card.file.copyTo(target, overwrite = true)
                MediaScannerConnection.scanFile(context, arrayOf(target.absolutePath), arrayOf("image/jpeg"), null)
            }
            true
        }.getOrDefault(false)
    }

    private suspend fun loadArt(context: Context, model: Any): Bitmap? {
        val request = ImageRequest.Builder(context).data(model).size(1080).allowHardware(false).build()
        return (SingletonImageLoader.get(context).execute(request) as? SuccessResult)?.image?.toBitmap()
    }

    fun render(context: Context, song: Song, art: Bitmap?): Bitmap {
        val out = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(out)
        val serif = ResourcesCompat.getFont(context, R.font.instrument_serif) ?: Typeface.SERIF
        val sans = ResourcesCompat.getFont(context, R.font.outfit) ?: Typeface.SANS_SERIF

        // Fondo: la portada muy difuminada y un velo oscuro para que se lea el texto.
        canvas.drawColor(BG)
        if (art != null) {
            canvas.drawBitmap(blurred(art), null, Rect(0, 0, W, H), Paint(Paint.FILTER_BITMAP_FLAG))
        }
        val veil = Paint().apply {
            shader = LinearGradient(
                0f, 0f, 0f, H.toFloat(),
                intArrayOf(0x8C0A0A0B.toInt(), 0xB80A0A0B.toInt(), 0xF20A0A0B.toInt()),
                floatArrayOf(0f, 0.55f, 1f),
                Shader.TileMode.CLAMP,
            )
        }
        canvas.drawRect(0f, 0f, W.toFloat(), H.toFloat(), veil)

        // Etiqueta de arriba.
        val eyebrow = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = sans
            textSize = 30f
            color = MUTED
            letterSpacing = 0.2f
            fontVariationSettings = "'wght' 500"
        }
        canvas.drawText("ESCUCHANDO EN LYRA", MARGIN, 232f, eyebrow)

        // Portada con sombra suave y borde fino.
        val size = W - 2 * MARGIN
        val top = 300f
        val rect = RectF(MARGIN, top, MARGIN + size, top + size)
        val radius = 44f
        val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            color = BG
            setShadowLayer(80f, 0f, 36f, 0xA6000000.toInt())
        }
        canvas.drawRoundRect(rect, radius, radius, shadow)
        val clip = Path().apply { addRoundRect(rect, radius, radius, Path.Direction.CW) }
        canvas.save()
        canvas.clipPath(clip)
        if (art != null) {
            canvas.drawBitmap(art, centerCrop(art, 1f), rect, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        } else {
            canvas.drawColor(SURFACE_HIGHER)
            val note = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
                typeface = serif
                textSize = 320f
                color = MUTED
                textAlign = Paint.Align.CENTER
            }
            canvas.drawText("♪", rect.centerX(), rect.centerY() + 110f, note)
        }
        canvas.restore()
        canvas.drawRoundRect(
            rect, radius, radius,
            Paint(Paint.ANTI_ALIAS_FLAG).apply {
                style = Paint.Style.STROKE
                strokeWidth = 2f
                color = 0x1FF3F3F1
            },
        )

        // Título (hasta dos líneas) y artista.
        val titlePaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 104f
            color = FG
        }
        val title = StaticLayout.Builder.obtain(song.title, 0, song.title.length, titlePaint, size.toInt())
            .setMaxLines(2)
            .setEllipsize(TextUtils.TruncateAt.END)
            .setLineSpacing(0f, 0.92f)
            .setIncludePad(false)
            .build()
        val titleTop = top + size + 96f
        canvas.withTranslation(MARGIN, titleTop) { title.draw(this) }

        val artistPaint = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = sans
            textSize = 48f
            color = SOFT
            fontVariationSettings = "'wght' 400"
        }
        val artist = TextUtils.ellipsize(song.artistsText, artistPaint, size, TextUtils.TruncateAt.END)
        canvas.drawText(artist, 0, artist.length, MARGIN, titleTop + title.height + 74f, artistPaint)

        // Pie: la marca y de dónde sale la canción.
        val brand = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = serif
            textSize = 68f
            color = FG
        }
        canvas.drawText("Lyra", MARGIN, H - 150f, brand)
        val source = TextPaint(Paint.ANTI_ALIAS_FLAG).apply {
            typeface = sans
            textSize = 28f
            color = MUTED
            letterSpacing = 0.16f
            textAlign = Paint.Align.RIGHT
            fontVariationSettings = "'wght' 500"
        }
        val from = if (song.source == Source.SOUNDCLOUD) "SOUNDCLOUD" else "YOUTUBE MUSIC"
        canvas.drawText(from, W - MARGIN, H - 164f, source)
        return out
    }

    /** Rectángulo centrado de [bitmap] con la proporción [aspect] (ancho / alto). */
    private fun centerCrop(bitmap: Bitmap, aspect: Float): Rect {
        val w = bitmap.width
        val h = bitmap.height
        return if (w.toFloat() / h > aspect) {
            val cw = (h * aspect).toInt()
            Rect((w - cw) / 2, 0, (w - cw) / 2 + cw, h)
        } else {
            val ch = (w / aspect).toInt()
            Rect(0, (h - ch) / 2, w, (h - ch) / 2 + ch)
        }
    }

    /** Portada reducida a 54×96 y difuminada; al ampliarla queda un fondo suave. */
    private fun blurred(art: Bitmap): Bitmap {
        val small = Bitmap.createBitmap(54, 96, Bitmap.Config.ARGB_8888)
        Canvas(small).drawBitmap(art, centerCrop(art, 54f / 96f), Rect(0, 0, 54, 96), Paint(Paint.FILTER_BITMAP_FLAG))
        val w = small.width
        val h = small.height
        val pixels = IntArray(w * h)
        val buffer = IntArray(w * h)
        small.getPixels(pixels, 0, w, 0, 0, w, h)
        repeat(3) {
            boxBlur(pixels, buffer, w, h, radius = 5, horizontal = true)
            boxBlur(buffer, pixels, w, h, radius = 5, horizontal = false)
        }
        small.setPixels(pixels, 0, w, 0, 0, w, h)
        return small
    }

    private fun boxBlur(src: IntArray, dst: IntArray, w: Int, h: Int, radius: Int, horizontal: Boolean) {
        val lines = if (horizontal) h else w
        val length = if (horizontal) w else h
        for (line in 0 until lines) {
            for (i in 0 until length) {
                var r = 0
                var g = 0
                var b = 0
                for (k in -radius..radius) {
                    val j = (i + k).coerceIn(0, length - 1)
                    val p = if (horizontal) src[line * w + j] else src[j * w + line]
                    r += (p shr 16) and 0xFF
                    g += (p shr 8) and 0xFF
                    b += p and 0xFF
                }
                val n = 2 * radius + 1
                val index = if (horizontal) line * w + i else i * w + line
                dst[index] = (0xFF shl 24) or ((r / n) shl 16) or ((g / n) shl 8) or (b / n)
            }
        }
    }

    private fun fileSafe(text: String) =
        text.replace(Regex("[^\\p{L}\\p{N} _-]"), "").trim().replace(Regex("\\s+"), "-").take(40).ifEmpty { "cancion" }
}
