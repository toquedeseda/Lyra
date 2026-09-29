package com.lyra.music

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.InnerTube
import com.lyra.music.data.source.innertube.SearchFilter
import com.lyra.music.data.source.soundcloud.AudioQuality
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.data.source.soundcloud.SoundCloudFilter
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import okhttp3.Request
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.util.concurrent.TimeUnit

/**
 * Pruebas contra los servicios reales. Solo se ejecutan con LYRA_LIVE_TESTS=1,
 * para que un fallo de red no rompa la compilación normal.
 */
class LiveSourcesTest {

    private val http = OkHttpClient.Builder().readTimeout(30, TimeUnit.SECONDS).build()

    @Before
    fun onlyWhenAsked() {
        assumeTrue(System.getenv("LYRA_LIVE_TESTS") == "1")
    }

    @Test
    fun `youtube music - buscar, album, radio e inicio con paginas`() = runBlocking {
        val api = InnerTube(http)
        val songs = api.search("rosalia", SearchFilter.SONGS)
        assertTrue(songs.items.isNotEmpty())
        val more = api.searchMore(songs.continuation!!)
        assertTrue(more.items.isNotEmpty())

        val albumItem = api.search("rosalia motomami", SearchFilter.ALBUMS).items.first() as AlbumItem
        val album = api.album(albumItem.id)
        assertTrue(album.songs.size > 5)

        val radio = api.radio(album.songs.first().id)
        assertTrue(radio.songs.size > 20)
        val radio2 = api.radioMore(radio.continuation!!)
        assertTrue(radio2.songs.isNotEmpty())

        val home = api.home()
        assertTrue(home.sections.isNotEmpty())
        val home2 = api.homeMore(home.continuation!!)
        assertTrue("la segunda página del inicio llega vacía", home2.sections.isNotEmpty())

        val moods = api.moods()
        assertTrue(moods.flatMap { it.categories }.size > 5)
        val mood = api.browse(moods.first().categories.first().endpoint)
        assertTrue(mood.sections.isNotEmpty())

        println("OK YouTube Music: ${album.album.title}, radio ${radio.songs.size}+${radio2.songs.size}, inicio ${home.sections.size}+${home2.sections.size}, moods ${moods.size}")
    }

    @Test
    fun `youtube - url de audio reproducible`() = runBlocking {
        val source = NewPipeSource(http)
        val streams = source.audioStreams("yt:WdSGEvDGZAo")
        assertTrue(streams.isNotEmpty())
        streams.forEach { println("  ${it.mimeType} ${it.bitrate / 1000} kbps ${it.codec} hls=${it.isHls} len=${it.contentLength}") }
        val best = NewPipeSource.pick(streams, AudioQuality.HIGH)!!
        val response = http.newCall(
            Request.Builder().url(best.url).header("Range", "bytes=0-65535").build(),
        ).execute()
        println("  GET rango -> ${response.code} (${response.body.bytes().size} bytes)")
        assertTrue(response.code == 206 || response.code == 200)
    }

    @Test
    fun `soundcloud - buscar y audio`() = runBlocking {
        val source = NewPipeSource(http)
        val results = source.searchSoundCloud("lofi hip hop", SoundCloudFilter.TRACKS)
        val song = results.items.filterIsInstance<Song>().first()
        println("  SC: ${song.id} · ${song.title} · ${song.artistsText}")
        assertTrue(song.id.startsWith("sc:"))
        val streams = source.audioStreams(song.id)
        streams.forEach { println("  ${it.mimeType} ${it.bitrate / 1000} kbps hls=${it.isHls} ${it.url.take(60)}") }
        assertTrue(streams.isNotEmpty())
        val related = source.soundCloudRelated(song.id)
        println("  relacionadas: ${related.size}")
        val playlists = source.searchSoundCloud("chill", SoundCloudFilter.PLAYLISTS).items
        assertNotNull(playlists.firstOrNull())
        val users = source.searchSoundCloud("odesza", SoundCloudFilter.USERS).items
        val user = source.soundCloudUser(users.first().id)
        println("  artista SC: ${user.artist.title} con ${user.topSongs.size} pistas")
        assertEquals(true, user.topSongs.isNotEmpty())
    }
}
