package com.lyra.music

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.SongMatcher
import com.lyra.music.playback.AlternativeSources
import com.lyra.music.playback.AudioFxConfig
import com.lyra.music.playback.LyraAudioProcessor
import com.lyra.music.playback.SmartShuffleController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.schabi.newpipe.extractor.exceptions.AgeRestrictedContentException
import org.schabi.newpipe.extractor.exceptions.ContentNotAvailableException
import org.schabi.newpipe.extractor.exceptions.SignInConfirmNotBotException
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.sin

class SmartFeaturesTest {

    private fun song(id: String, title: String, artist: String, durationMs: Long? = null) =
        Song("yt:$id", title, listOf(ArtistRef(artist)), durationMs = durationMs)

    @Test
    fun `reconoce otra version de la misma cancion`() {
        val wanted = song("a", "CHICKEN TERIYAKI", "ROSALÍA", 150_000)
        val video = song("b", "CHICKEN TERIYAKI (Official Video)", "Rosalia", 152_000)
        val otherArtist = song("c", "Chicken Teriyaki", "Cover Band", 150_000)
        val other = song("d", "SAOKO", "ROSALÍA", 140_000)
        fun score(candidate: Song) = SongMatcher.score(wanted.title, wanted.artists.map { it.name }, wanted.durationMs, candidate)
        assertTrue(score(video) >= 5.0)
        assertTrue(score(otherArtist) < 7.0)
        assertTrue(score(other) < 5.0)
    }

    @Test
    fun `entiende los titulos de videos subidos por fans`() {
        val fanUpload = song("x", "Rammstein - ''Pussy'' - (OFFICIAL VIDEO) -  [FIXED AUDIO] - (English CC)", "Eliass Kevrelis")
        val readings = AlternativeSources.readings(fanUpload)
        assertEquals("Pussy" to listOf("Rammstein"), readings.first())
        assertEquals(1, AlternativeSources.readings(song("y", "SAOKO", "ROSALÍA")).size)
    }

    @Test
    fun `solo se busca otra version cuando el fallo es de ese video`() {
        val signIn = ContentNotAvailableException("Got error LOGIN_REQUIRED: \"Please sign in\"")
        val age = AgeRestrictedContentException("This age-restricted video cannot be watched anonymously")
        val bot = SignInConfirmNotBotException("YouTube probably temporarily blocked anonymous watch access")
        assertTrue(AlternativeSources.worthLookingElsewhere(signIn))
        assertTrue(AlternativeSources.worthLookingElsewhere(age))
        assertFalse(AlternativeSources.worthLookingElsewhere(bot))
        assertFalse(AlternativeSources.worthLookingElsewhere(IOException("sin red")))
        assertTrue(AlternativeSources.friendly(signIn).message!!.contains("iniciar sesión"))
        assertTrue(AlternativeSources.friendly(age).message!!.contains("restricción de edad"))
        assertTrue(AlternativeSources.friendly(bot).message!!.contains("frenado"))
    }

    @Test
    fun `el aleatorio inteligente reparte una recomendada cada tres`() {
        val mixed = SmartShuffleController.interleave(listOf(1, 2, 3, 4, 5, 6, 7), listOf(-1, -2, -3), every = 3)
        assertEquals(listOf(1, 2, 3, -1, 4, 5, 6, -2, 7, -3), mixed)
        assertEquals(listOf(1, 2), SmartShuffleController.interleave(listOf(1, 2), emptyList(), every = 3))
    }

    @Test
    fun `mide la sonoridad para saber cuando acaba una cancion`() {
        val processor = LyraAudioProcessor().apply { config = AudioFxConfig(eqEnabled = false, normalize = false) }
        processor.configure(AudioProcessor.AudioFormat(RATE, 2, C.ENCODING_PCM_16BIT))
        processor.flush()
        feed(processor, amplitude = 0.3, seconds = 4.0)
        val playing = processor.loudness!!
        assertFalse(playing.trackDb.isNaN())
        assertEquals(playing.trackDb, playing.blockDb, 1.5f)
        // El fundido final queda muy por debajo de la media de la canción.
        feed(processor, amplitude = 0.01, seconds = 1.0)
        val fading = processor.loudness!!
        assertTrue(fading.blockDb < fading.trackDb - 18f)
    }

    private fun feed(processor: LyraAudioProcessor, amplitude: Double, seconds: Double) {
        val frames = (RATE * seconds).toInt()
        var frame = 0
        while (frame < frames) {
            val n = minOf(4096, frames - frame)
            val input = ByteBuffer.allocateDirect(n * 4).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) {
                val v = (amplitude * sin(2 * PI * 440.0 * (frame + i) / RATE) * 32767).toInt().toShort()
                input.putShort(v)
                input.putShort(v)
            }
            input.flip()
            processor.queueInput(input)
            processor.output // se consume la salida
            frame += n
        }
    }

    private companion object {
        const val RATE = 48_000
    }
}
