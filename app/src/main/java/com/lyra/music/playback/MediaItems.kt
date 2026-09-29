package com.lyra.music.playback

import android.net.Uri
import android.os.Bundle
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.data.source.innertube.hiResArtwork
import kotlinx.serialization.json.Json
import java.io.File

/**
 * Conversión Song ⇄ MediaItem. La URI es `lyra://yt/<id>` o `lyra://sc/<ruta>`:
 * la URL real del audio se resuelve justo al reproducir (ver [LyraDataSourceFactory]).
 */
object MediaItems {
    private const val EXTRA_SONG = "lyra.song"
    private val json = Json { ignoreUnknownKeys = true }

    fun uriFor(songId: String): Uri = Uri.parse("lyra://" + songId.replaceFirst(':', '/'))

    fun songIdFrom(uri: Uri): String? {
        if (uri.scheme != "lyra") return null
        val host = uri.host ?: return null
        val path = uri.path?.trimStart('/') ?: return null
        return "$host:$path"
    }

    fun Song.toMediaItem(coverFile: File? = null): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uriFor(id))
        .setCustomCacheKey(id)
        .setMediaMetadata(toMetadata(coverFile))
        .build()

    fun Song.toMetadata(coverFile: File? = null): MediaMetadata {
        val extras = Bundle().apply { putString(EXTRA_SONG, json.encodeToString(Song.serializer(), this@toMetadata)) }
        return MediaMetadata.Builder()
            .setTitle(title)
            .setDisplayTitle(title)
            .setArtist(artistsText)
            .setSubtitle(artistsText)
            .setAlbumTitle(album?.title)
            .setAlbumArtist(artists.firstOrNull()?.name)
            .setArtworkUri(coverFile?.let(Uri::fromFile) ?: hiResArtwork(thumbnailUrl, 720)?.let(Uri::parse))
            .setDurationMs(durationMs)
            .setIsPlayable(true)
            .setIsBrowsable(false)
            .setMediaType(MediaMetadata.MEDIA_TYPE_MUSIC)
            .setExtras(extras)
            .build()
    }

    /** Reconstruye la canción desde un MediaItem (también los que vienen de otros procesos). */
    fun MediaItem.toSong(): Song? {
        mediaMetadata.extras?.getString(EXTRA_SONG)?.let { encoded ->
            runCatching { return json.decodeFromString(Song.serializer(), encoded) }
        }
        val id = mediaId.substringAfter("::").takeIf { it.startsWith("yt:") || it.startsWith("sc:") } ?: return null
        return Song(
            id = id,
            title = mediaMetadata.title?.toString() ?: return null,
            artists = mediaMetadata.artist?.toString()?.split(", ")?.map { ArtistRef(it) } ?: emptyList(),
            durationMs = mediaMetadata.durationMs,
            thumbnailUrl = mediaMetadata.artworkUri?.toString(),
        )
    }

    /** Los elementos del árbol de Android Auto llevan el contexto delante: `liked::yt:abc`. */
    fun songIdOf(mediaId: String): String = mediaId.substringAfter("::")
}
