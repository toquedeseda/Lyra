package com.lyra.music.data.source.innertube

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.AlbumPage
import com.lyra.music.data.model.AlbumRef
import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.ArtistPage
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.BrowseEndpoint
import com.lyra.music.data.model.BrowsePage
import com.lyra.music.data.model.Chip
import com.lyra.music.data.model.HomePage
import com.lyra.music.data.model.MoodCategory
import com.lyra.music.data.model.MoodGroup
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.PlaylistPage
import com.lyra.music.data.model.RadioPage
import com.lyra.music.data.model.SearchPage
import com.lyra.music.data.model.SearchSuggestions
import com.lyra.music.data.model.Section
import com.lyra.music.data.model.SectionStyle
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.cleaned
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject

/**
 * Convierte las respuestas de YouTube Music (cliente WEB_REMIX) en modelos de Lyra.
 * Probado contra respuestas reales guardadas en `src/test/resources/innertube`.
 */
object InnerTubeParser {

    private const val PAGE_ARTIST = "MUSIC_PAGE_TYPE_ARTIST"
    private const val PAGE_USER_CHANNEL = "MUSIC_PAGE_TYPE_USER_CHANNEL"
    private const val PAGE_ALBUM = "MUSIC_PAGE_TYPE_ALBUM"
    private const val PAGE_AUDIOBOOK = "MUSIC_PAGE_TYPE_AUDIOBOOK"
    private const val PAGE_PLAYLIST = "MUSIC_PAGE_TYPE_PLAYLIST"
    private const val TYPE_SONG = "MUSIC_VIDEO_TYPE_ATV"
    private const val TYPE_PODCAST = "MUSIC_VIDEO_TYPE_PODCAST_EPISODE"

    private val DURATION = Regex("^\\d{1,2}(:\\d{2}){1,2}$")
    private val YEAR = Regex("^\\d{4}$")
    private val STATS = Regex(
        "\\d.*(reproducc|visualizac|views|plays|usuarios|oyentes|listeners|suscript|subscri|canciones|songs|temas)",
        RegexOption.IGNORE_CASE,
    )
    private val TYPE_LABELS = setOf(
        "canción", "vídeo", "video", "álbum", "album", "single", "sencillo", "ep", "artista", "artist",
        "lista de reproducción", "playlist", "episodio", "episode", "pódcast", "podcast", "perfil", "profile",
        "song", "audiolibro", "audiobook",
    )
    private val ARTIST_JOINERS = Regex("\\s*(,|&| y | and | x )\\s*")
    private val SECTION_KEYS = setOf(
        "musicCarouselShelfRenderer",
        "musicImmersiveCarouselShelfRenderer",
        "musicShelfRenderer",
        "gridRenderer",
    )

    // ------------------------------------------------------------------ items

    fun parseDuration(text: String?): Long? {
        val clean = text?.trim() ?: return null
        if (!DURATION.matches(clean)) return null
        return clean.split(':').fold(0L) { acc, part -> acc * 60 + part.toLong() } * 1000
    }

    private fun Run.isArtistLink() = pageType == PAGE_ARTIST || pageType == PAGE_USER_CHANNEL

    private fun Run.toArtistRef() = ArtistRef(text.trim(), browseId?.let { "yt:$it" })

    private fun isNoiseGroup(group: List<Run>): Boolean {
        val text = group.joinText()
        return text.lowercase() in TYPE_LABELS || DURATION.matches(text) || YEAR.matches(text) ||
            STATS.containsMatchIn(text)
    }

    /** Artistas de una línea de subtítulo. Si no vienen enlazados, se deducen del texto. */
    private fun artistsFrom(runs: List<Run>): List<ArtistRef> {
        val linked = runs.filter { it.isArtistLink() }.map { it.toArtistRef() }.distinctBy { it.id ?: it.name }
        if (linked.isNotEmpty()) return linked
        val group = runs.groups().firstOrNull { !isNoiseGroup(it) && it.none { r -> r.pageType == PAGE_ALBUM } }
            ?: return emptyList()
        return group.joinText().split(ARTIST_JOINERS).map { it.trim() }.filter { it.isNotEmpty() }
            .map { ArtistRef(it) }
    }

