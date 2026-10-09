package com.lyra.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.lyra.music.data.source.lyrics.Lrclib
import com.lyra.music.playback.AudioFxConfig
import com.lyra.music.playback.AudioLevels
import com.lyra.music.playback.LyraAudioProcessor
import com.lyra.music.update.VersionComparator
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.log10
import kotlin.math.sin
import kotlin.math.sqrt

class AudioProcessorTest {

    private val rate = 48_000

    /** Pasa [seconds] de un seno por el procesador y devuelve las muestras de salida (canal izquierdo). */
    private fun run(processor: LyraAudioProcessor, frequency: Double, amplitude: Double, seconds: Double): FloatArray {
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        val frames = (rate * seconds).toInt()
        val out = FloatArray(frames)
        var written = 0
        val chunk = 4096
        var frame = 0
        while (frame < frames) {
            val n = minOf(chunk, frames - frame)
            val input = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val v = (amplitude * sin(2 * PI * frequency * (frame + i) / rate) * 32767).toInt().toShort()
                input.putShort(v)
                input.putShort(v)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            while (output.remaining() >= 4) {
                out[written++] = output.short / 32768f
                output.short
            }
            frame += n
        }
        return out.copyOf(written)
    }

    private fun rmsDb(samples: FloatArray, from: Int, to: Int): Double {
        var sum = 0.0
        for (i in from until to) sum += samples[i] * samples[i]
        return 20 * log10(sqrt(sum / (to - from)) + 1e-12)
    }

    @Test
    fun `sin efectos el audio pasa tal cual`() {
        val processor = LyraAudioProcessor().apply { config = AudioFxConfig(eqEnabled = false, normalize = false) }
        val out = run(processor, 440.0, 0.25, 0.5)
        val inputDb = 20 * log10(0.25 / sqrt(2.0))
        assertEquals(inputDb, rmsDb(out, 1000, out.size), 0.2)
    }

    @Test
    fun `el ecualizador sube la banda de 1 kHz`() {
        val bands = List(10) { if (it == 5) 6f else 0f }
        val flat = run(LyraAudioProcessor().apply { config = AudioFxConfig(false, normalize = false) }, 1000.0, 0.1, 0.5)
        val boosted = run(LyraAudioProcessor().apply { config = AudioFxConfig(true, bands, 0f, normalize = false) }, 1000.0, 0.1, 0.5)
        // +6 dB de la banda y -3 dB de margen automático = +3 dB netos.
        val gain = rmsDb(boosted, 4800, boosted.size) - rmsDb(flat, 4800, flat.size)
        assertEquals(3.0, gain, 0.6)
    }

    @Test
    fun `el ecualizador no toca frecuencias lejanas`() {
        val bands = List(10) { if (it == 0) 8f else 0f }
        val flat = run(LyraAudioProcessor().apply { config = AudioFxConfig(false, normalize = false) }, 8000.0, 0.1, 0.3)
        val eq = run(LyraAudioProcessor().apply { config = AudioFxConfig(true, bands, 4f, normalize = false) }, 8000.0, 0.1, 0.3)
        // Solo se nota el preamplificador (+4 dB) y el margen automático (-4 dB).
        assertEquals(0.0, rmsDb(eq, 2000, eq.size) - rmsDb(flat, 2000, flat.size), 0.5)
    }

    @Test
    fun `igualar volumen baja una cancion muy fuerte`() {
        val out = run(LyraAudioProcessor().apply { config = AudioFxConfig(normalize = true) }, 300.0, 0.9, 12.0)
        val start = rmsDb(out, 0, rate / 2)
        val end = rmsDb(out, out.size - rate, out.size)
        assertTrue("debería bajar al menos 8 dB (de $start a $end)", end < start - 8)
    }

