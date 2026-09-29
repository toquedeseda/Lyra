package com.lyra.music

import com.lyra.music.data.download.AudioTags
import com.lyra.music.data.download.Id3Tagger
import com.lyra.music.data.download.Mp4Tagger
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer

class TaggerTest {

    private val tags = AudioTags(
        title = "Canción de prueba",
        artist = "Artista Inventado",
        album = "Álbum de prueba",
        cover = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 1, 2, 3, 0xFF.toByte(), 0xD9.toByte()),
    )

    private fun box(type: String, vararg parts: ByteArray): ByteArray {
        val payload = ByteArrayOutputStream().apply { parts.forEach { write(it) } }.toByteArray()
        return ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type.toByteArray()).put(payload).array()
    }

    private fun int(v: Int) = ByteBuffer.allocate(4).putInt(v).array()
    private fun long(v: Long) = ByteBuffer.allocate(8).putLong(v).array()

    private fun indexOf(data: ByteArray, pattern: ByteArray, from: Int = 0): Int {
        outer@ for (i in from..data.size - pattern.size) {
            for (j in pattern.indices) if (data[i + j] != pattern[j]) continue@outer
            return i
        }
        return -1
    }

    private val audio = "AUDIO-1".toByteArray() + "AUDIO-2".toByteArray()

    /** MP4 "normal": moov delante con tabla stco que apunta dentro de mdat. */
    private fun plainMp4(): ByteArray {
        val ftyp = box("ftyp", "M4A ".toByteArray(), int(0))
        fun moovWith(offsets: List<Int>): ByteArray {
            val stco = box("stco", int(0), int(offsets.size), *offsets.map(::int).toTypedArray())
            return box("moov", box("mvhd", ByteArray(20)), box("trak", box("mdia", box("minf", box("stbl", stco)))))
        }
        val moovSize = moovWith(listOf(0, 0)).size
        val mdatStart = ftyp.size + moovSize
        val offsets = listOf(mdatStart + 8, mdatStart + 8 + 7)
        return ftyp + moovWith(offsets) + box("mdat", audio)
    }

    /** MP4 fragmentado con base_data_offset absoluto en tfhd (el peor caso). */
    private fun fragmentedMp4(): ByteArray {
        val ftyp = box("ftyp", "iso6".toByteArray(), int(0))
        val moov = box("moov", box("mvhd", ByteArray(20)))
        fun moofWith(base: Long) = box("moof", box("traf", box("tfhd", int(0x000001), int(1), long(base))))
        val moofSize = moofWith(0).size
        val mdatStart = ftyp.size + moov.size + moofSize
        return ftyp + moov + moofWith((mdatStart + 8).toLong()) + box("mdat", audio)
    }

    @Test
    fun `mp4 normal - las posiciones stco siguen apuntando al audio`() {
        val original = plainMp4()
        val tagged = Mp4Tagger.tag(original, tags)
        assertNotNull(tagged)
        tagged!!
        val stco = indexOf(tagged, "stco".toByteArray())
        val count = ByteBuffer.wrap(tagged, stco + 8, 4).int
        assertEquals(2, count)
        val first = ByteBuffer.wrap(tagged, stco + 12, 4).int
        val second = ByteBuffer.wrap(tagged, stco + 16, 4).int
        assertArrayEquals("AUDIO-1".toByteArray(), tagged.copyOfRange(first, first + 7))
        assertArrayEquals("AUDIO-2".toByteArray(), tagged.copyOfRange(second, second + 7))
        assertTrue(indexOf(tagged, "Canción de prueba".toByteArray()) > 0)
        assertTrue(indexOf(tagged, "covr".toByteArray()) > 0)
        assertTrue(indexOf(tagged, "ilst".toByteArray()) > 0)
    }

    @Test
    fun `mp4 fragmentado - tfhd sigue apuntando al audio`() {
        val tagged = Mp4Tagger.tag(fragmentedMp4(), tags)!!
        val tfhd = indexOf(tagged, "tfhd".toByteArray())
        val base = ByteBuffer.wrap(tagged, tfhd + 12, 8).long.toInt()
        assertArrayEquals(audio, tagged.copyOfRange(base, base + audio.size))
        // El audio sigue intacto al final del archivo.
        assertArrayEquals(audio, tagged.copyOfRange(tagged.size - audio.size, tagged.size))
    }

    @Test
    fun `etiquetar dos veces no duplica los datos`() {
        val once = Mp4Tagger.tag(plainMp4(), tags)!!
        val twice = Mp4Tagger.tag(once, tags)!!
        assertEquals(once.size, twice.size)
    }

    @Test
    fun `un archivo que no es mp4 no se toca`() {
        assertEquals(null, Mp4Tagger.tag("esto no es un mp4".toByteArray(), tags))
    }

    @Test
    fun `id3 en mp3 - cabecera, textos y audio intacto`() {
        val mp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte(), 0x64) + ByteArray(100) { it.toByte() }
        val tagged = Id3Tagger.tag(mp3, tags)
        assertEquals("ID3", String(tagged, 0, 3))
        assertEquals(3, tagged[3].toInt())
        assertTrue(indexOf(tagged, "Canción de prueba".toByteArray(Charsets.UTF_16LE)) > 0)
        assertTrue(indexOf(tagged, "APIC".toByteArray()) > 0)
        assertArrayEquals(mp3, tagged.copyOfRange(tagged.size - mp3.size, tagged.size))
        // Volver a etiquetar sustituye la etiqueta anterior en vez de apilarla.
        val again = Id3Tagger.tag(tagged, tags)
        assertEquals(tagged.size, again.size)
    }
}
