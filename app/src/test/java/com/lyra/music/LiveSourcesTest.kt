package com.lyra.music

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.playback.AlternativeSources
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
import java.io.File
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
    fun `youtube music - datos de varias canciones a la vez`() = runBlocking {
        val api = InnerTube(http)
        val ids = listOf("yt:kJQP7kiw5Fk", "yt:qkO6iBwcoe4", "yt:lxvv1M7MACo")
        val songs = api.songs(ids)
        assertEquals(ids.toSet(), songs.map { it.id }.toSet())
        assertTrue(songs.all { it.title.isNotBlank() && it.artists.isNotEmpty() && it.thumbnailUrl != null })
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
        // YouTube no siempre da una segunda página del inicio (según el día y la región).
        val home2 = home.continuation?.let { api.homeMore(it) }
        if (home2 != null) assertTrue("la segunda página del inicio llega vacía", home2.sections.isNotEmpty())

        val moods = api.moods()
        assertTrue(moods.flatMap { it.categories }.size > 5)
        val mood = api.browse(moods.first().categories.first().endpoint)
        assertTrue(mood.sections.isNotEmpty())

        println("OK YouTube Music: ${album.album.title}, radio ${radio.songs.size}+${radio2.songs.size}, inicio ${home.sections.size}+${home2?.sections?.size ?: 0}, moods ${moods.size}")
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
    fun `youtube - si el video esta restringido se usa otra version`() = runBlocking {
        val source = NewPipeSource(http)
        // Tal cual sale en la búsqueda de vídeos: subido por un fan, con el artista dentro del título.
        val restricted = Song(
            "yt:qkO6iBwcoe4",
            "Rammstein - ''Pussy'' - (OFFICIAL VIDEO) -  [FIXED AUDIO] - (English CC)",
            listOf(ArtistRef("Eliass Kevrelis")),
        )
        val store = File.createTempFile("alternatives", ".json").apply { delete() }
        val alternatives = AlternativeSources(source, InnerTube(http), { id -> restricted.takeIf { it.id == id } }, store)
        // El vídeo original pide cuenta (restricción de edad)...
        val direct = runCatching { source.audioStreams(restricted.id) }
        assertTrue(direct.isFailure && AlternativeSources.worthLookingElsewhere(direct.exceptionOrNull()!!))
        // ...pero la canción suena con otra versión, que queda apuntada.
        val streams = alternatives.audioStreams(restricted.id)
        val alternative = alternatives.alternativeFor(restricted.id)
        println("  alternativa de ${restricted.id}: $alternative (${streams.size} pistas)")
        assertTrue(streams.isNotEmpty())
        assertNotNull(alternative)
        assertTrue(store.readText().contains(alternative!!))
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