    @Test
    fun `igualar volumen sube una cancion muy floja`() {
        val out = run(LyraAudioProcessor().apply { config = AudioFxConfig(normalize = true) }, 300.0, 0.02, 12.0)
        val start = rmsDb(out, 0, rate / 2)
        val end = rmsDb(out, out.size - rate, out.size)
        assertTrue("debería subir al menos 5 dB (de $start a $end)", end > start + 5)
    }

    @Test
    fun `publica niveles variados para las barras de la isla`() {
        val processor = LyraAudioProcessor().apply {
            config = AudioFxConfig(normalize = false)
            publishLevels = true
        }
        processor.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        // Graves que suben y bajan (como un bombo) durante 3 segundos.
        val frames = rate * 3
        val seen = mutableListOf<Float>()
        val out = FloatArray(3)
        var frame = 0
        while (frame < frames) {
            val n = minOf(2048, frames - frame)
            val input = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val t = (frame + i).toDouble() / rate
                val envelope = if ((t * 2).toInt() % 2 == 0) 0.8 else 0.05
                val v = (envelope * sin(2 * PI * 80 * t) * 32767).toInt().toShort()
                input.putShort(v)
                input.putShort(v)
            }
            input.flip()
            processor.queueInput(input)
            processor.output
            frame += n
            if (AudioLevels.sample(System.nanoTime(), out)) seen += out[0]
        }
        assertTrue("sin niveles publicados", seen.isNotEmpty())
        assertTrue("las barras deberían bajar (mín ${seen.min()})", seen.min() < 0.3f)
        assertTrue("las barras deberían subir (máx ${seen.max()})", seen.max() > 0.7f)
        assertTrue(seen.all { it in 0f..1f })
    }

    /** Sigue pasando un seno por un procesador ya configurado (sin reiniciarlo), como al seguir sonando. */
    private fun feed(processor: LyraAudioProcessor, amplitude: Double, seconds: Double): FloatArray {
        val frames = (rate * seconds).toInt()
        val out = FloatArray(frames)
        var written = 0
        var frame = 0
        while (frame < frames) {
            val n = minOf(4096, frames - frame)
            val input = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val v = (amplitude * sin(2 * PI * 300.0 * (frame + i) / rate) * 32767).toInt().toShort()
                input.putShort(v)
                input.putShort(v)
            }
            input.flip()
            processor.queueInput(input)
            val output = processor.output.order(ByteOrder.LITTLE_ENDIAN)
            while (output.remaining() >= 4) {
                out[written++] = output.short / 32768f
                output.short
            }
            frame += n
        }
        return out.copyOf(written)
    }

    @Test
    fun `en el crossfade el final de la cancion no sube de golpe`() {
        // El principal lleva un rato bajando una canción fuerte.
        val main = LyraAudioProcessor().apply { config = AudioFxConfig(normalize = true) }
        val mainOut = run(main, 300.0, 0.9, 12.0)
        val mainLevel = rmsDb(mainOut, mainOut.size - rate / 2, mainOut.size)
        // El auxiliar sigue con el final de la misma canción y la misma ganancia.
        val tail = LyraAudioProcessor().apply {
            config = AudioFxConfig(normalize = true)
            holdGainDb = main.currentGainDb
        }
        val tailOut = run(tail, 300.0, 0.9, 3.0)
        val first = rmsDb(tailOut, 0, rate / 10)
        val last = rmsDb(tailOut, tailOut.size - rate / 2, tailOut.size)
        assertEquals("al empezar, igual que el principal", mainLevel, first, 0.5)
        assertEquals("y sin moverse después", mainLevel, last, 0.5)
        // Sin pasarle la ganancia (como antes), el final sonaba varios dB más fuerte.
        val fresh = run(LyraAudioProcessor().apply { config = AudioFxConfig(normalize = true) }, 300.0, 0.9, 0.5)
        assertTrue(rmsDb(fresh, 0, rate / 10) > mainLevel + 6)
    }

    @Test
    fun `una cancion ya medida suena bien desde el principio`() {
        // Primera vez: se mide entera.
        val first = LyraAudioProcessor().apply { config = AudioFxConfig(normalize = true) }
        val firstOut = run(first, 300.0, 0.9, 12.0)
        val settled = rmsDb(firstOut, firstOut.size - rate / 2, firstOut.size)
        val known = first.measuredDb
        assertTrue(first.measuredSeconds > 10f)
        // Otra vez: con lo medido, en medio segundo ya está donde acabó la primera vez.
        val again = LyraAudioProcessor().apply { config = AudioFxConfig(normalize = true) }
        again.configure(AudioProcessor.AudioFormat(rate, 2, C.ENCODING_PCM_16BIT))
        again.flush()
        again.onNewTrack(known)
        val againOut = feed(again, 0.9, 2.0)
        assertEquals(settled, rmsDb(againOut, rate / 2, rate), 1.0)
        // Sin saberlo, el primer medio segundo suena muy por encima.
        assertTrue(rmsDb(firstOut, 0, rate / 2) > settled + 6)
    }

    @Test
    fun `saltar dentro de la cancion no cambia el volumen de golpe`() {
        val processor = LyraAudioProcessor().apply { config = AudioFxConfig(normalize = true) }
        val loud = run(processor, 300.0, 0.5, 12.0)
        val before = rmsDb(loud, loud.size - rate / 2, loud.size) - 20 * log10(0.5)
        // Salto (flush) a una parte flojita: la ganancia sigue casi igual que antes.
        processor.flush()
        val quiet = feed(processor, 0.05, 2.0)
        val after = rmsDb(quiet, quiet.size - rate / 2, quiet.size) - 20 * log10(0.05)
        assertEquals(before, after, 1.5)
    }

    @Test
    fun `el limitador nunca satura`() {
        val bands = List(10) { 12f }
        val out = run(LyraAudioProcessor().apply { config = AudioFxConfig(true, bands, 6f, normalize = false) }, 100.0, 0.99, 1.0)
        assertTrue(out.all { it in -1f..1f })
        assertTrue(LyraAudioProcessor.softLimit(3f) <= 1f)
        assertTrue(LyraAudioProcessor.softLimit(-3f) >= -1f)
        assertEquals(0.5f, LyraAudioProcessor.softLimit(0.5f), 0f)
    }
}

