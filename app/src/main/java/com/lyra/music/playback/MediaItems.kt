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
    private const val EXTRA_RECOMMENDED = "lyra.recommended"
    private const val EXTRA_RADIO = "lyra.radio"
    private val json = Json { ignoreUnknownKeys = true }

    /** Canciones ya leídas de su JSON (con colas de cientos, leerlas en cada cambio daba tirones). */
    private val decoded = object : LinkedHashMap<String, Song>(512, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Song>) = size > 3_000
    }

    fun uriFor(songId: String): Uri = Uri.parse("lyra://" + songId.replaceFirst(':', '/'))

    /** URI interna con título, artista y duración: si YouTube no deja ese vídeo, sirven para buscar otra versión. */
    fun uriFor(song: Song): Uri = uriFor(song.id).buildUpon()
        .appendQueryParameter("t", song.title)
        .appendQueryParameter("a", song.artists.firstOrNull()?.name.orEmpty())
        .apply { song.durationMs?.let { appendQueryParameter("d", it.toString()) } }
        .build()

    /** Lo que se sabe de la canción por su URI interna (null si no lleva datos). */
    fun hintFrom(uri: Uri): Song? {
        val id = songIdFrom(uri) ?: return null
        val title = uri.getQueryParameter("t")?.takeIf { it.isNotBlank() } ?: return null
        val artist = uri.getQueryParameter("a").orEmpty()
        return Song(
            id = id,
            title = title,
            artists = if (artist.isBlank()) emptyList() else listOf(ArtistRef(artist)),
            durationMs = uri.getQueryParameter("d")?.toLongOrNull(),
        )
    }

    fun songIdFrom(uri: Uri): String? {
        if (uri.scheme != "lyra") return null
        val host = uri.host ?: return null
        val path = uri.path?.trimStart('/') ?: return null
        return "$host:$path"
    }

    /**
     * [recommended]: la ha metido el aleatorio inteligente (se marca en la cola).
     * [radio]: la ha añadido la radio al acabarse tu lista (el aleatorio no la mezcla con la lista).
     */
    fun Song.toMediaItem(coverFile: File? = null, recommended: Boolean = false, radio: Boolean = false): MediaItem = MediaItem.Builder()
        .setMediaId(id)
        .setUri(uriFor(this))
        .setCustomCacheKey(id)
        .setMediaMetadata(toMetadata(coverFile, recommended, radio))
        .build()

    fun Song.toMetadata(coverFile: File? = null, recommended: Boolean = false, radio: Boolean = false): MediaMetadata {
        val extras = Bundle().apply {
            putString(EXTRA_SONG, json.encodeToString(Song.serializer(), this@toMetadata))
            if (recommended) putBoolean(EXTRA_RECOMMENDED, true)
            if (radio) putBoolean(EXTRA_RADIO, true)
        }
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
            synchronized(decoded) { decoded[encoded] }?.let { return it }
            runCatching {
                val song = json.decodeFromString(Song.serializer(), encoded)
                synchronized(decoded) { decoded[encoded] = song }
                return song
            }
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

    /** Canción que ha metido el aleatorio inteligente. */
    fun MediaItem.isRecommended(): Boolean = mediaMetadata.extras?.getBoolean(EXTRA_RECOMMENDED, false) == true

    /** Canción que ha añadido la radio infinita detrás de tu lista. */
    fun MediaItem.isRadio(): Boolean = mediaMetadata.extras?.getBoolean(EXTRA_RADIO, false) == true

    /** Los elementos del árbol de Android Auto llevan el contexto delante: `liked::yt:abc`. */
    fun songIdOf(mediaId: String): String = mediaId.substringAfter("::")
}
