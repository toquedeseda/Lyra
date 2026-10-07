package com.lyra.desktop.ui

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import com.lyra.desktop.AppContainer
import com.lyra.desktop.data.Library
import com.lyra.desktop.player.PlayContext
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.RadioItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.model.remoteId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.launch
import java.awt.Desktop
import java.awt.Toolkit
import java.awt.datatransfer.StringSelection
import java.io.File
import java.net.URI

/** Un aviso de abajo, con un botón opcional («Deshacer», «Abrir»…). */
data class UiMessage(val text: String, val action: String? = null, val onAction: (() -> Unit)? = null)

/** Petición de un cuadro con un campo de texto (crear o renombrar). */
data class TextRequest(
    val title: String,
    val initial: String,
    val placeholder: String,
    val confirm: String,
    val secondInitial: String? = null,
    val secondPlaceholder: String? = null,
    val onConfirm: (String, String?) -> Unit,
)

/** Petición de confirmación (borrar algo). */
data class ConfirmRequest(val title: String, val text: String, val confirm: String, val onConfirm: () -> Unit)

/**
 * Lo que pueden hacer todas las pantallas: abrir cosas, poner música, Me gusta, playlists,
 * descargas, enlaces… y los cuadros de diálogo y avisos comunes.
 */
class LyraActions(val app: AppContainer, val nav: Navigator) {
    val scope: CoroutineScope = app.scope

    private val _messages = MutableSharedFlow<UiMessage>(extraBufferCapacity = 8)
    val messages: SharedFlow<UiMessage> = _messages

    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var textRequest by mutableStateOf<TextRequest?>(null)
    var confirmRequest by mutableStateOf<ConfirmRequest?>(null)
    var spotifyImport by mutableStateOf<String?>(null)

    /** Pide que el buscador de arriba coja el foco (Ctrl+F). */
    var focusSearch by mutableStateOf(0)

    /** Pantalla completa (F11 o el botón de abajo a la derecha; Esc para salir). */
    var fullScreen by mutableStateOf(false)

    fun message(text: String, action: String? = null, onAction: (() -> Unit)? = null) {
        _messages.tryEmit(UiMessage(text, action, onAction))
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) = scope.launch(Dispatchers.Main, block = block)

    // ------------------------------------------------------------------ abrir y poner

    fun open(item: MusicItem) {
        when (item) {
            is Song -> app.player.startRadio(item)
            is AlbumItem -> nav.navigate(Screen.Album(item.id, item))
            is ArtistItem -> nav.navigate(Screen.Artist(item.id, item))
            is PlaylistItem -> when {
                item.id == Library.LIKED_ID -> nav.navigate(Screen.Liked)
                item.id == Library.DOWNLOADS_ID -> nav.navigate(Screen.Downloads)
                item.id.startsWith(Library.LOCAL_PREFIX) -> nav.navigate(Screen.LocalPlaylist(item.id.removePrefix(Library.LOCAL_PREFIX)))
                else -> nav.navigate(Screen.RemotePlaylist(item.id, item))
            }
            is RadioItem -> app.player.startRadio(item.seed, item.title)
        }
    }

    /** El botón de reproducir de una tarjeta: carga lo que haga falta y lo pone. */
    fun playItem(item: MusicItem) {
        when (item) {
            is Song -> app.player.startRadio(item)
            is RadioItem -> app.player.startRadio(item.seed, item.title)
            is PlaylistItem -> when {
                item.id == Library.LIKED_ID -> play(app.library.likedSongs(), 0, PlayContext("Canciones que te gustan", Library.LIKED_ID), item)
                item.id.startsWith(Library.LOCAL_PREFIX) -> {
                    val id = item.id.removePrefix(Library.LOCAL_PREFIX)
                    val playlist = app.library.playlist(id) ?: return
                    play(app.library.playlistSongs(id), 0, PlayContext(playlist.name, item.id), item)
                }
                else -> launch {
                    val page = runCatching { app.music.fullPlaylist(item.id) }.getOrElse {
                        message("No se pudo cargar la playlist")
                        return@launch
                    }
                    play(page.songs, 0, PlayContext(page.playlist.title, item.id), item)
                }
            }
            is AlbumItem -> launch {
                val page = runCatching { app.music.album(item.id) }.getOrElse {
                    message("No se pudo cargar el álbum")
                    return@launch
                }
                play(page.songs, 0, PlayContext(page.album.title, item.id), page.album)
            }
            is ArtistItem -> launch {
                val page = runCatching { app.music.artist(item.id) }.getOrElse {
                    message("No se pudo cargar el artista")
                    return@launch
                }
                play(page.topSongs, 0, PlayContext(page.artist.title, item.id), page.artist)
            }
        }
    }

    fun play(songs: List<Song>, index: Int, context: PlayContext? = null, from: MusicItem? = null) =
        app.player.play(songs, index, context, shuffle = false, from = from)

    fun shuffle(songs: List<Song>, context: PlayContext? = null, from: MusicItem? = null) =
        app.player.play(songs, 0, context, shuffle = true, from = from)

    fun toggleLike(song: Song) {
        val liked = app.library.toggleLike(song)
        message(if (liked) "Añadida a Canciones que te gustan" else "Quitada de Canciones que te gustan")
    }

