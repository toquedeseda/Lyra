package com.lyra.music

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.InnerTubeParser
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El parser contra respuestas reales de YouTube Music guardadas el 2026-09-28. */
class InnerTubeParserTest {

    private fun fixture(name: String): JsonObject {
        val text = javaClass.classLoader!!.getResource("innertube/$name.json")!!.readText()
        return Json.parseToJsonElement(text).jsonObject
    }

    @Test
    fun `busqueda de canciones`() {
        val page = InnerTubeParser.search(fixture("search_songs"))
        val songs = page.items.filterIsInstance<Song>()
        assertTrue("esperaba 20 canciones, hay ${songs.size}", songs.size >= 15)
        val first = songs.first()
        assertEquals("yt:WdSGEvDGZAo", first.id)
        assertEquals("NUEVAYoL", first.title)
        assertEquals("Bad Bunny", first.artists.first().name)
        assertEquals("yt:UCiY3z8HAGD6BlSNKVn2kSvQ", first.artists.first().id)
        assertNotNull(first.album)
        assertNotNull(first.durationMs)
        assertFalse(first.isVideo)
        assertTrue(first.thumbnailUrl!!.contains("w544-h544"))
        assertNotNull("la búsqueda filtrada tiene más páginas", page.continuation)
    }

    @Test
    fun `busqueda general con resultado destacado`() {
        val page = InnerTubeParser.search(fixture("s_all"))
        val top = page.topResult
        assertTrue(top is ArtistItem)
        assertEquals("Bad Bunny", top!!.title)
        assertTrue(page.items.any { it is ArtistItem })
        assertTrue(page.items.any { it is AlbumItem })
        assertTrue(page.items.any { it is PlaylistItem })
        val song = page.items.filterIsInstance<Song>().first { it.id == "yt:juRFjpB5Ppg" }
        assertEquals("Bad Bunny", song.artists.single().name)
        // Los episodios de pódcast no se cuelan como canciones.
        assertFalse(page.items.any { it.id.contains("XL_55_O2B_E") })
    }

    @Test
    fun `busqueda de albumes artistas y playlists`() {
        val albums = InnerTubeParser.search(fixture("s_albums")).items
        assertTrue(albums.isNotEmpty() && albums.all { it is AlbumItem })
        val album = albums.first() as AlbumItem
        assertTrue(album.id.startsWith("yt:MPREb"))
        assertTrue(album.artists.isNotEmpty())

        val artists = InnerTubeParser.search(fixture("s_artists")).items
        assertTrue(artists.first() is ArtistItem)

        val playlists = InnerTubeParser.search(fixture("s_playlists")).items
        assertTrue(playlists.first() is PlaylistItem)
        assertTrue(playlists.first().id.startsWith("yt:VL"))
    }

    @Test
    fun `pagina de album`() {
        val page = InnerTubeParser.album(fixture("album"), "MPREb_tZC7e1H5mfm")
        assertEquals("DeBÍ TiRAR MáS FOToS", page.album.title)
        assertEquals("Bad Bunny", page.album.artists.single().name)
        assertEquals("2025", page.album.year)
        assertEquals(17, page.songs.size)
        page.songs.forEach {
            assertNotNull(it.durationMs)
            assertEquals("yt:MPREb_tZC7e1H5mfm", it.album?.id)
            assertNotNull("las pistas heredan la carátula del álbum", it.thumbnailUrl)
        }
        assertNotNull(page.subtitle)
    }

    @Test
    fun `pagina de artista`() {
        val page = InnerTubeParser.artist(fixture("artist"), "UCiY3z8HAGD6BlSNKVn2kSvQ")
        assertEquals("Bad Bunny", page.artist.title)
        assertEquals(5, page.topSongs.size)
        assertNotNull(page.topSongsMore)
        assertNotNull(page.listeners)
        val titles = page.sections.map { it.title }
        assertTrue(titles.toString(), titles.contains("Álbumes"))
        assertTrue(titles.toString(), titles.contains("Singles y EPs"))
        assertTrue(page.sections.first { it.title == "Álbumes" }.items.all { it is AlbumItem })
        assertTrue(page.sections.last().items.all { it is ArtistItem })
    }

    @Test
    fun `playlist`() {
        val page = InnerTubeParser.playlist(fixture("playlist"), "VLRDCLAK5uy_k3jElZuYeDhqZsFkUnRf519q4CD52CaRY")
        assertEquals(39, page.songs.size)
        assertTrue(page.playlist.title.isNotBlank())
        assertTrue(page.songs.all { it.artists.isNotEmpty() })
    }

    @Test
    fun `radio`() {
        val page = InnerTubeParser.radio(fixture("next"))
        assertTrue("esperaba unas 50, hay ${page.songs.size}", page.songs.size >= 40)
        assertEquals(page.songs.size, page.songs.distinctBy { it.id }.size)
        assertNotNull(page.continuation)
        assertTrue(page.relatedBrowseId!!.startsWith("MPTR"))
        val song = page.songs[1]
        assertTrue(song.artists.isNotEmpty())
        assertNotNull(song.durationMs)
    }

    @Test
    fun `inicio`() {
        val page = InnerTubeParser.home(fixture("home"))
        assertEquals(3, page.sections.size)
        assertTrue(page.sections.first().items.all { it is Song })
        assertTrue(page.chips.size >= 5)
        assertNotNull(page.continuation)
    }

    @Test
    fun `sugerencias`() {
        val result = InnerTubeParser.suggestions(fixture("sugg"))
        assertTrue(result.queries.first().startsWith("bad bunny"))
        assertTrue(result.items.any { it is ArtistItem })
        val song = result.items.filterIsInstance<Song>().first()
        assertNotNull(song.album)
    }

    @Test
    fun `duraciones`() {
        assertEquals(184_000L, InnerTubeParser.parseDuration("3:04"))
        assertEquals(3_723_000L, InnerTubeParser.parseDuration("1:02:03"))
        assertNull(InnerTubeParser.parseDuration("2025"))
    }
}
