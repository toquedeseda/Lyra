package com.lyra.desktop

import com.lyra.desktop.audio.AudioCache
import com.lyra.desktop.audio.FfmpegDecoder
import com.lyra.desktop.audio.RemoteStream
import com.lyra.desktop.audio.StreamResolver
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.soundcloud.AudioQuality
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.playback.AlternativeSources
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import kotlin.math.sqrt

/** Sonido de verdad: YouTube Music → caché por trozos → FFmpeg (solo con LYRA_LIVE_TESTS=1). */
class AudioLiveTest {

    @Before
    fun onlyLive() = assumeTrue(System.getenv("LYRA_LIVE_TESTS") == "1")

    private val http = OkHttpClient()
    private val dir: File = Files.createTempDirectory("lyra-audio").toFile()
    private val newPipe = NewPipeSource(http)
    private val innerTube = InnerTube(http)
    private val alternatives = AlternativeSources(newPipe, innerTube, { null }, File(dir, "alt.json"))

    private fun resolver(quality: AudioQuality) = StreamResolver(newPipe, alternatives, http, File(dir, "hls"), { quality })

    private fun rms(samples: FloatArray, count: Int): Double {
        var sum = 0.0
        for (i in 0 until count) sum += samples[i] * samples[i]
        return sqrt(sum / count.coerceAtLeast(1))
    }

    private fun play(songId: String, quality: AudioQuality, portable: Boolean = false) {
        val song = Song(songId, "Prueba", listOf(ArtistRef("Prueba")))
        val resolver = resolver(quality)
        if (portable) resolver.preferPortable(songId)
        val cache = AudioCache(File(dir, "cache-$quality-$portable"), http) { 500L * 1024 * 1024 }
        val input = cache.open(songId) { refresh ->
            if (refresh) resolver.invalidate(songId)
            val resolved = resolver.resolveBlocking(songId, song)
            RemoteStream(resolved.url ?: error("sin URL"), resolved.contentLength)
        }
        FfmpegDecoder(input).use { decoder ->
            println("$songId $quality portable=$portable códec=${decoder.codecName} duración=${decoder.durationMs}")
            assertTrue("duración", (decoder.durationMs ?: 0) > 60_000)
            val buffer = FloatArray(48_000 * 2 * 3)
            val read = decoder.read(buffer, 0, 48_000 * 3)
            assertEquals(48_000 * 3, read)
            val start = rms(buffer, read * 2)
            println("  nivel al empezar: $start")
            assertTrue("suena", start > 0.005)

            // Saltar a mitad de la canción (lo que aún no se ha bajado).
            assertTrue("salta", decoder.seek(90_000))
            val afterSeek = decoder.read(buffer, 0, 48_000)
            assertEquals(48_000, afterSeek)
            val middle = rms(buffer, afterSeek * 2)
            println("  nivel en 1:30: $middle, posición ${decoder.positionFrames / 48} ms")
            assertTrue("suena tras saltar", middle > 0.005)
            assertTrue("posición", decoder.positionFrames in (91_000L * 48)..(91_100L * 48))
        }
    }

    @Test
    fun `youtube en la mejor calidad (opus) suena y salta`() = play("yt:kJQP7kiw5Fk", AudioQuality.HIGH)

    @Test
    fun `youtube en m4a suena y salta`() = play("yt:kJQP7kiw5Fk", AudioQuality.NORMAL, portable = true)
}