class ParsingTest {

    @Test
    fun `lrc con varias marcas por linea`() {
        // Texto inventado para la prueba.
        val lrc = """
            [ar:Prueba]
            [00:01.50]primera línea de prueba
            [00:03.00][00:10.25]línea que se repite
            [01:02.5]otra más
            texto sin marca que se ignora
        """.trimIndent()
        val lines = Lrclib.parseLrc(lrc)
        assertEquals(listOf(1_500L, 3_000L, 10_250L, 62_500L), lines.map { it.timeMs })
        assertEquals("línea que se repite", lines[1].text)
        assertEquals("línea que se repite", lines[2].text)
    }

    @Test
    fun `limpieza de titulos para buscar la letra`() {
        assertEquals("Mi Canción", Lrclib.cleanTitle("Mi Canción (Official Video)"))
        assertEquals("Mi Canción", Lrclib.cleanTitle("Mi Canción [Lyric Video]"))
        assertEquals("Mi Canción", Lrclib.cleanTitle("Mi Canción (feat. Alguien)"))
        assertEquals("Tema", Lrclib.cleanTitle("Tema ft. Otro"))
    }

    @Test
    fun `comparador de versiones`() {
        assertTrue(VersionComparator.isNewer("1.0.0", "1.0.1"))
        assertTrue(VersionComparator.isNewer("1.2.9", "1.2.10"))
        assertTrue(VersionComparator.isNewer("1.9", "2.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.0", "1.0.0"))
        assertFalse(VersionComparator.isNewer("1.0.1-debug", "1.0.1"))
        assertFalse(VersionComparator.isNewer("2.0.0", "v1.9.9"))
    }
}
