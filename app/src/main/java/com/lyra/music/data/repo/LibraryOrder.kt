package com.lyra.music.data.repo

import com.lyra.music.data.db.FolderSummary
import com.lyra.music.data.db.PlaylistSummary
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.settings.LibrarySort
import java.text.Collator
import java.util.Locale

/** Cuándo se guardó algo de la biblioteca, cuándo se escuchó por última vez y cuántas veces. */
data class PlayStat(val added: Long, val lastPlayed: Long = 0, val plays: Int = 0)

/** Lo necesario para ordenar una cosa de la biblioteca. */
data class SortKeys(val name: String, val added: Long, val recent: Long, val plays: Int)

/**
 * Lo escuchado de cada playlist, álbum y artista de la biblioteca (a partir de sus canciones).
 * Lo que no aparece aquí se acaba de guardar: va arriba en «Recientes» y «Añadidas».
 */
data class LibraryStats(
    val playlists: Map<Long, PlayStat> = emptyMap(),
    val albums: Map<String, PlayStat> = emptyMap(),
    val artists: Map<String, PlayStat> = emptyMap(),
) {
    /** «Recientes» también cuenta cuando la cambiaste (añadir o quitar canciones), como en Spotify. */
    private fun keys(p: PlaylistSummary): SortKeys {
        val stat = playlists[p.id]
        return SortKeys(p.name, stat?.added ?: p.updatedAt, maxOf(stat?.lastPlayed ?: 0, p.updatedAt), stat?.plays ?: 0)
    }

    fun playlists(list: List<PlaylistSummary>, sort: LibrarySort): List<PlaylistSummary> = list.sortedForLibrary(sort, ::keys)

    /** Una carpeta cuenta como su playlist más reciente y suma lo escuchado de todas. */
    fun folders(list: List<FolderSummary>, all: List<PlaylistSummary>, sort: LibrarySort): List<FolderSummary> {
        val inside = all.filter { it.folderId != null }.groupBy { it.folderId }
        return list.sortedForLibrary(sort) { folder ->
            val members = inside[folder.id].orEmpty().map(::keys)
            SortKeys(folder.name, folder.id, members.maxOfOrNull { it.recent } ?: 0, members.sumOf { it.plays })
        }
    }

    fun albums(list: List<AlbumItem>, sort: LibrarySort): List<AlbumItem> = list.sortedForLibrary(sort) { album ->
        val stat = albums[album.id]
        SortKeys(album.title, stat?.added ?: Long.MAX_VALUE, maxOf(stat?.lastPlayed ?: 0, stat?.added ?: Long.MAX_VALUE), stat?.plays ?: 0)
    }

    fun artists(list: List<ArtistItem>, sort: LibrarySort): List<ArtistItem> = list.sortedForLibrary(sort) { artist ->
        val stat = artists[artist.id]
        SortKeys(artist.title, stat?.added ?: Long.MAX_VALUE, maxOf(stat?.lastPlayed ?: 0, stat?.added ?: Long.MAX_VALUE), stat?.plays ?: 0)
    }
}

/** Ordena según lo elegido. Por nombre ignora mayúsculas y tildes («Álbum» va con la A). */
fun <T> List<T>.sortedForLibrary(sort: LibrarySort, keys: (T) -> SortKeys): List<T> {
    if (size < 2) return this
    val order: Comparator<SortKeys> = when (sort) {
        LibrarySort.RECENT -> compareByDescending<SortKeys> { it.recent }.thenByDescending { it.added }
        LibrarySort.PLAYS -> compareByDescending<SortKeys> { it.plays }.thenByDescending { it.recent }
        LibrarySort.ADDED -> compareByDescending { it.added }
        LibrarySort.NAME -> {
            val collator = Collator.getInstance(Locale.forLanguageTag("es")).apply { strength = Collator.PRIMARY }
            Comparator { a, b -> collator.compare(a.name.trim(), b.name.trim()) }
        }
    }
    return map { it to keys(it) }.sortedWith { a, b -> order.compare(a.second, b.second) }.map { it.first }
}