    fun download(songs: List<Song>) {
        if (songs.isEmpty()) return
        app.downloads.download(songs)
        message(if (songs.size == 1) "Descargando «${songs.first().title}»" else "Descargando ${songs.size} canciones", "Ver") {
            nav.navigate(Screen.Downloads)
        }
    }

    fun goToArtist(song: Song) {
        val artist = song.artists.firstOrNull { it.id != null } ?: return
        nav.navigate(Screen.Artist(artist.id!!, ArtistItem(artist.id!!, artist.name)))
    }

    fun goToAlbum(song: Song) {
        val album = song.album?.takeIf { it.id != null } ?: return
        nav.navigate(Screen.Album(album.id!!, AlbumItem(album.id!!, album.title, song.artists, thumbnailUrl = song.thumbnailUrl)))
    }

    // ------------------------------------------------------------------ playlists

    fun createPlaylist(songs: List<Song> = emptyList(), openAfter: Boolean = true) {
        textRequest = TextRequest("Nueva playlist", "", "Nombre de la playlist", "Crear") { name, _ ->
            val id = app.library.createPlaylist(name, songs)
            if (songs.isNotEmpty()) message(if (songs.size == 1) "Añadida a «$name»" else "${songs.size} canciones añadidas a «$name»")
            if (openAfter) nav.navigate(Screen.LocalPlaylist(id))
        }
    }

    fun createFolder() {
        textRequest = TextRequest("Nueva carpeta", "", "Nombre de la carpeta", "Crear") { name, _ ->
            val id = app.library.createFolder(name)
            nav.navigate(Screen.Folder(id))
        }
    }

    fun addTo(playlistId: String, songs: List<Song>) {
        val playlist = app.library.playlist(playlistId) ?: return
        val added = app.library.addToPlaylist(playlistId, songs)
        message(
            when {
                added == 0 -> "Ya estaba en «${playlist.name}»"
                songs.size == 1 -> "Añadida a «${playlist.name}»"
                added == 1 -> "1 canción añadida a «${playlist.name}» (las demás ya estaban)"
                added < songs.size -> "$added canciones añadidas a «${playlist.name}» (las demás ya estaban)"
                else -> "$added canciones añadidas a «${playlist.name}»"
            },
            "Abrir",
        ) { nav.navigate(Screen.LocalPlaylist(playlistId)) }
    }

    fun renamePlaylist(id: String) {
        val playlist = app.library.playlist(id) ?: return
        textRequest = TextRequest("Editar playlist", playlist.name, "Nombre", "Guardar", playlist.description.orEmpty(), "Descripción (opcional)") { name, description ->
            app.library.renamePlaylist(id, name, description)
        }
    }

    fun deletePlaylist(id: String) {
        val playlist = app.library.playlist(id) ?: return
        confirmRequest = ConfirmRequest("¿Borrar «${playlist.name}»?", "Se quitará de tu biblioteca. Las canciones siguen en sus álbumes y en Me gusta.", "Borrar") {
            app.library.deletePlaylist(id)
            if ((nav.current.screen as? Screen.LocalPlaylist)?.id == id) nav.goBack()
            message("Playlist borrada")
        }
    }

    /** Guarda una playlist de YouTube Music o SoundCloud como copia propia. */
    fun saveRemotePlaylist(item: PlaylistItem, songs: List<Song>) {
        app.library.playlistFromRemote(item.id)?.let { existing ->
            message("Ya la tienes en tu biblioteca", "Abrir") { nav.navigate(Screen.LocalPlaylist(existing.id)) }
            return
        }
        val id = app.library.createPlaylist(item.title, songs, remoteId = item.id, coverUrl = item.thumbnailUrl)
        message("Guardada en tu biblioteca", "Abrir") { nav.navigate(Screen.LocalPlaylist(id)) }
    }

    /** Enlace para compartir una playlist (el mismo que hace el móvil: se abre en Lyra o en la web). */
    fun sharePlaylist(name: String, songs: List<Song>) {
        if (songs.isEmpty()) {
            message("La playlist está vacía")
            return
        }
        launch {
            val link = runCatching { app.sharing.linkFor(name, songs) }.getOrNull()
            if (link == null) {
                message("No se pudo crear el enlace")
            } else {
                copy(link)
                message("Enlace copiado: pégalo donde quieras")
            }
        }
    }

    // ------------------------------------------------------------------ enlaces y archivos

    fun songLink(song: Song): String = when (song.source) {
        Source.YOUTUBE -> "https://music.youtube.com/watch?v=" + song.id.remoteId()
        Source.SOUNDCLOUD -> "https://soundcloud.com/" + song.id.remoteId()
    }

    fun copy(text: String) {
        runCatching { Toolkit.getDefaultToolkit().systemClipboard.setContents(StringSelection(text), null) }
    }

    fun copyLink(song: Song) {
        copy(songLink(song))
        message("Enlace copiado")
    }

    fun openInBrowser(url: String) {
        runCatching { Desktop.getDesktop().browse(URI(url)) }
    }

    fun openFolder(folder: File) {
        runCatching {
            folder.mkdirs()
            Desktop.getDesktop().open(folder)
        }
    }

    /** Muestra el archivo seleccionado en el Explorador de Windows. */
    fun showInExplorer(file: File) {
        runCatching { ProcessBuilder("explorer.exe", "/select,", file.absolutePath).start() }
            .onFailure { openFolder(file.parentFile) }
    }
}

val LocalActions = staticCompositionLocalOf<LyraActions> { error("Sin LyraActions") }
