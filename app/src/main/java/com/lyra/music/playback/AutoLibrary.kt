package com.lyra.music.playback

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaConstants
import com.lyra.music.LyraApp
import com.lyra.music.data.db.DownloadState
import com.lyra.music.data.db.PlaylistSummary
import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.MoodGroup
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.RadioItem
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.cleaned
import com.lyra.music.data.repo.DOWNLOADS_ID
import com.lyra.music.data.repo.LIKED_SONGS_ID
import com.lyra.music.data.repo.SearchTab
import com.lyra.music.data.source.innertube.hiResArtwork
import com.lyra.music.playback.MediaItems.toMediaItem
import com.lyra.music.ui.components.searchKey
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * Lyra en Android Auto, al estilo de Spotify: cuatro pestañas (Inicio, Recientes,
 * Biblioteca y Explorar) con portadas, listas con "Aleatorio" arriba, búsqueda por
 * secciones y voz ("Ok Google, pon … en Lyra").
 *
 * Ids:
 *  - pestañas: `tab:home`, `tab:recent`, `tab:library`, `tab:explore`
 *  - listas: `liked`, `downloads`, `playlist:<id>`, `album:<id>`, `remote:<id>`, `artist:<id>`,
 *    `folder:<id>`, `recent`, `top`, `home:<n>`, `search:<texto>`, `mood:<grupo>:<n>`
 *  - para tocar: `<lista>::<canción>`, `shuffle::<lista>`, `radio::<canción>`, `artistradio::<artista>`
 */
@UnstableApi
class AutoLibrary(private val context: Context) {

    /** Lo que hay que poner: canciones, por dónde empezar y de dónde salen. */
    data class Pick(
        val songs: List<Song>,
        val index: Int = 0,
        /** true: en aleatorio; false: en orden; null: como esté. */
        val shuffle: Boolean? = null,
        val label: String? = null,
        val contextId: String? = null,
        val positionMs: Long = C.TIME_UNSET,
        /** Para "Seguir escuchando" (accesos rápidos de la app y del coche). */
        val note: MusicItem? = null,
    )

    private val container get() = (context.applicationContext as LyraApp).container