    private fun albumFrom(runs: List<Run>): AlbumRef? =
        runs.firstOrNull { it.pageType == PAGE_ALBUM && it.browseId != null }
            ?.let { AlbumRef(it.text.trim(), "yt:${it.browseId}") }

    private fun isExplicit(renderer: JsonObject): Boolean =
        renderer.findAll("musicInlineBadgeRenderer").any {
            it.atStr("icon", "iconType") == "MUSIC_EXPLICIT_BADGE"
        }

    private fun flexColumns(renderer: JsonObject): List<List<Run>> =
        renderer.arr("flexColumns")?.map {
            it.obj("musicResponsiveListItemFlexColumnRenderer").obj("text").runs()
        } ?: emptyList()

    private fun fixedColumns(renderer: JsonObject): List<Run> =
        renderer.arr("fixedColumns")?.flatMap {
            it.obj("musicResponsiveListItemFixedColumnRenderer").obj("text").runs()
        } ?: emptyList()

    /** Fila de lista (búsqueda, álbum, playlist, inicio…) que sea una canción o vídeo. */
    fun songFromListItem(
        renderer: JsonObject,
        fallbackAlbum: AlbumRef? = null,
        fallbackArtists: List<ArtistRef> = emptyList(),
        fallbackThumbnail: String? = null,
    ): Song? {
        val flex = flexColumns(renderer)
        val titleRuns = flex.firstOrNull() ?: return null
        val playEndpoint = renderer.at(
            "overlay", "musicItemThumbnailOverlayRenderer", "content",
            "musicPlayButtonRenderer", "playNavigationEndpoint", "watchEndpoint",
        )
        val videoId = renderer.atStr("playlistItemData", "videoId")
            ?: titleRuns.firstNotNullOfOrNull { it.videoId }
            ?: playEndpoint.str("videoId")
            ?: return null
        val videoType = titleRuns.firstNotNullOfOrNull { it.videoType }
            ?: playEndpoint.atStr("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType")
        if (videoType == TYPE_PODCAST) return null

        val detail = flex.drop(1).flatMapIndexed { index, runs -> if (index == 0) runs else listOf(Run(" • ")) + runs }
        val title = titleRuns.joinText().ifEmpty { return null }
        return Song(
            id = "yt:$videoId",
            title = title,
            artists = artistsFrom(detail).ifEmpty { fallbackArtists },
            album = albumFrom(detail) ?: fallbackAlbum,
            durationMs = (fixedColumns(renderer) + detail).firstNotNullOfOrNull { parseDuration(it.text) },
            thumbnailUrl = renderer.obj("thumbnail").bestThumbnail() ?: fallbackThumbnail,
            isVideo = videoType != null && videoType != TYPE_SONG,
            explicit = isExplicit(renderer),
        ).cleaned()
    }

