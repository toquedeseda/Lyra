package com.lyra.desktop

import com.lyra.desktop.data.Downloads
import com.lyra.desktop.data.Library
import com.lyra.desktop.data.LibraryEntry
import com.lyra.desktop.data.LibrarySort
import com.lyra.desktop.player.savedRange
import com.lyra.desktop.update.Updater
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.sync.SyncEngine
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.nio.file.Files

/** Lo que no necesita internet ni tarjeta de sonido: versiones, nombres de archivo, biblioteca. */
class DesktopLogicTest {

    private fun library() = Library(Files.createTempFile("biblioteca", ".json").toFile(), CoroutineScope(SupervisorJob() + Dispatchers.Default))

    private fun song(n: Int, artist: String = "Artista") = Song("yt:cancion$n", "Canción $n", listOf(ArtistRef(artist, "yt:UC$artist")))

    @Test
    fun `versiones nuevas`() {
        assertTrue(Updater.isNewer("1.10.0", "1.9.1"))
        assertTrue(Updater.isNewer("2.0.0", "1.99.99"))
        assertFalse(Updater.isNewer("1.9.1", "1.9.1"))
        assertFalse(Updater.isNewer("1.9.0", "1.10.0"))
    }

    @Test
    fun `las notas sin la guia de instalar`() {
        assertEquals("- Arreglos", Updater.cleanNotes("- Arreglos\n\n<!-- instalar -->\n\n## Antes de instalar…"))
    }

    @Test
    fun `nombres de archivo que Windows acepta`() {
        val song = Song("yt:x", "¿Qué: pasa? / \"Remix\"", listOf(ArtistRef("AC/DC"), ArtistRef("Otro"), ArtistRef("Tercero")))
        val name = Downloads.fileNameFor(song, "m4a")
        assertEquals("AC DC, Otro - ¿Qué pasa Remix.m4a", name)
        assertFalse(name.any { it in "\\/:*?\"<>|" })
    }

    @Test
    fun `playlists, carpetas y Me gusta`() {
        val lib = library()
        val id = lib.createPlaylist("Para correr", listOf(song(1), song(2)))
        assertEquals(1, lib.addToPlaylist(id, listOf(song(2), song(3))))
        lib.movePlaylistSong(id, 2, 0)
        assertEquals(listOf("yt:cancion3", "yt:cancion1", "yt:cancion2"), lib.playlist(id)?.songIds)
        lib.removeFromPlaylist(id, setOf("yt:cancion1"))
        assertEquals(2, lib.playlistSongs(id).size)
        val folder = lib.createFolder("Deporte")
        lib.movePlaylistToFolder(id, folder)
        lib.deleteFolder(folder)
        assertEquals(null, lib.playlist(id)?.folderId)
        assertTrue(lib.toggleLike(song(5)))
        assertFalse(lib.toggleLike(song(5)))
    }

    @Test
    fun `orden de la biblioteca`() {
        val lib = library()
        lib.createPlaylist("Zeta", listOf(song(1)))
        lib.createPlaylist("Álbum raro", listOf(song(2)))
        lib.setAlbumSaved(AlbumItem("yt:MPREb_b", "Bonito"), true)
        lib.setFollowing(ArtistItem("yt:UCx", "Mónica"), true)
        val byName = lib.entries(lib.current, LibrarySort.NAME).map { it.name }
        assertEquals(listOf("Álbum raro", "Bonito", "Mónica", "Zeta"), byName)
        // Lo último escuchado, arriba.
        lib.recordPlay(song(1), 60_000)
        val recent = lib.entries(lib.current, LibrarySort.RECENT).first()
        assertTrue(recent is LibraryEntry.Playlist && recent.playlist.name == "Zeta")
    }

    @Test
    fun `en colas enormes se guarda la parte de la que suena`() {
        assertEquals(0..499, savedRange(size = 500, index = 300))
        assertEquals(0..999, savedRange(size = 3_000, index = 0))
        assertEquals(1_400..2_399, savedRange(size = 3_000, index = 1_500))
        assertEquals(2_000..2_999, savedRange(size = 3_000, index = 2_999))
        assertTrue(2_999 in savedRange(size = 3_000, index = 2_999))
    }

    @Test
    fun `la biblioteca pasa entera de un PC a otro por la sincronizacion`() {
        val a = library()
        val folder = a.createFolder("Gimnasio")
        val id = a.createPlaylist("Correr", listOf(song(1), song(2)))
        a.movePlaylistToFolder(id, folder)
        a.setLiked(song(7), true)
        a.setAlbumSaved(AlbumItem("yt:MPREb_z", "MOTOMAMI"), true)
        val b = library()
        b.applySync(a.syncSnapshot())
        val essence = { lib: Library -> lib.syncSnapshot().associate { it.id to SyncEngine.essence(it) } }
        assertEquals(essence(a), essence(b))
        assertEquals("Gimnasio", b.current.folders.single().name)
        assertEquals(folder, b.playlist(id)?.folderId)
    }
}