    /** Canciones de cada lista ya cargadas (las de internet, durante un rato). */
    private val songCache = ConcurrentHashMap<String, Pair<Long, List<Song>>>()
    private val labels = ConcurrentHashMap<String, String>()
    private val contextItems = ConcurrentHashMap<String, MusicItem>()
    private val radioSeeds = ConcurrentHashMap<String, Song>()
    private val known = object : LinkedHashMap<String, MediaItem>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, MediaItem>?) = size > 2_000
    }
    @Volatile private var moods: List<MoodGroup> = emptyList()

    val root: MediaItem = browsable(ROOT, "Lyra")

    /** Cómo se ven las cosas por defecto (cada pestaña y lista lo ajusta para lo suyo). */
    val rootExtras: Bundle get() = style(browsable = GRID, playable = LIST)

    suspend fun children(parentId: String): List<MediaItem> = when {
        parentId == ROOT -> tabs()
        parentId == TAB_HOME -> home()
        parentId == TAB_RECENT -> recent()
        parentId == TAB_LIBRARY -> library()
        parentId == TAB_EXPLORE -> explore()
        parentId.startsWith(FOLDER) -> folder(parentId.removePrefix(FOLDER).toLongOrNull() ?: -1)
        parentId.startsWith(MOOD) && parentId.count { it == ':' } == 2 -> mood(parentId)
        else -> collection(parentId)
    }

    fun item(mediaId: String): MediaItem? = synchronized(known) { known[mediaId] }

    // ------------------------------------------------------------------ pestañas

    private fun tabs() = listOf(
        browsable(TAB_HOME, "Inicio", icon = "tab_home", extras = style(browsable = GRID, playable = LIST)),
        browsable(TAB_RECENT, "Recientes", icon = "tab_recent", extras = style(browsable = GRID, playable = LIST)),
        browsable(TAB_LIBRARY, "Biblioteca", icon = "tab_library", extras = style(browsable = LIST, playable = LIST)),
        browsable(TAB_EXPLORE, "Explorar", icon = "tab_explore", extras = style(browsable = GRID, playable = LIST)),
    )

    /** Inicio: seguir escuchando (con Me gusta delante), tus mixes y las secciones del Inicio de la app. */
    private suspend fun home(): List<MediaItem> {
        val c = container
        if (c.home.feed.value.sections.none { it.title != RECENT_SECTION }) {
            // Si la app aún no ha cargado el Inicio (p. ej. solo se usa en el coche), se pide ahora.
            withTimeoutOrNull(8_000) { runCatching { c.home.refresh() } }
        }
        val items = mutableListOf<MediaItem>()
        val recent = listOf<MusicItem>(PlaylistItem(LIKED_SONGS_ID, LIKED_TITLE)) + c.library.recentContexts.value
        recent.distinctBy { it.id }.take(8).forEach { node(it, group = "Seguir escuchando")?.let(items::add) }
        val sections = c.home.feed.value.sections
        sections.forEachIndexed { index, section ->
            if (items.size > 90 || section.items.isEmpty()) return@forEachIndexed
            val ctx = "$HOME$index"
            register(ctx, section.title, section.items.filterIsInstance<Song>())
            section.items.take(12).forEach { node(it, group = section.title, songContext = ctx)?.let(items::add) }
        }
        if (sections.none { it.title != RECENT_SECTION }) {
            // Sin Inicio (sin internet): lo que más escuchas.
            val top = runCatching { c.library.topSongs(30, 30) }.getOrDefault(emptyList())
            register(TOP, "Lo que más escuchas", top)
            top.forEach { items += playable(it, TOP, group = "Lo que más escuchas") }
        }
        return items
    }

    /** Recientes: las listas y álbumes que has puesto y las últimas canciones. */
    private suspend fun recent(): List<MediaItem> {
        val c = container
        val items = mutableListOf<MediaItem>()
        c.library.recentContexts.value.take(12).forEach { node(it, group = "Listas, álbumes y mixes")?.let(items::add) }
        val songs = runCatching { c.database.songs().recentlyPlayedList(50).map { it.toSong() } }.getOrDefault(emptyList())
        register(RECENT, RECENT_SECTION, songs)
        songs.forEach { items += playable(it, RECENT, group = "Canciones") }
        return items
    }

    /** Biblioteca: Me gusta, Descargas, carpetas, playlists, álbumes y artistas. */
    private suspend fun library(): List<MediaItem> {
        val c = container
        val items = mutableListOf<MediaItem>()
        items += browsable(LIKED, LIKED_TITLE, plural(c.library.likedIds.value.size, "canción", "canciones"), icon = "liked")
        val downloaded = runCatching { c.database.downloads().completed().size }.getOrDefault(0)
        items += browsable(DOWNLOADS, DOWNLOADS_TITLE, plural(downloaded, "canción", "canciones"), icon = "downloads")
        c.library.folders.first().forEach { folder ->
            val id = "$FOLDER${folder.id}"
            labels[id] = folder.name
            items += browsable(
                id, folder.name, "Carpeta · " + plural(folder.playlistCount, "playlist", "playlists"),
                artwork = folderArtwork(folder.id), icon = "folder", group = "Carpetas",
                extras = style(browsable = LIST, playable = LIST),
            )
        }
        c.library.playlistSummaries.first().filter { it.folderId == null }.forEach { items += playlistNode(it, "Playlists") }
        c.library.savedAlbums.first().forEach { node(it, group = "Álbumes")?.let(items::add) }
        c.library.followedArtists.first().forEach { node(it, group = "Artistas")?.let(items::add) }
        return items
    }

    /** Explorar: géneros y estados de ánimo de YouTube Music, en tarjetas de colores. */
    private suspend fun explore(): List<MediaItem> {
        val groups = runCatching { container.music.moods() }.getOrDefault(emptyList())
        if (groups.isNotEmpty()) moods = groups
        var tile = 0
        return moods.flatMapIndexed { g, group ->
            group.categories.mapIndexed { i, category ->
                browsable(
                    "$MOOD$g:$i", category.title,
                    artwork = ArtworkProvider.moodUri(context, category.title, tile++), group = group.title,
                    extras = style(browsable = GRID, playable = LIST),
                )
            }
        }
    }

    private suspend fun mood(id: String): List<MediaItem> {
        val parts = id.removePrefix(MOOD).split(':').map { it.toIntOrNull() ?: -1 }
        if (moods.isEmpty()) moods = runCatching { container.music.moods() }.getOrDefault(emptyList())
        val category = moods.getOrNull(parts.getOrElse(0) { -1 })?.categories?.getOrNull(parts.getOrElse(1) { -1 }) ?: return emptyList()
        val page = runCatching { container.music.browse(category.endpoint) }.getOrNull() ?: return emptyList()
        return page.sections.flatMapIndexed { s, section ->
            val ctx = "$id:$s"
            register(ctx, section.title, section.items.filterIsInstance<Song>())
            section.items.take(16).mapNotNull { node(it, group = section.title, songContext = ctx) }
        }
    }

    private suspend fun folder(id: Long): List<MediaItem> {
        val inside = container.library.playlistSummaries.first().filter { it.folderId == id }
        if (inside.isEmpty()) return emptyList()
        val shuffle = playableNode("$SHUFFLE$FOLDER$id", "Aleatorio", "Todas las canciones de la carpeta", artwork = iconUri("shuffle"))
        return listOf(shuffle) + inside.map { playlistNode(it, null) }
    }

    /** Una lista: "Aleatorio" arriba (y la radio, si es un artista) y sus canciones. */
    private suspend fun collection(id: String): List<MediaItem> {
        val songs = songsFor(id)
        if (songs.isEmpty()) return emptyList()
        val items = mutableListOf<MediaItem>()
        if (id.startsWith(ARTIST)) {
            val name = labels[id] ?: "este artista"
            items += playableNode("$ARTIST_RADIO${id.removePrefix(ARTIST)}", "Radio de $name", "Canciones parecidas, sin fin", artwork = iconUri("radio"))
        }
        items += playableNode("$SHUFFLE$id", "Aleatorio", plural(songs.size, "canción", "canciones"), artwork = iconUri("shuffle"))
        // El coche no necesita miles de filas; "Aleatorio" y tocar una canción ponen la lista entera.
        songs.take(MAX_ROWS).forEach { items += playable(it, id) }
        return items
    }

    // ------------------------------------------------------------------ canciones de cada lista

    suspend fun songsFor(contextId: String): List<Song> {
        val volatile = contextId.startsWith(HOME) || contextId.startsWith(MOOD) || contextId.startsWith(SEARCH)
        songCache[contextId]?.let { (time, songs) ->
            // Las del Inicio, Explorar y búsquedas se guardan al montarlas; las de internet duran un rato.
            if (volatile || System.currentTimeMillis() - time < CACHE_MS) return songs
        }
        val c = container
        val songs: List<Song> = runCatching {
            when {
                contextId == LIKED -> c.library.likedList()
                contextId == DOWNLOADS -> c.downloads.downloads.first().filter { it.downloadState == DownloadState.COMPLETED }.map { it.toSong() }
                contextId == RECENT -> c.database.songs().recentlyPlayedList(50).map { it.toSong() }
                contextId == TOP -> c.library.topSongs(30, 50)
                contextId.startsWith(PLAYLIST) -> c.library.playlistSongList(contextId.removePrefix(PLAYLIST).toLongOrNull() ?: -1)
                contextId.startsWith(FOLDER) -> {
                    val folderId = contextId.removePrefix(FOLDER).toLongOrNull() ?: -1
                    c.library.playlistSummaries.first().filter { it.folderId == folderId }
                        .flatMap { c.library.playlistSongList(it.id) }.distinctBy { it.id }
                }
                contextId.startsWith(ALBUM) -> c.music.album(contextId.removePrefix(ALBUM)).also {
                    labels[contextId] = it.album.title
                    contextItems.putIfAbsent(contextId, it.album)
                }.songs
                contextId.startsWith(REMOTE) -> c.music.playlist(contextId.removePrefix(REMOTE)).also {
                    labels[contextId] = it.playlist.title
                    contextItems.putIfAbsent(contextId, it.playlist)
                }.songs
                contextId.startsWith(ARTIST) -> artistSongs(contextId.removePrefix(ARTIST)).let { (artist, songs) ->
                    labels[contextId] = artist.title
                    contextItems.putIfAbsent(contextId, artist)
                    songs
                }
                contextId.startsWith(HOME) -> c.home.feed.value.sections
                    .getOrNull(contextId.removePrefix(HOME).toIntOrNull() ?: -1)?.items.orEmpty().filterIsInstance<Song>()
                contextId.startsWith(SEARCH) -> c.music.search(contextId.removePrefix(SEARCH), SearchTab.SONGS).items.filterIsInstance<Song>()
                else -> emptyList()
            }
        }.getOrDefault(emptyList())
        // Las locales cambian a menudo: solo se recuerdan las que vienen de internet.
        if (songs.isNotEmpty() && (contextId.startsWith(ALBUM) || contextId.startsWith(REMOTE) || contextId.startsWith(ARTIST) || volatile)) {
            remember(contextId, songs)
        }
        return songs
    }

    /** Canciones de un artista: las populares y, detrás, las de su lista completa de canciones (hasta 100). */
    private suspend fun artistSongs(artistId: String): Pair<ArtistItem, List<Song>> {
        val page = container.music.artist(artistId)
        val more = page.topSongsMore?.let { endpoint ->
            runCatching { container.music.playlist("yt:" + endpoint.browseId).songs }.getOrDefault(emptyList())
        }.orEmpty()
        return page.artist to (page.topSongs + more).distinctBy { it.id }.take(100)
    }

    private fun register(contextId: String, label: String, songs: List<Song>) {
        labels[contextId] = label
        if (songs.isNotEmpty()) remember(contextId, songs)
    }

    private fun remember(contextId: String, songs: List<Song>) {
        songCache[contextId] = System.currentTimeMillis() to songs
        if (songCache.size > 120) {
            songCache.entries.sortedBy { it.value.first }.take(40).forEach { songCache.remove(it.key) }
        }
    }

    /** Texto "Reproduciendo desde…" e id de la lista en la app, para lo que se pone desde el coche. */
    fun contextInfo(contextId: String): Pair<String?, String?> {
        val label = labels[contextId] ?: when {
            contextId == LIKED -> LIKED_TITLE
            contextId == DOWNLOADS -> DOWNLOADS_TITLE
            contextId == RECENT -> RECENT_SECTION
            contextId.startsWith(SEARCH) -> "Búsqueda: ${contextId.removePrefix(SEARCH)}"
            else -> null
        }
        val appId = when {
            contextId == LIKED -> LIKED_SONGS_ID
            contextId == DOWNLOADS -> DOWNLOADS_ID
            contextId.startsWith(PLAYLIST) -> "local:" + contextId.removePrefix(PLAYLIST)
            contextId.startsWith(ALBUM) -> contextId.removePrefix(ALBUM)
            contextId.startsWith(REMOTE) -> contextId.removePrefix(REMOTE)
            contextId.startsWith(ARTIST) -> contextId.removePrefix(ARTIST)
            contextId.startsWith(FOLDER) -> contextId
            else -> null
        }
        return label to appId
    }

    /** Lo que se apunta en "Seguir escuchando" al poner una lista desde el coche. */
    private fun noteFor(contextId: String): MusicItem? = when {
        contextId == LIKED -> PlaylistItem(LIKED_SONGS_ID, LIKED_TITLE)
        contextId == DOWNLOADS -> PlaylistItem(DOWNLOADS_ID, DOWNLOADS_TITLE)
        else -> contextItems[contextId]
    }

    private suspend fun pickFrom(contextId: String, songs: List<Song>, index: Int = 0, shuffle: Boolean? = null): Pick {
        if (labels[contextId] == null) rememberName(contextId)
        val (label, appId) = contextInfo(contextId)
        return Pick(songs, index, shuffle, label, appId, note = noteFor(contextId))
    }

    /** Nombre de una playlist o carpeta propia que el coche no ha mostrado aún (p. ej. tras reiniciarse la app). */
    private suspend fun rememberName(contextId: String) {
        val c = container
        when {
            contextId.startsWith(PLAYLIST) -> {
                val id = contextId.removePrefix(PLAYLIST).toLongOrNull() ?: return
                c.library.playlistSummaries.first().firstOrNull { it.id == id }?.let { playlistNode(it, null) }
            }
            contextId.startsWith(FOLDER) -> {
                val id = contextId.removePrefix(FOLDER).toLongOrNull() ?: return
                c.library.folders.first().firstOrNull { it.id == id }?.let { labels[contextId] = it.name }
            }
        }
    }

    // ------------------------------------------------------------------ al tocar algo

    fun isAutoId(mediaId: String) = mediaId.contains("::")

    /** Lo que suena al tocar un elemento del coche. */
    suspend fun pick(mediaId: String): Pick? {
        val head = mediaId.substringBefore("::")
        val tail = mediaId.substringAfter("::")
        return when ("$head::") {
            SHUFFLE -> {
                val songs = songsFor(tail)
                if (songs.isEmpty()) return null
                pickFrom(tail, songs, index = songs.indices.random(), shuffle = true)
            }
            RADIO -> {
                val seed = radioSeeds[tail] ?: container.database.songs().get(tail)?.toSong() ?: Song(tail, "")
                radio(seed, labels["$RADIO$tail"], note = contextItems["$RADIO$tail"])
            }
            ARTIST_RADIO -> artistRadio(tail)
            else -> {
                val songId = MediaItems.songIdOf(mediaId)
                val songs = songsFor(head)
                if (songs.isEmpty()) {
                    // La lista ya no está (p. ej. se cerró la app): suena al menos la canción.
                    val single = container.database.songs().get(songId)?.toSong() ?: return null
                    return Pick(listOf(single), label = single.title)
                }
                pickFrom(head, songs, index = songs.indexOfFirst { it.id == songId }.coerceAtLeast(0))
            }
        }
    }

    /** Radio a partir de una canción: la cola ya llena para que se vea en el coche. */
    private suspend fun radio(seed: Song, label: String?, note: MusicItem? = null): Pick? {
        val page = runCatching { container.music.radio(seed) }.getOrNull()
        val songs = page?.songs.orEmpty().filter { it.title.isNotBlank() }
        val queue = when {
            songs.isEmpty() && seed.title.isBlank() -> return null
            songs.isEmpty() -> listOf(seed)
            seed.title.isNotBlank() && songs.none { it.id == seed.id } -> listOf(seed) + songs
            else -> songs
        }
        val name = label ?: "Radio de ${seed.title.ifBlank { queue.first().title }}"
        return Pick(queue, shuffle = false, label = name, contextId = "radio:${seed.id}", note = note)
    }

    private suspend fun artistRadio(artistId: String): Pick? {
        val artist = runCatching { container.music.artist(artistId) }.getOrNull() ?: return null
        val label = "Radio de ${artist.artist.title}"
        val official = artist.radioPlaylistId?.let { id ->
            runCatching { container.music.radioFromPlaylist(id) }.getOrNull()?.songs?.filter { it.title.isNotBlank() }
        }
        if (!official.isNullOrEmpty()) {
            return Pick(official, shuffle = false, label = label, contextId = "radio:${artist.artist.id}", note = artist.artist)
        }
        val seed = artist.topSongs.firstOrNull() ?: return null
        return radio(seed, label, note = artist.artist)
    }

    /**
     * "Ok Google, pon … en Lyra": primero lo tuyo (Me gusta, descargas, tus playlists y
     * carpetas) y si no, lo mejor de YouTube Music (canción con su radio, artista, álbum o playlist).
     */
    suspend fun voice(query: String, extras: Bundle?): Pick? {
        val c = container
        val focus = extras?.getString(MediaStore.EXTRA_MEDIA_FOCUS)
        val key = voiceKey(query)
        val bare = stripFillers(key)

        if (key in LIKED_WORDS || bare in LIKED_WORDS) {
            val songs = c.library.likedList()
            if (songs.isNotEmpty()) return pickFrom(LIKED, songs, songs.indices.random(), shuffle = true)
        }
        if (key in DOWNLOAD_WORDS || bare in DOWNLOAD_WORDS) {
            val songs = songsFor(DOWNLOADS)
            if (songs.isNotEmpty()) return pickFrom(DOWNLOADS, songs, songs.indices.random(), shuffle = true)
        }

        // Tus playlists y carpetas por su nombre ("pon mi playlist Gym").
        val named = extras?.getString(EXTRA_PLAYLIST)?.let { stripFillers(voiceKey(it)) }?.takeIf { it.isNotBlank() }
        val wanted = setOfNotNull(named, bare.takeIf { it.isNotBlank() }, key.takeIf { it.isNotBlank() })
        if (wanted.isNotEmpty()) {
            val playlists = c.library.playlistSummaries.first()
            val playlist = playlists.firstOrNull { voiceKey(it.name) in wanted }
                ?: named?.takeIf { it.length >= 3 }?.let { name -> playlists.firstOrNull { voiceKey(it.name).contains(name) } }
            if (playlist != null) {
                val ctx = "$PLAYLIST${playlist.id}"
                labels[ctx] = playlist.name
                contextItems[ctx] = PlaylistItem("local:${playlist.id}", playlist.name, thumbnailUrl = playlist.customCover ?: playlist.coverUrl)
                val songs = c.library.playlistSongList(playlist.id)
                if (songs.isNotEmpty()) return pickFrom(ctx, songs, shuffle = false)
            }
            c.library.folders.first().firstOrNull { voiceKey(it.name) in wanted }?.let { folder ->
                val ctx = "$FOLDER${folder.id}"
                labels[ctx] = folder.name
                val songs = songsFor(ctx)
                if (songs.isNotEmpty()) return pickFrom(ctx, songs, songs.indices.random(), shuffle = true)
            }
        }

        // Lo mejor de YouTube Music.
        val artistHint = extras?.getString(MediaStore.EXTRA_MEDIA_ARTIST)
        val text = listOfNotNull(
            query.takeIf { it.isNotBlank() },
            artistHint?.takeIf { focus == MediaStore.Audio.Media.ENTRY_CONTENT_TYPE && !query.contains(it, ignoreCase = true) },
        ).joinToString(" ")
        if (text.isBlank()) return null
        val page = runCatching { c.music.search(text, SearchTab.ALL) }.getOrNull() ?: return null
        val candidates = listOfNotNull(page.topResult) + page.items
        val best: MusicItem = when (focus) {
            MediaStore.Audio.Artists.ENTRY_CONTENT_TYPE -> candidates.firstOrNull { it is ArtistItem }
            MediaStore.Audio.Albums.ENTRY_CONTENT_TYPE -> candidates.firstOrNull { it is AlbumItem }
            PLAYLIST_FOCUS -> candidates.firstOrNull { it is PlaylistItem }
            MediaStore.Audio.Media.ENTRY_CONTENT_TYPE -> candidates.firstOrNull { it is Song }
            else -> null
        } ?: candidates.firstOrNull() ?: return null

        return when (best) {
            is Song -> best.cleaned().let { radio(it, "Radio de ${it.title}") }
            is ArtistItem -> {
                val (artist, songs) = runCatching { artistSongs(best.id) }.getOrNull() ?: return null
                if (songs.isEmpty()) return artistRadio(best.id)
                Pick(songs, songs.indices.random(), shuffle = true, label = artist.title, contextId = best.id, note = artist)
            }
            is AlbumItem -> {
                val album = runCatching { c.music.album(best.id) }.getOrNull() ?: return null
                Pick(album.songs.ifEmpty { return null }, shuffle = false, label = album.album.title, contextId = best.id, note = album.album)
            }
            is PlaylistItem -> {
                val playlist = runCatching { c.music.playlist(best.id) }.getOrNull() ?: return null
                Pick(playlist.songs.ifEmpty { return null }, shuffle = false, label = playlist.playlist.title, contextId = best.id, note = playlist.playlist)
            }
            is RadioItem -> radio(best.seed, best.title, note = best)
        }
    }

    // ------------------------------------------------------------------ búsqueda en el coche

    /** Resultados por secciones, como en Spotify: lo tuyo, el mejor, canciones, artistas, álbumes y playlists. */
    suspend fun search(query: String): List<MediaItem> {
        val c = container
        val items = mutableListOf<MediaItem>()
        val key = searchKey(query).trim()
        if (key.isNotEmpty()) {
            c.library.playlistSummaries.first().filter { searchKey(it.name).contains(key) }.take(3)
                .forEach { items += playlistNode(it, "Tus playlists") }
        }

        val page = runCatching { c.music.search(query, SearchTab.ALL) }.getOrNull() ?: return items
        val ctx = "$SEARCH$query"
        val songs = page.items.filterIsInstance<Song>()
        register(ctx, "Búsqueda: $query", songs)
        val top = page.topResult
        top?.let { node(it, group = "Mejor resultado", songContext = ctx) }?.let(items::add)
        songs.filterNot { it.id == top?.id }.take(8).forEach { items += playable(it, ctx, group = "Canciones") }
        page.items.filterIsInstance<ArtistItem>().filterNot { it.id == top?.id }.take(3).forEach { node(it, group = "Artistas")?.let(items::add) }
        page.items.filterIsInstance<AlbumItem>().filterNot { it.id == top?.id }.take(4).forEach { node(it, group = "Álbumes")?.let(items::add) }
        page.items.filterIsInstance<PlaylistItem>().filterNot { it.id == top?.id }.take(4).forEach { node(it, group = "Playlists")?.let(items::add) }
        return items
    }

    // ------------------------------------------------------------------ elementos

    /** Canción que, al tocarla, pone toda su lista desde ella. */
    fun playable(song: Song, contextId: String, group: String? = null): MediaItem {
        val cover = container.downloads.localCover(song.id)
        val base = song.toMediaItem(cover)
        val artwork = cover?.let { ArtworkProvider.fileUri(context, it) }
            ?: hiResArtwork(song.thumbnailUrl, 480)?.let { ArtworkProvider.remoteUri(context, it) }
        val extras = Bundle(base.mediaMetadata.extras ?: Bundle()).apply {
            if (song.explicit) putLong(MediaConstants.EXTRAS_KEY_IS_EXPLICIT, MediaConstants.EXTRAS_VALUE_ATTRIBUTE_PRESENT)
            if (container.downloads.isDownloaded(song.id)) {
                putLong(MediaConstants.EXTRAS_KEY_DOWNLOAD_STATUS, MediaConstants.EXTRAS_VALUE_STATUS_DOWNLOADED)
            }
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
        }
        val id = "$contextId::${song.id}"
        return base.buildUpon()
            .setMediaId(id)
            .setMediaMetadata(
                base.mediaMetadata.buildUpon()
                    .setSubtitle(song.artistsText)
                    .setArtworkUri(artwork)
                    .setExtras(extras)
                    .build(),
            )
            .build()
            .also(::keep)
    }

    private suspend fun node(item: MusicItem, group: String? = null, songContext: String? = null): MediaItem? = when (item) {
        is Song -> playable(item, songContext ?: RECENT, group)
        is AlbumItem -> {
            val id = "$ALBUM${item.id}"
            labels[id] = item.title
            contextItems[id] = item
            browsable(id, item.title, item.artistsText.ifEmpty { item.kind ?: "Álbum" }, artwork = remoteArtwork(item.thumbnailUrl), group = group)
        }
        is ArtistItem -> {
            val id = "$ARTIST${item.id}"
            labels[id] = item.title
            contextItems[id] = item
            browsable(id, item.title, "Artista", artwork = remoteArtwork(item.thumbnailUrl), group = group)
        }
        is PlaylistItem -> when {
            item.id == LIKED_SONGS_ID ->
                browsable(LIKED, LIKED_TITLE, plural(container.library.likedIds.value.size, "canción", "canciones"), icon = "liked", group = group)
            item.id == DOWNLOADS_ID -> browsable(DOWNLOADS, DOWNLOADS_TITLE, null, icon = "downloads", group = group)
            item.id.startsWith("local:") -> {
                val id = item.id.removePrefix("local:").toLongOrNull()
                val summary = id?.let { wanted -> container.library.playlistSummaries.first().firstOrNull { it.id == wanted } }
                summary?.let { playlistNode(it, group) }
            }
            else -> {
                val id = "$REMOTE${item.id}"
                labels[id] = item.title
                contextItems[id] = item
                browsable(id, item.title, item.author ?: "Playlist", artwork = remoteArtwork(item.thumbnailUrl), group = group)
            }
        }
        is RadioItem -> {
            val id = "$RADIO${item.seed.id}"
            radioSeeds[item.seed.id] = item.seed
            labels[id] = item.title
            contextItems[id] = item
            playableNode(
                id, item.title, item.subtitle,
                artwork = remoteArtwork(item.thumbnailUrl ?: item.seed.thumbnailUrl) ?: iconUri("radio"),
                group = group, gridItem = true,
            )
        }
    }

    private suspend fun playlistNode(playlist: PlaylistSummary, group: String?): MediaItem {
        val id = "$PLAYLIST${playlist.id}"
        labels[id] = playlist.name
        contextItems[id] = PlaylistItem("local:${playlist.id}", playlist.name, thumbnailUrl = playlist.customCover ?: playlist.coverUrl)
        val artwork = playlist.customCover?.let(::remoteArtwork)
            ?: container.library.playlistCovers(playlist.id).first().firstOrNull()?.let(::remoteArtwork)
            ?: remoteArtwork(playlist.coverUrl)
        return browsable(
            id, playlist.name, "Playlist · " + plural(playlist.songCount, "canción", "canciones"),
            artwork = artwork, icon = "playlist", group = group,
        )
    }

    private suspend fun folderArtwork(folderId: Long): Uri? =
        container.library.folderCovers(folderId).first().firstOrNull()?.let(::remoteArtwork)

    private fun browsable(
        id: String,
        title: String,
        subtitle: String? = null,
        artwork: Uri? = null,
        icon: String? = null,
        group: String? = null,
        extras: Bundle? = null,
    ): MediaItem {
        val bundle = Bundle(extras ?: Bundle()).apply { group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) } }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork ?: icon?.let(::iconUri))
                    .setIsBrowsable(true)
                    .setIsPlayable(false)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_FOLDER_MIXED)
                    .setExtras(bundle)
                    .build(),
            )
            .build()
            .also(::keep)
    }

    private fun playableNode(id: String, title: String, subtitle: String?, artwork: Uri?, group: String? = null, gridItem: Boolean = false): MediaItem {
        val bundle = Bundle().apply {
            group?.let { putString(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_GROUP_TITLE, it) }
            if (gridItem) putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_SINGLE_ITEM, MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM)
        }
        return MediaItem.Builder()
            .setMediaId(id)
            .setMediaMetadata(
                MediaMetadata.Builder()
                    .setTitle(title)
                    .setSubtitle(subtitle)
                    .setArtworkUri(artwork)
                    .setIsBrowsable(false)
                    .setIsPlayable(true)
                    .setMediaType(MediaMetadata.MEDIA_TYPE_PLAYLIST)
                    .setExtras(bundle)
                    .build(),
            )
            .build()
            .also(::keep)
    }

    private fun keep(item: MediaItem) {
        synchronized(known) { known[item.mediaId] = item }
    }

    /** Carátula servida por [ArtworkProvider] (en grande si es de YouTube; las propias, desde su archivo). */
    private fun remoteArtwork(url: String?): Uri? {
        if (url.isNullOrBlank()) return null
        if (url.startsWith("file:")) return Uri.parse(url).path?.let { ArtworkProvider.fileUri(context, File(it)) }
        return ArtworkProvider.remoteUri(context, hiResArtwork(url, 480) ?: url)
    }

    private fun iconUri(name: String) = ArtworkProvider.iconUri(context, name)

    private fun style(browsable: Int? = null, playable: Int? = null) = Bundle().apply {
        browsable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_BROWSABLE, it) }
        playable?.let { putInt(MediaConstants.EXTRAS_KEY_CONTENT_STYLE_PLAYABLE, it) }
    }

    private fun plural(n: Int, one: String, many: String) = if (n == 1) "1 $one" else "$n $many"

    companion object {
        const val ROOT = "root"
        const val TAB_HOME = "tab:home"
        const val TAB_RECENT = "tab:recent"
        const val TAB_LIBRARY = "tab:library"
        const val TAB_EXPLORE = "tab:explore"

        const val LIKED = "liked"
        const val DOWNLOADS = "downloads"
        const val RECENT = "recent"
        const val TOP = "top"
        const val PLAYLIST = "playlist:"
        const val FOLDER = "folder:"
        const val ALBUM = "album:"
        const val REMOTE = "remote:"
        const val ARTIST = "artist:"
        const val HOME = "home:"
        const val MOOD = "mood:"
        const val SEARCH = "search:"

        const val SHUFFLE = "shuffle::"
        const val RADIO = "radio::"
        const val ARTIST_RADIO = "artistradio::"

        private const val LIKED_TITLE = "Canciones que te gustan"
        private const val DOWNLOADS_TITLE = "Descargas"
        private const val RECENT_SECTION = "Escuchado recientemente"

        private const val GRID = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_GRID_ITEM
        private const val LIST = MediaConstants.EXTRAS_VALUE_CONTENT_STYLE_LIST_ITEM

        private const val CACHE_MS = 15 * 60_000L
        private const val MAX_ROWS = 500

        // Lo que manda el Asistente al pedir una playlist (las constantes de MediaStore están obsoletas).
        private const val EXTRA_PLAYLIST = "android.intent.extra.playlist"
        private const val PLAYLIST_FOCUS = "vnd.android.cursor.item/playlist"

        /** Texto para comparar lo dicho con tus nombres: sin tildes, mayúsculas ni signos (Today’s = Today's). */
        internal fun voiceKey(text: String): String = searchKey(text).replace(NOT_WORD, " ").trim().replace(SPACES, " ")

        /** Quita "pon", "mi", "la playlist"… del principio (y "en lyra" del final) de lo que se ha dicho. */
        internal fun stripFillers(key: String): String {
            var text = key.trim().removeSuffix(" en lyra").trim()
            var changed = true
            while (changed) {
                changed = false
                for (filler in FILLERS) {
                    if (text.startsWith("$filler ")) {
                        text = text.removePrefix("$filler ").trim()
                        changed = true
                    }
                }
            }
            return text
        }

        private val NOT_WORD = Regex("[^\\p{L}\\p{N}]+")
        private val SPACES = Regex("\\s+")

        private val FILLERS = listOf(
            "pon", "ponme", "reproduce", "reproducir", "quiero escuchar", "escuchar", "mi", "mis", "la", "el", "los", "las",
            "lista", "playlist", "carpeta",
        )
        private val LIKED_WORDS = setOf(
            "me gusta", "me gustan", "canciones que me gustan", "canciones que te gustan", "favoritas", "favoritos",
            "canciones favoritas", "liked songs", "canciones", "musica que me gusta", "megusta",
        )
        private val DOWNLOAD_WORDS = setOf("descargas", "descargadas", "lo descargado", "canciones descargadas", "musica descargada")
    }
}
