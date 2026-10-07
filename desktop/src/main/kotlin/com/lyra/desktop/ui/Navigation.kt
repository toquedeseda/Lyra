package com.lyra.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.BrowseEndpoint
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.Section
import java.util.concurrent.atomic.AtomicLong

/** Las pantallas de la parte central. */
sealed interface Screen {
    data object Home : Screen
    data class Search(val query: String = "", val tab: Int = 0) : Screen
    data class Album(val id: String, val preview: AlbumItem? = null) : Screen
    data class Artist(val id: String, val preview: ArtistItem? = null) : Screen
    data class RemotePlaylist(val id: String, val preview: PlaylistItem? = null) : Screen
    data class LocalPlaylist(val id: String) : Screen
    data class Folder(val id: String) : Screen
    data class SharedPlaylist(val url: String) : Screen
    data class Browse(val endpoint: BrowseEndpoint, val title: String?) : Screen
    data class AllOfSection(val section: Section) : Screen
    data object Liked : Screen
    data object Downloads : Screen
    data object History : Screen
    data object Settings : Screen
}

/** Atrás / adelante, como en un navegador (también con los botones laterales del ratón). */
class Navigator {
    data class Entry(val screen: Screen, val id: Long = ids.incrementAndGet())

    private val back = mutableStateListOf(Entry(Screen.Home))
    private val forward = mutableStateListOf<Entry>()

    /** Entradas que ya no se pueden volver a ver (para olvidar su estado guardado). */
    var dropped: List<Long> by mutableStateOf(emptyList())
        private set

    val current: Entry get() = back.last()
    val canGoBack: Boolean get() = back.size > 1
    val canGoForward: Boolean get() = forward.isNotEmpty()

    fun navigate(screen: Screen) {
        if (current.screen == screen) return
        back.add(Entry(screen))
        dropped = dropped + forward.map { it.id }
        forward.clear()
        // No se guarda una historia infinita.
        while (back.size > 60) dropped = dropped + back.removeAt(0).id
    }

    /** Cambia la pantalla actual sin apilar otra (p. ej. lo que se va escribiendo al buscar). */
    fun replace(screen: Screen) {
        val last = back.removeAt(back.lastIndex)
        back.add(last.copy(screen = screen))
    }

    fun goBack(): Boolean {
        if (back.size <= 1) return false
        forward.add(back.removeAt(back.lastIndex))
        return true
    }

    fun goForward(): Boolean {
        if (forward.isEmpty()) return false
        back.add(forward.removeAt(forward.lastIndex))
        return true
    }

    fun consumeDropped(): List<Long> {
        val list = dropped
        dropped = emptyList()
        return list
    }

    private companion object {
        val ids = AtomicLong(0)
    }
}
