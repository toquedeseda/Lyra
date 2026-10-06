package com.lyra.music

import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.data.share.PlaylistSharing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** Enlaces para compartir playlists cuando el servidor no responde (la playlist va dentro). */
class SharingTest {

    private fun songs(n: Int) = (1..n).map { i ->
        Song("yt:vid${i.toString().padStart(8, '0')}", "Canción número $i con título largo", listOf(ArtistRef("Artista $i"), ArtistRef("Invitado")))
    }

    @Test
    fun `el enlace largo trae la misma playlist`() {
        val playlist = PlaylistSharing.shareable("  Para correr ", songs(30) + songs(2))
        val decoded = PlaylistSharing.decode(PlaylistSharing.encode(playlist))
        assertEquals("Para correr", decoded.name)
        assertEquals(30, decoded.songs.size)
        assertEquals("yt:vid00000001", decoded.songs.first().id)
        assertEquals("Artista 1, Invitado", decoded.songs.first().artist)
        assertEquals(listOf("Artista 1", "Invitado"), decoded.songs.first().toSong().artists.map { it.name })
    }

    @Test
    fun `cien canciones caben en un enlace de tamano razonable`() {
        val link = PlaylistSharing.longLink(PlaylistSharing.shareable("Mix", songs(100)))
        assertTrue(link.startsWith("https://lyra.shopxcenter.duckdns.org/p#"))
        assertTrue("el enlace mide ${link.length}", link.length < 4_000)
        assertTrue(link.substringAfter('#').all { it.isLetterOrDigit() || it == '-' || it == '_' })
    }

    @Test
    fun `no se comparten mas canciones de las que acepta el servidor`() {
        val playlist = PlaylistSharing.shareable("Enorme", songs(PlaylistSharing.MAX_SONGS + 50))
        assertEquals(PlaylistSharing.MAX_SONGS, playlist.songs.size)
    }
}
