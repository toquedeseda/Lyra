package com.lyra.music

import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.playback.RadioController
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class RadioTest {

    private fun song(id: String, title: String, artist: String) = Song("yt:$id", title, listOf(ArtistRef(artist)))

    @Test
    fun `no pone dos canciones seguidas del mismo artista`() {
        val songs = listOf(
            song("1", "Uno", "A"), song("2", "Dos", "A"), song("3", "Tres", "A"),
            song("4", "Cuatro", "B"), song("5", "Cinco", "C"), song("6", "Seis", "D"),
        )
        val spread = RadioController.spreadArtists(songs, previousArtist = "A")
        assertEquals(songs.size, spread.size)
        assertNotEquals("A", spread.first().artists.first().name)
        spread.zipWithNext().forEach { (a, b) -> assertNotEquals(a.artists.first().name, b.artists.first().name) }
    }

    @Test
    fun `la misma cancion en video o audio cuenta como repetida`() {
        val audio = song("a", "Mi Canción", "Artista")
        val video = song("b", "Mi Canción (Official Video)", "Artista")
        val otra = song("c", "Otra", "Artista")
        assertEquals(RadioController.songKey(audio), RadioController.songKey(video))
        assertNotEquals(RadioController.songKey(audio), RadioController.songKey(otra))
    }
}