    /** Fila de lista que sea un álbum, artista o playlist. */
    fun itemFromListItem(renderer: JsonObject): MusicItem? {
        songFromListItem(renderer)?.let { return it }
        val flex = flexColumns(renderer)
        val title = flex.firstOrNull()?.joinText()?.takeIf { it.isNotEmpty() } ?: return null
        val detail = flex.drop(1).flatMapIndexed { index, runs -> if (index == 0) runs else listOf(Run(" • ")) + runs }
        val browse = renderer.obj("navigationEndpoint").obj("browseEndpoint") ?: return null
        val browseId = browse.str("browseId") ?: return null
        val pageType = browse.atStr(
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
        )
        val thumbnail = renderer.obj("thumbnail").bestThumbnail()
        val groups = detail.groups()
        return when {
            pageType == PAGE_ALBUM || pageType == PAGE_AUDIOBOOK || browseId.startsWith("MPREb") -> AlbumItem(
                id = "yt:$browseId",
                title = title,
                artists = artistsFrom(detail),
                year = groups.map { it.joinText() }.firstOrNull { YEAR.matches(it) },
                thumbnailUrl = thumbnail,
                kind = groups.firstOrNull()?.joinText()?.takeIf { it.lowercase() in TYPE_LABELS },
            )
            pageType == PAGE_ARTIST -> ArtistItem(
                id = "yt:$browseId",
                title = title,
                thumbnailUrl = thumbnail,
                subtitle = groups.map { it.joinText() }.filter { it.lowercase() !in TYPE_LABELS }
                    .joinToString(" • ").ifEmpty { null },
            )
            pageType == PAGE_PLAYLIST || browseId.startsWith("VL") -> PlaylistItem(
                id = "yt:$browseId",
                title = title,
                author = groups.firstOrNull { g -> g.any { it.isArtistLink() } }?.joinText()
                    ?: groups.map { it.joinText() }.firstOrNull { !it.isTypeLabel() && !STATS.containsMatchIn(it) },
                thumbnailUrl = thumbnail,
                songCountText = groups.map { it.joinText() }.firstOrNull { STATS.containsMatchIn(it) },
            )
            else -> null
        }
    }

    private fun String.isTypeLabel() = lowercase() in TYPE_LABELS

    /** Tarjeta de carrusel (musicTwoRowItemRenderer). */
    fun itemFromTwoRow(renderer: JsonObject): MusicItem? {
        val titleRuns = renderer.obj("title").runs()
        val title = titleRuns.joinText().ifEmpty { return null }
        val subtitle = renderer.obj("subtitle").runs()
        val groups = subtitle.groups()
        val nav = renderer.obj("navigationEndpoint")
        val thumbnail = renderer.obj("thumbnailRenderer").bestThumbnail()

        nav.obj("watchEndpoint")?.let { watch ->
            val videoId = watch.str("videoId") ?: return null
            val videoType = watch.atStr("watchEndpointMusicSupportedConfigs", "watchEndpointMusicConfig", "musicVideoType")
            if (videoType == TYPE_PODCAST) return null
            return Song(
                id = "yt:$videoId",
                title = title,
                artists = artistsFrom(subtitle),
                album = albumFrom(subtitle),
                thumbnailUrl = thumbnail,
                isVideo = videoType != null && videoType != TYPE_SONG,
                explicit = isExplicit(renderer),
            ).cleaned()
        }
        nav.obj("watchPlaylistEndpoint")?.let { watch ->
            val playlistId = watch.str("playlistId") ?: return null
            return PlaylistItem("yt:VL$playlistId", title, groups.lastOrNull()?.joinText(), thumbnail)
        }
        val browse = nav.obj("browseEndpoint") ?: return null
        val browseId = browse.str("browseId") ?: return null
        val pageType = browse.atStr(
            "browseEndpointContextSupportedConfigs", "browseEndpointContextMusicConfig", "pageType",
        )
        return when {
            pageType == PAGE_ALBUM || pageType == PAGE_AUDIOBOOK || browseId.startsWith("MPREb") -> AlbumItem(
                id = "yt:$browseId",
                title = title,
                artists = subtitle.filter { it.isArtistLink() }.map { it.toArtistRef() },
                year = groups.map { it.joinText() }.firstOrNull { YEAR.matches(it) },
                thumbnailUrl = thumbnail,
                kind = groups.firstOrNull()?.joinText()?.takeIf { it.isTypeLabel() },
            )
            pageType == PAGE_ARTIST -> ArtistItem("yt:$browseId", title, thumbnail, subtitle.joinText().ifEmpty { null })
            pageType == PAGE_PLAYLIST || browseId.startsWith("VL") -> PlaylistItem(
                id = "yt:$browseId",
                title = title,
                author = groups.map { it.joinText() }.lastOrNull { !it.isTypeLabel() && !STATS.containsMatchIn(it) },
                thumbnailUrl = thumbnail,
                songCountText = groups.map { it.joinText() }.firstOrNull { STATS.containsMatchIn(it) },
            )
            else -> null
        }
    }

