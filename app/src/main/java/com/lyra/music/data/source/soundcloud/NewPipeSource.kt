package com.lyra.music.data.source.soundcloud

import com.lyra.music.data.model.ArtistItem
import com.lyra.music.data.model.ArtistPage
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.MusicItem
import com.lyra.music.data.model.PlaylistItem
import com.lyra.music.data.model.PlaylistPage
import com.lyra.music.data.model.Song
import com.lyra.music.data.model.remoteId
import com.lyra.music.data.source.innertube.hiResArtwork
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import org.schabi.newpipe.extractor.Image
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.ChannelInfoItem
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.stream.AudioStream
import org.schabi.newpipe.extractor.stream.AudioTrackType
import org.schabi.newpipe.extractor.stream.DeliveryMethod
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/** Una pista de audio concreta lista para reproducir o descargar. */
data class AudioStreamInfo(
    val url: String,
    val mimeType: String,
    val extension: String,
    val bitrate: Int,
    val codec: String?,
    val isHls: Boolean,
    val contentLength: Long?,
)

enum class AudioQuality { HIGH, NORMAL, LOW }

enum class SoundCloudFilter(val value: String) { TRACKS("tracks"), PLAYLISTS("playlists"), USERS("users") }

data class SoundCloudResults(val items: List<MusicItem>, val nextPage: Page?)

/**
 * Todo lo que usa NewPipeExtractor: las URLs de audio de YouTube (que van cifradas
 * y cambian cada pocas horas) y el catálogo completo de SoundCloud.
 */
class NewPipeSource(client: OkHttpClient) {

    init {
        synchronized(NewPipeSource::class) {
            if (!initialized) {
                NewPipe.init(NewPipeDownloader(client), Localization("es", "ES"), ContentCountry("ES"))
                initialized = true
            }
        }
    }

    // ------------------------------------------------------------- audio

    suspend fun audioStreams(songId: String): List<AudioStreamInfo> = withContext(Dispatchers.IO) {
        val (service, url) = when {
            songId.startsWith("sc:") -> ServiceList.SoundCloud to soundCloudUrl(songId)
            else -> ServiceList.YouTube to "https://www.youtube.com/watch?v=${songId.remoteId()}"
        }
        val extractor = service.getStreamExtractor(url)
        extractor.fetchPage()
        val lengthSeconds = runCatching { extractor.length }.getOrDefault(0L)
        extractor.audioStreams
            .filter { it.isUrl }
            .filter { it.audioTrackType == null || it.audioTrackType == AudioTrackType.ORIGINAL }
            .filter { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP || it.deliveryMethod == DeliveryMethod.HLS }
            .map { it.toInfo(lengthSeconds) }
    }

    private fun AudioStream.toInfo(lengthSeconds: Long): AudioStreamInfo {
        val format = format
        val url = content
        val length = itagItem?.contentLength?.takeIf { it > 0 }
            ?: Regex("[?&]clen=(\\d+)").find(url)?.groupValues?.get(1)?.toLongOrNull()
        // NewPipe no siempre rellena el bitrate: se deduce del tamaño o de la URL (".128.mp3").
        val declared = if (averageBitrate > 0) averageBitrate * 1000 else bitrate
        val estimated = when {
            declared > 0 -> declared
            length != null && lengthSeconds > 0 -> (length * 8 / lengthSeconds).toInt()
            else -> Regex("\\.(\\d{2,3})\\.(mp3|opus|aac)").find(url)?.groupValues?.get(1)?.toIntOrNull()
                ?.times(1000) ?: 0
        }
        return AudioStreamInfo(
            url = url,
            mimeType = format?.mimeType ?: "audio/mpeg",
            extension = format?.suffix ?: "audio",
            bitrate = estimated,
            codec = codec,
            isHls = deliveryMethod == DeliveryMethod.HLS,
            contentLength = length,
        )
    }

    // ------------------------------------------------------------- SoundCloud

    suspend fun searchSoundCloud(
        query: String,
        filter: SoundCloudFilter = SoundCloudFilter.TRACKS,
        page: Page? = null,
    ): SoundCloudResults = withContext(Dispatchers.IO) {
        val extractor = ServiceList.SoundCloud.getSearchExtractor(query, listOf(filter.value), "")
        val result = if (page == null) {
            extractor.fetchPage()
            extractor.initialPage
        } else {
            extractor.getPage(page)
        }
        SoundCloudResults(result.items.mapNotNull(::toMusicItem), result.nextPage)
    }

    suspend fun soundCloudPlaylist(id: String): PlaylistPage = withContext(Dispatchers.IO) {
        val url = soundCloudUrl(id)
        val info = PlaylistInfo.getInfo(ServiceList.SoundCloud, url)
        val songs = info.relatedItems.mapNotNull(::toSong).toMutableList()
        var next = info.nextPage
        // Las playlists de SoundCloud llegan en trozos; se cargan enteras (suelen ser cortas).
        var guard = 0
        while (next != null && guard++ < 20) {
            val more = PlaylistInfo.getMoreItems(ServiceList.SoundCloud, url, next)
            songs += more.items.mapNotNull(::toSong)
            next = more.nextPage
        }
        PlaylistPage(
            playlist = PlaylistItem(
                id = soundCloudId(info.url),
                title = info.name,
                author = info.uploaderName,
                thumbnailUrl = bestImage(info.thumbnails),
                songCountText = "${songs.size} canciones",
            ),
            songs = songs,
            description = info.description?.content?.takeIf { it.isNotBlank() },
        )
    }

