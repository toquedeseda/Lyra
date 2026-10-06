package com.lyra.music

import com.lyra.music.data.db.FolderSummary
import com.lyra.music.data.db.PlaylistSummary
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.repo.LibraryStats
import com.lyra.music.data.repo.PlayStat
import com.lyra.music.data.settings.LibrarySort
import org.junit.Assert.assertEquals
import org.junit.Test

/** Ordenar la biblioteca: recientes, más escuchadas, nombre y añadidas. */
class LibraryOrderTest {

    private fun playlist(id: Long, name: String, updatedAt: Long, folderId: Long? = null) =
        PlaylistSummary(id, name, null, updatedAt, null, null, 10, folderId = folderId)

    private val playlists = listOf(
        playlist(1, "Zumba", updatedAt = 100),
        playlist(2, "ácido", updatedAt = 200),
        playlist(3, "Bachata", updatedAt = 300),
    )

    private val stats = LibraryStats(
        playlists = mapOf(
            1L to PlayStat(added = 10, lastPlayed = 5_000, plays = 3),
            2L to PlayStat(added = 30, lastPlayed = 0, plays = 0),
            3L to PlayStat(added = 20, lastPlayed = 1_000, plays = 40),
        ),
    )

    private fun names(list: List<PlaylistSummary>) = list.map { it.name }

    @Test
    fun `recientes pone arriba lo ultimo que escuchaste`() {
        assertEquals(listOf("Zumba", "Bachata", "ácido"), names(stats.playlists(playlists, LibrarySort.RECENT)))
    }

    @Test
    fun `recientes cuenta cuando cambias una playlist`() {
        val edited = playlists.map { if (it.id == 2L) it.copy(updatedAt = 9_000) else it }
        assertEquals("ácido", stats.playlists(edited, LibrarySort.RECENT).first().name)
    }

    @Test
    fun `mas escuchadas por numero de reproducciones`() {
        assertEquals(listOf("Bachata", "Zumba", "ácido"), names(stats.playlists(playlists, LibrarySort.PLAYS)))
    }

    @Test
    fun `por nombre ignora tildes y mayusculas`() {
        assertEquals(listOf("ácido", "Bachata", "Zumba"), names(stats.playlists(playlists, LibrarySort.NAME)))
    }

    @Test
    fun `anadidas por fecha de creacion`() {
        assertEquals(listOf("ácido", "Bachata", "Zumba"), names(stats.playlists(playlists, LibrarySort.ADDED)))
    }

    @Test
    fun `una carpeta cuenta como su playlist mas reciente`() {
        val inFolders = listOf(
            playlist(1, "Zumba", updatedAt = 100, folderId = 7),
            playlist(3, "Bachata", updatedAt = 300, folderId = 8),
        )
        val folders = listOf(FolderSummary(8, "Latino", 1), FolderSummary(7, "Gimnasio", 1))
        assertEquals(listOf("Gimnasio", "Latino"), stats.folders(folders, inFolders, LibrarySort.RECENT).map { it.name })
        assertEquals(listOf("Latino", "Gimnasio"), stats.folders(folders, inFolders, LibrarySort.PLAYS).map { it.name })
    }

    @Test
    fun `un album recien guardado sale arriba aunque aun no tenga datos`() {
        val albums = listOf(AlbumItem("a", "Viejo"), AlbumItem("b", "Nuevo"))
        val withOld = LibraryStats(albums = mapOf("a" to PlayStat(added = 50, lastPlayed = 60, plays = 2)))
        assertEquals(listOf("Nuevo", "Viejo"), withOld.albums(albums, LibrarySort.RECENT).map { it.title })
        assertEquals(listOf("Viejo", "Nuevo"), withOld.albums(albums, LibrarySort.PLAYS).map { it.title })
    }
}