    /** Elemento de la cola de radio (next). */
    fun songFromPanelVideo(renderer: JsonObject): Song? {
        val videoId = renderer.str("videoId") ?: return null
        val byline = renderer.obj("longBylineText").runs()
        val videoType = renderer.atStr(
            "navigationEndpoint", "watchEndpoint", "watchEndpointMusicSupportedConfigs",
            "watchEndpointMusicConfig", "musicVideoType",
        )
        if (videoType == TYPE_PODCAST) return null
        return Song(
            id = "yt:$videoId",
            title = renderer.obj("title").runs().joinText().ifEmpty { return null },
            artists = artistsFrom(byline),
            album = albumFrom(byline),
            durationMs = parseDuration(renderer.obj("lengthText").runs().joinText()),
            thumbnailUrl = renderer.obj("thumbnail").bestThumbnail(),
            isVideo = videoType != null && videoType != TYPE_SONG,
            explicit = isExplicit(renderer),
        ).cleaned()
    }

    private fun itemFromAny(entry: JsonElement): MusicItem? {
        (entry as? JsonObject)?.obj("musicTwoRowItemRenderer")?.let { return itemFromTwoRow(it) }
        (entry as? JsonObject)?.obj("musicResponsiveListItemRenderer")?.let { return itemFromListItem(it) }
        return null
    }

    // --------------------------------------------------------------- secciones

    private fun browseEndpointOf(element: JsonElement?): BrowseEndpoint? {
        val browse = element?.findFirst("browseEndpoint") ?: return null
        val id = browse.str("browseId") ?: return null
        return BrowseEndpoint(id, browse.str("params"))
    }

    private fun sectionFrom(key: String, shelf: JsonObject): Section? {
        val items: List<MusicItem>
        val title: String
        var subtitle: String? = null
        var more: BrowseEndpoint? = null
        when (key) {
            "musicCarouselShelfRenderer", "musicImmersiveCarouselShelfRenderer" -> {
                val header = shelf.obj("header")?.let {
                    it.obj("musicCarouselShelfBasicHeaderRenderer")
                        ?: it.obj("musicImmersiveCarouselShelfBasicHeaderRenderer")
                }
                title = header.obj("title").runs().joinText()
                subtitle = header.obj("strapline").runs().joinText().ifEmpty { null }
                more = browseEndpointOf(header.obj("moreContentButton"))
                    ?: header.obj("title").runs().firstOrNull { it.browseId != null }
                        ?.let { BrowseEndpoint(it.browseId!!) }
                items = shelf.arr("contents")?.mapNotNull(::itemFromAny) ?: emptyList()
            }
            "musicShelfRenderer" -> {
                title = shelf.obj("title").runs().joinText()
                more = browseEndpointOf(shelf.obj("bottomEndpoint"))
                items = shelf.arr("contents")?.mapNotNull(::itemFromAny) ?: emptyList()
            }
            "gridRenderer" -> {
                title = shelf.at("header", "gridHeaderRenderer", "title").runs().joinText()
                items = shelf.arr("items")?.mapNotNull(::itemFromAny) ?: emptyList()
            }
            else -> return null
        }
        if (items.isEmpty()) return null
        val style = when {
            key == "musicShelfRenderer" -> SectionStyle.LIST
            items.all { it is Song } && shelf.arr("contents")?.firstOrNull()
                ?.let { (it as? JsonObject)?.containsKey("musicResponsiveListItemRenderer") } == true -> SectionStyle.SONG_GRID
            else -> SectionStyle.CAROUSEL
        }
        return Section(title.ifEmpty { "" }, items, subtitle, more, style)
    }