    suspend fun soundCloudUser(id: String): ArtistPage = withContext(Dispatchers.IO) {
        val info = ChannelInfo.getInfo(ServiceList.SoundCloud, soundCloudUrl(id))
        val tracksTab = info.tabs.firstOrNull { tab -> tab.contentFilters.any { it == "tracks" } }
            ?: info.tabs.firstOrNull()
        val tracks = tracksTab?.let { tab ->
            ChannelTabInfo.getInfo(ServiceList.SoundCloud, tab).relatedItems.mapNotNull(::toSong)
        } ?: emptyList()
        ArtistPage(
            artist = ArtistItem(soundCloudId(info.url), info.name, bestImage(info.avatars)),
            bannerUrl = bestImage(info.banners),
            description = info.description?.takeIf { it.isNotBlank() },
            listeners = info.subscriberCount.takeIf { it > 0 }?.let { "${formatCount(it)} seguidores" },
            topSongs = tracks,
        )
    }

    /** Pistas relacionadas: la "radio" de una canción de SoundCloud. */
    suspend fun soundCloudRelated(songId: String): List<Song> = withContext(Dispatchers.IO) {
        StreamInfo.getInfo(ServiceList.SoundCloud, soundCloudUrl(songId)).relatedItems.mapNotNull(::toSong)
    }

    suspend fun soundCloudTrack(songId: String): Song? = withContext(Dispatchers.IO) {
        val info = StreamInfo.getInfo(ServiceList.SoundCloud, soundCloudUrl(songId))
        Song(
            id = soundCloudId(info.url),
            title = info.name,
            artists = listOf(ArtistRef(info.uploaderName, info.uploaderUrl?.let(::soundCloudId))),
            durationMs = info.duration.takeIf { it > 0 }?.times(1000),
            thumbnailUrl = bestImage(info.thumbnails),
        )
    }

    private fun toMusicItem(item: InfoItem): MusicItem? = when (item) {
        is StreamInfoItem -> toSong(item)
        is PlaylistInfoItem -> PlaylistItem(
            id = soundCloudId(item.url),
            title = item.name,
            author = item.uploaderName,
            thumbnailUrl = bestImage(item.thumbnails),
            songCountText = item.streamCount.takeIf { it >= 0 }?.let { "$it canciones" },
        )
        is ChannelInfoItem -> ArtistItem(
            id = soundCloudId(item.url),
            title = item.name,
            thumbnailUrl = bestImage(item.thumbnails),
            subtitle = item.subscriberCount.takeIf { it > 0 }?.let { "${formatCount(it)} seguidores" },
        )
        else -> null
    }

    private fun toSong(item: InfoItem): Song? {
        val stream = item as? StreamInfoItem ?: return null
        return Song(
            id = soundCloudId(stream.url),
            title = stream.name,
            artists = listOf(ArtistRef(stream.uploaderName ?: "SoundCloud", stream.uploaderUrl?.let(::soundCloudId))),
            durationMs = stream.duration.takeIf { it > 0 }?.times(1000),
            thumbnailUrl = bestImage(stream.thumbnails),
        )
    }

    companion object {
        @Volatile private var initialized = false

        fun soundCloudId(url: String): String =
            "sc:" + url.substringAfter("soundcloud.com/").substringBefore('?').trimEnd('/')

        fun soundCloudUrl(id: String): String = "https://soundcloud.com/" + id.removePrefix("sc:")

        fun bestImage(images: List<Image>?): String? {
            val best = images?.maxByOrNull { if (it.height > 0) it.height else 0 } ?: return null
            return hiResArtwork(best.url)
        }

        fun formatCount(count: Long): String = when {
            count >= 1_000_000 -> String.format(java.util.Locale.forLanguageTag("es"), "%.1f M", count / 1_000_000.0)
            count >= 1_000 -> String.format(java.util.Locale.forLanguageTag("es"), "%.1f K", count / 1_000.0)
            else -> count.toString()
        }

        /** Elige la pista según la calidad pedida. Prefiere descarga directa frente a HLS. */
        fun pick(streams: List<AudioStreamInfo>, quality: AudioQuality): AudioStreamInfo? {
            val candidates = streams.filterNot { it.isHls }.ifEmpty { streams }
            if (candidates.isEmpty()) return null
            return when (quality) {
                AudioQuality.HIGH -> candidates.maxByOrNull { it.bitrate }
                AudioQuality.LOW -> candidates.minByOrNull { it.bitrate.takeIf { b -> b > 0 } ?: Int.MAX_VALUE }
                AudioQuality.NORMAL -> candidates.minByOrNull { kotlin.math.abs(it.bitrate - 128_000) }
            }
        }
    }
}
