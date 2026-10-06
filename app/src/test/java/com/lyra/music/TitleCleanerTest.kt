package com.lyra.music

import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.TitleCleaner
import com.lyra.music.data.model.cleaned
import org.junit.Assert.assertEquals
import org.junit.Test

class TitleCleanerTest {

    private fun video(title: String, vararg artists: String) =
        Song("yt:v", title, artists.map { ArtistRef(it, "yt:$it") }, isVideo = true)

    private fun song(title: String, vararg artists: String) = Song("yt:s", title, artists.map { ArtistRef(it) })

    @Test
    fun `quita el ruido de los videos`() {
        assertEquals("CHICKEN TERIYAKI", video("CHICKEN TERIYAKI (Official Video)", "ROSALÍA").cleaned().title)
        assertEquals("Don't Start Now", video("Don't Start Now (Official Music Video)", "Dua Lipa").cleaned().title)
        assertEquals("Rockin'", video("Rockin' (Lyrics)", "The Weeknd").cleaned().title)
        assertEquals("Despechá", video("Despechá [Video Oficial] [HD]", "ROSALÍA").cleaned().title)
        assertEquals("Song", video("Song | Official Audio", "Artist").cleaned().title)
    }

    @Test
    fun `quita el ruido sin parentesis del final`() {
        assertEquals(
            "Marilyn Manson Tainted Love",
            video("Marilyn Manson Tainted Love Official Music Video and Lyrics", "Music Club").cleaned().title,
        )
        assertEquals("Ojitos Lindos", video("Ojitos Lindos Video Oficial 2022", "Fan").cleaned().title)
        // Una sola palabra o nombres de canción de verdad se quedan.
        assertEquals("Video Games", song("Video Games", "Lana Del Rey").cleaned().title)
        assertEquals("Music", song("Music", "Madonna").cleaned().title)
        assertEquals("Love Song Lyrics", song("Love Song Lyrics", "Someone").cleaned().title)
    }

    @Test
    fun `deja lo que dice algo de la cancion`() {
        assertEquals("After (con Young Miko)", song("After (con Young Miko)", "Conep").cleaned().title)
        assertEquals("Song (Remix)", song("Song (Remix)", "Artist").cleaned().title)
        assertEquals("Song - Remastered 2011", song("Song - Remastered 2011", "Queen").cleaned().title)
        assertEquals("Jay-Z Song", song("Jay-Z Song", "Someone").cleaned().title)
    }

    @Test
    fun `separa artista y cancion en subidas de fans`() {
        val fan = video("Rammstein - ''Pussy'' - (OFFICIAL VIDEO) -  [FIXED AUDIO] - (English CC)", "Eliass Kevrelis").cleaned()
        assertEquals("Pussy", fan.title)
        assertEquals(listOf("Rammstein"), fan.artists.map { it.name })
        assertEquals(null, fan.artists.first().id)
    }

    @Test
    fun `quita el artista repetido en el titulo y conserva su enlace`() {
        val own = video("Cardi B - WAP feat. Megan Thee Stallion [Official Music Video]", "Cardi B").cleaned()
        assertEquals("WAP feat. Megan Thee Stallion", own.title)
        assertEquals("yt:Cardi B", own.artists.first().id)
        val duo = video("Bad Bunny & Jhayco - DÁKITI (Visualizer)", "Bad Bunny").cleaned()
        assertEquals("DÁKITI", duo.title)
        assertEquals(listOf("Bad Bunny", "Jhayco"), duo.artists.map { it.name })
    }

    @Test
    fun `soundcloud y nombres de canal`() {
        val sc = Song("sc:odesza/say-my-name", "ODESZA - Say My Name (Hayden James Remix)", listOf(ArtistRef("ODESZA"))).cleaned()
        assertEquals("Say My Name (Hayden James Remix)", sc.title)
        assertEquals("Rammstein", TitleCleaner.cleanArtist("RammsteinVEVO"))
        assertEquals("Bad Bunny", TitleCleaner.cleanArtist("Bad Bunny - Topic"))
        assertEquals("VEVO", TitleCleaner.cleanArtist("VEVO"))
    }
}