    /** Todas las secciones (carruseles, estanterías, rejillas) en orden de aparición. */
    fun sections(root: JsonElement, skip: Set<String> = emptySet()): List<Section> {
        val out = mutableListOf<Section>()
        fun walk(node: JsonElement) {
            when (node) {
                is JsonObject -> for ((k, v) in node) {
                    if (k in skip) continue
                    if (k in SECTION_KEYS && v is JsonObject) {
                        sectionFrom(k, v)?.let(out::add)
                    } else {
                        walk(v)
                    }
                }
                is JsonArray -> node.forEach(::walk)
                else -> Unit
            }
        }
        walk(root)
        return out
    }

    fun continuationToken(root: JsonElement): String? =
        root.findFirst("nextContinuationData")?.str("continuation")
            ?: root.findFirst("nextRadioContinuationData")?.str("continuation")
            ?: root.findFirst("continuationCommand")?.str("token")

    // ---------------------------------------------------------------- páginas

    fun home(root: JsonObject): HomePage {
        val chips = root.findAll("chipCloudChipRenderer").mapNotNull { chip ->
            val title = chip.obj("text").runs().joinText()
            val endpoint = browseEndpointOf(chip.obj("navigationEndpoint")) ?: return@mapNotNull null
            Chip(title, endpoint)
        }
        return HomePage(sections(root), chips, continuationToken(root))
    }

    fun browse(root: JsonObject): BrowsePage {
        val title = root.at("header").let { header ->
            header?.findFirst("title")?.runs()?.joinText()
        }
        return BrowsePage(title?.ifEmpty { null }, sections(root))
    }

    fun moods(root: JsonObject): List<MoodGroup> {
        val groups = root.findAll("gridRenderer").mapNotNull { grid ->
            val title = grid.at("header", "gridHeaderRenderer", "title").runs().joinText()
            val categories = grid.findAll("musicNavigationButtonRenderer").mapNotNull(::moodFrom)
            if (categories.isEmpty()) null else MoodGroup(title, categories)
        }
        if (groups.isNotEmpty()) return groups
        val loose = root.findAll("musicNavigationButtonRenderer").mapNotNull(::moodFrom)
        return if (loose.isEmpty()) emptyList() else listOf(MoodGroup("", loose))
    }

    private fun moodFrom(button: JsonObject): MoodCategory? {
        val title = button.obj("buttonText").runs().joinText().ifEmpty { return null }
        val endpoint = browseEndpointOf(button.obj("clickCommand")) ?: return null
        return MoodCategory(title, endpoint)
    }

    fun search(root: JsonObject): SearchPage {
        val card = root.findFirst("musicCardShelfRenderer")
        val top: MusicItem? = card?.let(::topResultFrom)
        // Las canciones de la tarjeta no repiten el artista: va implícito en la propia tarjeta.
        val cardArtists = (top as? ArtistItem)?.let { listOf(ArtistRef(it.title, it.id)) } ?: emptyList()
        val cardSongs = card?.arr("contents")?.mapNotNull {
            (it as? JsonObject)?.obj("musicResponsiveListItemRenderer")?.let { row -> songFromListItem(row, fallbackArtists = cardArtists) }
        } ?: emptyList()
        val items = root.findAll("musicResponsiveListItemRenderer", skip = setOf("musicCardShelfRenderer"))
            .mapNotNull(::itemFromListItem)
        val all = (cardSongs + items).distinctBy { it.id }.filterNot { it.id == top?.id }
        return SearchPage(all, continuationToken(root), top)
    }

