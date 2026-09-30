package com.lyra.music.ui

import android.content.ClipData
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
import androidx.core.content.FileProvider
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavController
import androidx.navigation.NavGraph.Companion.findStartDestination
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
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.data.source.soundcloud.NewPipeSource
import com.lyra.music.ui.navigation.AlbumRoute
import com.lyra.music.ui.navigation.ArtistRoute
import com.lyra.music.ui.navigation.DownloadsRoute
import com.lyra.music.ui.navigation.LikedRoute
import com.lyra.music.ui.navigation.LocalPlaylistRoute
import com.lyra.music.ui.navigation.PlaylistRoute
import com.lyra.music.ui.navigation.SearchRoute
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
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

    /** Diálogo de importar de Spotify (null = cerrado; "" = abierto sin enlace). */
    var importDialog by mutableStateOf<String?>(null)

    /** Canción cuya tarjeta para compartir se está mostrando (null = ninguna). */
    var shareCard by mutableStateOf<Song?>(null)

    /** Búsqueda que la pestaña Buscar debe lanzar al abrirse (desde la biblioteca). */
    var pendingSearch by mutableStateOf<String?>(null)

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

    /** Botón de aleatorio del reproductor: apagado → aleatorio → inteligente → apagado. */
    fun cycleShuffle() {
        val shuffle = player.state.value.shuffle
        val smart = container.settings.current.smartShuffle
        scope.launch {
            when {
                !shuffle -> player.setShuffle(true)
                !smart -> {
                    container.settings.update { it.copy(smartShuffle = true) }
                    message("Aleatorio inteligente: se colarán canciones recomendadas")
                }
                else -> {
                    container.settings.update { it.copy(smartShuffle = false) }
                    player.setShuffle(false)
                }
            }
        }
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

    /** Botón de play de las tarjetas: reproduce el álbum, playlist o mix sin entrar. */
    fun playItem(item: MusicItem) {
        when (item) {
            is Song -> startRadio(item)
            is RadioItem -> open(item)
            is ArtistItem -> open(item)
            is AlbumItem, is PlaylistItem -> scope.launch {
                val songs = runCatching {
                    when {
                        item.id == LIKED_SONGS_ID -> container.library.likedList()
                        item.id == DOWNLOADS_ID -> container.downloads.downloads.first()
                            .filter { it.downloadState == com.lyra.music.data.db.DownloadState.COMPLETED }.map { it.toSong() }
                        item.id.startsWith("local:") ->
                            container.library.playlistSongList(item.id.removePrefix("local:").toLongOrNull() ?: -1)
                        item is AlbumItem -> container.music.album(item.id).songs
                        else -> container.music.playlist(item.id).songs
                    }
                }.getOrElse {
                    message("No se pudo cargar «${item.title}»")
                    return@launch
                }
                if (songs.isEmpty()) message("«${item.title}» está vacía") else play(songs, 0, from = item)
            }
        }
    }

    /** Abre la pestaña Buscar con [query] ya buscada en YouTube Music y SoundCloud. */
    fun searchOnline(query: String) {
        pendingSearch = query
        nav.navigate(SearchRoute) {
            popUpTo(nav.graph.findStartDestination().id) { saveState = true }
            launchSingleTop = true
            restoreState = true
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
        if (container.spotifyImport.isSpotifyLink(url)) {
            importDialog = url
            return
        }
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

    /** [collectionTitle]: nombre de la playlist o álbum (para crear su .m3u8 en Música/Lyra). */
    fun download(songs: List<Song>, collectionTitle: String? = null) {
        scope.launch {
            container.downloads.enqueue(songs, collectionTitle)
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

    fun linkFor(song: Song): String = when (song.source) {
        Source.YOUTUBE -> "https://music.youtube.com/watch?v=${song.id.remoteId()}"
        Source.SOUNDCLOUD -> NewPipeSource.soundCloudUrl(song.id)
    }

    fun share(song: Song) {
        val intent = Intent(Intent.ACTION_SEND).setType("text/plain")
            .putExtra(Intent.EXTRA_TEXT, "${song.title} · ${song.artistsText}\n${linkFor(song)}")
        context.startActivity(Intent.createChooser(intent, "Compartir").addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    /** Portada para dibujar la tarjeta: la descargada si la hay; si no, la de alta resolución. */
    fun coverModel(song: Song): Any? = container.downloads.localCover(song.id) ?: hiResArtwork(song.thumbnailUrl, 1080)

    /** Comparte la tarjeta (imagen) con el enlace en el texto. */
    fun shareImage(song: Song, file: java.io.File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", file)
        val intent = Intent(Intent.ACTION_SEND).setType("image/jpeg")
            .putExtra(Intent.EXTRA_STREAM, uri)
            .putExtra(Intent.EXTRA_TEXT, "${song.title} · ${song.artistsText}\n${linkFor(song)}")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        intent.clipData = ClipData.newRawUri(song.title, uri)
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
