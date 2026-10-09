package com.lyra.music.playback

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

class LoudnessMemoryTest {

    private fun file() = Files.createTempDirectory("sonoridad").resolve("sonoridad.txt").toFile()

    @Test
    fun `recuerda lo medido y lo guarda`() {
        val file = file()
        val memory = LoudnessMemory(file)
        assertTrue(memory.remember("yt:abc", -9.5f, seconds = 180f))
        memory.save()
        assertEquals(-9.5f, LoudnessMemory(file).get("yt:abc")!!, 0.01f)
    }

    @Test
    fun `con poco escuchado no vale`() {
        val memory = LoudnessMemory(file())
        assertFalse(memory.remember("yt:a", -9f, seconds = 12f))
        assertNull(memory.get("yt:a"))
        // Una canción corta casi entera, sí.
        assertTrue(memory.remember("yt:b", -9f, seconds = 20f, durationSeconds = 25f))
        // Valores sin sentido, no.
        assertFalse(memory.remember("yt:c", Float.NaN, seconds = 200f))
        assertFalse(memory.remember("yt:d", 5f, seconds = 200f))
    }

    @Test
    fun `se queda con las ultimas`() {
        val file = file()
        val memory = LoudnessMemory(file, max = 3)
        for (i in 1..5) memory.remember("yt:$i", -10f - i, seconds = 60f)
        memory.save()
        val again = LoudnessMemory(file, max = 3)
        assertNull(again.get("yt:1"))
        assertNull(again.get("yt:2"))
        assertEquals(-15f, again.get("yt:5")!!, 0.01f)
    }
}