    private fun topResultFrom(card: JsonObject): MusicItem? {
        val titleRuns = card.obj("title").runs()
        val subtitle = card.obj("subtitle").runs()
        val thumbnail = card.obj("thumbnail").bestThumbnail()
        val first = titleRuns.firstOrNull() ?: return null
        val title = titleRuns.joinText()
        return when {
            first.videoId != null -> Song(
                id = "yt:${first.videoId}",
                title = title,
                artists = artistsFrom(subtitle),
                album = albumFrom(subtitle),
                durationMs = subtitle.firstNotNullOfOrNull { parseDuration(it.text) },
                thumbnailUrl = thumbnail,
                isVideo = first.videoType != null && first.videoType != TYPE_SONG,
            ).cleaned()
            first.pageType == PAGE_ARTIST && first.browseId != null ->
                ArtistItem("yt:${first.browseId}", title, thumbnail,
                    subtitle.groups().drop(1).joinToString(" • ") { it.joinText() }.ifEmpty { null })
            first.pageType == PAGE_ALBUM && first.browseId != null -> AlbumItem(
                id = "yt:${first.browseId}",
                title = title,
                artists = artistsFrom(subtitle),
                year = subtitle.map { it.text.trim() }.firstOrNull { YEAR.matches(it) },
                thumbnailUrl = thumbnail,
                kind = subtitle.groups().firstOrNull()?.joinText(),
            )
            first.pageType == PAGE_PLAYLIST && first.browseId != null ->
                PlaylistItem("yt:${first.browseId}", title, subtitle.groups().lastOrNull()?.joinText(), thumbnail)
            else -> null
        }
    }

    fun searchContinuation(root: JsonObject): SearchPage {
        val items = root.findAll("musicResponsiveListItemRenderer").mapNotNull(::itemFromListItem)
        return SearchPage(items, continuationToken(root))
    }

    fun suggestions(root: JsonObject): SearchSuggestions {
        val queries = root.findAll("searchSuggestionRenderer").map { it.obj("suggestion").runs().joinText() }
            .filter { it.isNotBlank() }
        val items = root.findAll("musicResponsiveListItemRenderer").mapNotNull(::itemFromListItem)
        return SearchSuggestions(queries, items)
    }

    fun album(root: JsonObject, browseId: String): AlbumPage {
        val header = root.findFirst("musicResponsiveHeaderRenderer")
            ?: root.findFirst("musicDetailHeaderRenderer")
        val title = header.obj("title").runs().joinText()
        val subtitleRuns = header.obj("subtitle").runs()
        val artists = header.obj("straplineTextOne").runs().filter { it.isArtistLink() }.map { it.toArtistRef() }
            .ifEmpty { artistsFrom(subtitleRuns) }
        val thumbnail = header.obj("thumbnail").bestThumbnail()
            ?: root.obj("background").bestThumbnail()
        val album = AlbumItem(
            id = "yt:$browseId",
            title = title,
            artists = artists,
            year = subtitleRuns.map { it.text.trim() }.firstOrNull { YEAR.matches(it) },
            thumbnailUrl = thumbnail,
            kind = subtitleRuns.groups().firstOrNull()?.joinText(),
        )
        val albumRef = AlbumRef(title, album.id)
        val shelf = root.findFirst("musicShelfRenderer") ?: root.findFirst("musicPlaylistShelfRenderer")
        val songs = shelf?.findAll("musicResponsiveListItemRenderer")?.mapNotNull {
            songFromListItem(it, fallbackAlbum = albumRef, fallbackArtists = artists, fallbackThumbnail = thumbnail)
        }?.map { song ->
            // Las pistas de un álbum son canciones aunque enlacen al videoclip oficial.
            song.copy(thumbnailUrl = song.thumbnailUrl ?: thumbnail, isVideo = false)
        } ?: emptyList()
        return AlbumPage(
            album = album,
            songs = songs,
            subtitle = header.obj("secondSubtitle").runs().joinText().ifEmpty { null },
            description = header?.findFirst("musicDescriptionShelfRenderer")?.obj("description")?.runs()?.joinText(),
            sections = sections(root, skip = setOf("musicShelfRenderer")).filter { it.items.none { item -> item is Song } },
        )
    }

