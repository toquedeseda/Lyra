package com.lyra.music.ui

import android.content.Context
import android.content.Intent
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.SnackbarResult
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import com.lyra.music.AppContainer
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.RadioItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.Source
import com.lyra.music.data.model.remoteId
import com.lyra.music.data.repo.DOWNLOADS_ID
import com.lyra.music.data.repo.LIKED_SONGS_ID
import com.lyra.music.data.repo.LinkTarget
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.ui.navigation.AlbumRoute
import com.lyra.music.ui.navigation.ArtistRoute
import com.lyra.music.ui.navigation.DownloadsRoute
import com.lyra.music.ui.navigation.LikedRoute
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.navigation.PlaylistRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/** Menú de una canción, con el contexto desde el que se abrió. */
data class SongMenuRequest(val song: Song, val localPlaylistId: Long? = null, val queueIndex: Int? = null)

/**
 * Acciones comunes a toda la interfaz (abrir, reproducir, menús…). Se pasa
 * por CompositionLocal para no arrastrar lambdas por todas las pantallas.
 */
@UnstableApi
@Stable
class LyraActions(
    val container: AppContainer,
    val nav: NavController,
    val snackbar: SnackbarHostState,
    private val scope: CoroutineScope,
    private val context: Context,
) {
    var songMenu by mutableStateOf<SongMenuRequest?>(null)
    var addToPlaylist by mutableStateOf<List<Song>?>(null)
    var nowPlayingOpen by mutableStateOf(false)
    var queueOpen by mutableStateOf(false)
    var lyricsOpen by mutableStateOf(false)

    private val player get() = container.player

    fun message(text: String, action: String? = null, onAction: () -> Unit = {}) {
        scope.launch {
            val result = snackbar.showSnackbar(text, actionLabel = action, duration = SnackbarDuration.Short)
            if (result == SnackbarResult.ActionPerformed) onAction()
        }
    }

    // ---------------------------------------------------------------- reproducir

    fun play(songs: List<Song>, index: Int = 0, from: MusicItem? = null, fromLabel: String? = null) {
        if (songs.isEmpty()) return
        from?.let(container.library::noteContext)
        player.play(songs, index, shuffle = false, from = fromLabel ?: from?.title)
    }

    fun shuffle(songs: List<Song>, from: MusicItem? = null, fromLabel: String? = null) {
        if (songs.isEmpty()) return
        from?.let(container.library::noteContext)
        player.play(songs, 0, shuffle = true, from = fromLabel ?: from?.title)
    }

    fun startRadio(song: Song) {
        player.startRadio(song)
    }

    fun playNext(songs: List<Song>) {
        player.playNext(songs)
        message(if (songs.size == 1) "Sonará a continuación" else "${songs.size} canciones a continuación")
    }

    fun addToQueue(songs: List<Song>) {
        player.addToQueue(songs)
        message(if (songs.size == 1) "Añadida a la cola" else "${songs.size} canciones añadidas a la cola")
    }

    // ---------------------------------------------------------------- abrir

    fun open(item: MusicItem) {
        when (item) {
            is Song -> startRadio(item)
            is AlbumItem -> nav.navigate(AlbumRoute(item.id))
            is ArtistItem -> nav.navigate(ArtistRoute(item.id))
            is PlaylistItem -> when {
                item.id == LIKED_SONGS_ID -> nav.navigate(LikedRoute)
                item.id == DOWNLOADS_ID -> nav.navigate(DownloadsRoute)
                item.id.startsWith("local:") -> item.id.removePrefix("local:").toLongOrNull()?.let { nav.navigate(LocalPlaylistRoute(it)) }
                else -> nav.navigate(PlaylistRoute(item.id))
            }
            is RadioItem -> {
                container.library.noteContext(item)
                player.startRadio(item.seed)
            }
        }
    }

    fun openArtist(id: String?) {
        if (id != null) nav.navigate(ArtistRoute(id)) else message("Este artista no tiene página")
    }

    fun openAlbum(id: String?) {
        if (id != null) nav.navigate(AlbumRoute(id)) else message("Esta canción no tiene álbum")
    }

    /** Enlace pegado o compartido desde otra app. */
    fun openLink(url: String) {
        scope.launch {
            val target = runCatching { container.music.resolveLink(url) }.getOrNull()
            when (target) {
                is LinkTarget.PlaySong -> startRadio(target.song)
                is LinkTarget.Open -> open(target.item)
                null -> message("No reconozco ese enlace")
            }
        }
    }

    // ---------------------------------------------------------------- biblioteca

    fun toggleLike(song: Song) {
        scope.launch {
            val liked = container.library.toggleLike(song)
            message(if (liked) "Añadida a Me gusta" else "Quitada de Me gusta")
        }
    }

    fun download(songs: List<Song>) {
        scope.launch {
            container.downloads.enqueue(songs)
            message(if (songs.size == 1) "Descargando «${songs.first().title}»" else "Descargando ${songs.size} canciones")
        }
    }

    fun removeDownload(song: Song) {
        scope.launch {
            container.downloads.remove(song.id)
            message("Descarga eliminada")
        }
    }

    fun addToPlaylist(playlistId: Long, songs: List<Song>, name: String) {
        scope.launch {
            container.library.addToPlaylist(playlistId, songs)
            message("Añadida a $name")
        }
    }

    fun createPlaylist(name: String, songs: List<Song>) {
        scope.launch {
            val id = container.library.createPlaylist(name, songs)
            message("Playlist creada", "Abrir") { nav.navigate(LocalPlaylistRoute(id)) }
        }
    }

    fun share(song: Song) {
        val url = when (song.source) {
            Source.YOUTUBE -> "https://music.youtube.com/watch?v=${song.id.remoteId()}"
            Source.SOUNDCLOUD -> NewPipeSource.soundCloudUrl(song.id)
        }
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "${song.title} · ${song.artistsText}\n$url")
        context.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun launch(block: suspend CoroutineScope.() -> Unit) {
        scope.launch(block = block)
    }
}

@UnstableApi
val LocalActions = staticCompositionLocalOf<LyraActions> { error("LyraActions no disponible") }

/** Estado compartido que muchas filas necesitan (se recoge una sola vez arriba del todo). */
data class LibraryUiState(
    val likedIds: Set<String> = emptySet(),
    val downloads: Map<String, com.lyra.music.data.db.DownloadEntity> = emptyMap(),
    val currentSongId: String? = null,
    val isPlaying: Boolean = false,
)

val LocalLibraryState = androidx.compose.runtime.compositionLocalOf { LibraryUiState() }
