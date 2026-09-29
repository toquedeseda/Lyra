package com.lyra.music

import com.lyra.music.data.model.AlbumRef
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.data.repo.SpotifyImporter
import com.lyra.music.ui.components.SongOrder
import com.lyra.music.ui.components.filterSongs
import com.lyra.music.ui.components.matchesQuery
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class LibraryFeaturesTest {

    private fun song(id: String, title: String, artist: String, album: String? = null) =
        Song("yt:$id", title, listOf(ArtistRef(artist)), album?.let { AlbumRef(it) })

    @Test
    fun `buscar ignora tildes y mayusculas y vale en cualquier orden`() {
        assertTrue(matchesQuery("cancion", "Canción del Mariachi"))
        assertTrue(matchesQuery("BUNNY titi", "Tití Me Preguntó", "Bad Bunny"))
        assertTrue(matchesQuery("  ", "Lo que sea"))
        assertFalse(matchesQuery("rosalia", "Despechá", "Otra artista"))
    }

    @Test
    fun `filtrar y ordenar listas de canciones`() {
        val songs = listOf(
            song("1", "Zapatos", "Álvaro", "Norte"),
            song("2", "árbol", "Beatriz", null),
            song("3", "Monte", "Álvaro", "Este"),
        )
        assertEquals(listOf("yt:1", "yt:2", "yt:3"), songs.filterSongs("", SongOrder.DEFAULT) { it }.map { it.id })
        assertEquals(listOf("yt:2", "yt:3", "yt:1"), songs.filterSongs("", SongOrder.TITLE) { it }.map { it.id })
        assertEquals(listOf("yt:3", "yt:1", "yt:2"), songs.filterSongs("", SongOrder.ARTIST) { it }.map { it.id })
        // Sin álbum, al final.
        assertEquals(listOf("yt:3", "yt:1", "yt:2"), songs.filterSongs("", SongOrder.ALBUM) { it }.map { it.id })
        assertEquals(listOf("yt:1", "yt:3"), songs.filterSongs("alvaro", SongOrder.DEFAULT) { it }.map { it.id })
        assertEquals(listOf("yt:3"), songs.filterSongs("este", SongOrder.TITLE) { it }.map { it.id })
    }

    @Test
    fun `enlaces de Spotify`() {
        assertEquals("playlist" to "37i9dQZF1DXcBWIGoYBM5M", SpotifyImporter.parseLink("https://open.spotify.com/playlist/37i9dQZF1DXcBWIGoYBM5M?si=abc"))
        assertEquals("album" to "4aawyAB9vmqN3uQ7FjRGTy", SpotifyImporter.parseLink("Mira esto https://open.spotify.com/intl-es/album/4aawyAB9vmqN3uQ7FjRGTy"))
        assertEquals("track" to "11dFghVXANMlKmJXsNCbNl", SpotifyImporter.parseLink("spotify:track:11dFghVXANMlKmJXsNCbNl"))
        assertNull(SpotifyImporter.parseLink("https://music.youtube.com/watch?v=abc"))
    }

    @Test
    fun `normalizar titulos para emparejar con YouTube Music`() {
        assertEquals(SpotifyImporter.normalize("Canción (feat. Otro)"), SpotifyImporter.normalize("cancion"))
        assertEquals(SpotifyImporter.normalize("Song - Remastered 2011"), SpotifyImporter.normalize("Song"))
    }
}