    fun artist(root: JsonObject, browseId: String): ArtistPage {
        val header = root.obj("header")?.let {
            it.obj("musicImmersiveHeaderRenderer") ?: it.obj("musicVisualHeaderRenderer")
                ?: it.obj("musicHeaderRenderer")
        } ?: root.findFirst("musicImmersiveHeaderRenderer")
        val name = header.obj("title").runs().joinText()
        val banner = header.obj("thumbnail").bestThumbnail()
            ?: header.obj("foregroundThumbnail").bestThumbnail()
        val radio = header?.let {
            it.obj("startRadioButton")?.findFirst("watchPlaylistEndpoint")?.str("playlistId")
                ?: it.findFirst("watchPlaylistEndpoint")?.str("playlistId")
        }
        val sections = sections(root)
        val topShelf = sections.firstOrNull { it.style == SectionStyle.LIST && it.items.any { item -> item is Song } }
        val topSongs = topShelf?.items?.filterIsInstance<Song>()?.map { song ->
            if (song.artists.isEmpty()) song.copy(artists = listOf(ArtistRef(name, "yt:$browseId"))) else song
        } ?: emptyList()
        return ArtistPage(
            artist = ArtistItem("yt:$browseId", name, banner?.let(::squareArtistThumb)),
            bannerUrl = banner,
            description = header.obj("description").runs().joinText().ifEmpty { null },
            listeners = header.obj("monthlyListenerCount").runs().joinText().ifEmpty { null },
            topSongs = topSongs,
            topSongsMore = topShelf?.more,
            radioPlaylistId = radio,
            sections = sections.filter { it !== topShelf },
        )
    }

    /** El banner del artista es panorámico; para la miniatura pedimos un recorte cuadrado. */
    private fun squareArtistThumb(url: String): String =
        url.replace(Regex("=w\\d+-h\\d+[^/]*$"), "=w544-h544-p-l90-rj")

    fun playlist(root: JsonObject, browseId: String): PlaylistPage {
        val header = root.findFirst("musicResponsiveHeaderRenderer")
            ?: root.findFirst("musicDetailHeaderRenderer")
            ?: root.findFirst("musicEditablePlaylistDetailHeaderRenderer")?.findFirst("musicDetailHeaderRenderer")
        val title = header.obj("title").runs().joinText()
        val author = header.obj("straplineTextOne").runs().joinText().ifEmpty {
            header.obj("subtitle").runs().groups().getOrNull(1)?.joinText() ?: ""
        }
        val thumbnail = header.obj("thumbnail").bestThumbnail() ?: root.obj("background").bestThumbnail()
        val shelf = root.findFirst("musicPlaylistShelfRenderer") ?: root.findFirst("musicShelfRenderer")
        val songs = shelf?.findAll("musicResponsiveListItemRenderer")?.mapNotNull { songFromListItem(it) } ?: emptyList()
        return PlaylistPage(
            playlist = PlaylistItem("yt:$browseId", title, author.ifEmpty { null }, thumbnail),
            songs = songs,
            continuation = continuationToken(root),
            subtitle = header.obj("secondSubtitle").runs().joinText().ifEmpty { null },
            description = header?.findFirst("musicDescriptionShelfRenderer")?.obj("description")?.runs()?.joinText(),
        )
    }

    fun playlistContinuation(root: JsonObject): Pair<List<Song>, String?> =
        root.findAll("musicResponsiveListItemRenderer").mapNotNull { songFromListItem(it) } to continuationToken(root)

    fun radio(root: JsonObject): RadioPage {
        val songs = root.findAll("playlistPanelVideoRenderer", skip = setOf("counterpart"))
            .mapNotNull(::songFromPanelVideo)
            .distinctBy { it.id }
        val tabs = root.findAll("tabRenderer").mapNotNull { it.atStr("endpoint", "browseEndpoint", "browseId") }
        return RadioPage(
            songs = songs,
            continuation = continuationToken(root),
            relatedBrowseId = tabs.firstOrNull { it.startsWith("MPTR") },
            lyricsBrowseId = tabs.firstOrNull { it.startsWith("MPLY") },
        )
    }

    fun lyrics(root: JsonObject): String? =
        root.findFirst("musicDescriptionShelfRenderer")?.obj("description")?.runs()?.joinText()?.ifEmpty { null }
}
