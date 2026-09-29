package com.lyra.music.data.repo

import com.lyra.music.data.db.LyraDatabase
import com.lyra.music.data.db.PlaylistEntity
import com.lyra.music.data.download.DownloadRepository
import com.lyra.music.data.model.Song
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Playlists sincronizadas: si la original (YouTube Music, SoundCloud o Spotify)
 * tiene canciones nuevas, se añaden a tu copia y, si quieres, se descargan solas.
 */
class PlaylistSync(
    private val db: LyraDatabase,
    private val music: MusicRepository,
    private val library: LibraryRepository,
    private val downloads: DownloadRepository,
    private val spotify: SpotifyImportManager,
) {
    data class Result(val playlistId: Long, val name: String, val added: Int)

    suspend fun syncAll(): List<Result> = withContext(Dispatchers.IO) {
        db.playlists().syncable().mapNotNull { playlist ->
            runCatching { sync(playlist) }.getOrNull()?.takeIf { it.added > 0 }
        }
    }

    suspend fun sync(playlist: PlaylistEntity): Result = withContext(Dispatchers.IO) {
        val remote = playlist.remoteId ?: return@withContext Result(playlist.id, playlist.name, 0)
        val current = db.playlists().entries(playlist.id).map { it.songId }.toSet()
        val fresh: List<Song> = if (remote.startsWith("spotify:")) {
            newFromSpotify(playlist, remote, current)
        } else {
            music.fullPlaylist(remote).songs.filter { it.id !in current }
        }
        if (fresh.isNotEmpty()) {
            library.addToPlaylist(playlist.id, fresh)
            // Se descargan si la playlist lo tiene activado o si ya estaba toda descargada.
            val allDownloaded = current.isNotEmpty() && current.all { downloads.isDownloaded(it) }
            if (playlist.autoDownload || allDownloaded) downloads.enqueue(fresh, playlist.name)
        }
        db.playlists().markSynced(playlist.id)
        Result(playlist.id, playlist.name, fresh.size)
    }

    private suspend fun newFromSpotify(playlist: PlaylistEntity, remote: String, current: Set<String>): List<Song> {
        val parts = remote.split(':')
        if (parts.size < 3) return emptyList()
        val collection = spotify.spotify.fetch(parts[1], parts[2])
        val map = spotify.loadMap(playlist.id).toMutableMap()
        val newTracks = collection.tracks.filter { it.uri !in map }
        if (newTracks.isEmpty()) return emptyList()
        val matches = spotify.matchAll(newTracks) { }
        val songs = mutableListOf<Song>()
        newTracks.zip(matches).forEach { (track, song) ->
            // También se apuntan las que no se encontraron, para no buscarlas cada vez.
            map[track.uri] = song?.id ?: ""
            if (song != null && song.id !in current) songs += song
        }
        spotify.saveMap(playlist.id, map)
        return songs.distinctBy { it.id }
    }
}
