package com.lyra.desktop

import com.lyra.desktop.data.Library
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.sync.HttpSyncApi
import com.lyra.music.sync.SyncEngine
import com.lyra.music.sync.SyncRecord
import com.lyra.music.sync.SyncState
import com.lyra.music.sync.SyncStore
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.nio.file.Files

/**
 * Emparejar con un móvil de verdad (el emulador): LYRA_SYNC_CODE=<código que enseña el móvil>.
 * Une una biblioteca de PC vacía, cuenta lo que llega y hace dos cambios para que el móvil los reciba.
 */
class SyncPairLiveTest {

    @Test
    fun `emparejar con el codigo del movil`() = runBlocking {
        val code = System.getenv("LYRA_SYNC_CODE")
        assumeTrue(!code.isNullOrBlank())
        val api = HttpSyncApi(OkHttpClient())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val library = Library(Files.createTempFile("biblioteca", ".json").toFile(), scope)
        val store = object : SyncStore {
            override suspend fun snapshot() = library.syncSnapshot()
            override suspend fun apply(changes: List<SyncRecord>) = library.applySync(changes)
        }
        val key = api.join(code)
        val engine = SyncEngine(api, store)
        var state = engine.sync(key, SyncState()).state
        val data = library.current
        println("Me gusta: ${data.liked.size}")
        data.playlists.forEach { println("Playlist: ${it.name} (${it.songIds.size} canciones) carpeta=${data.folders.firstOrNull { f -> f.id == it.folderId }?.name}") }
        data.folders.forEach { println("Carpeta: ${it.name}") }
        println("Álbumes: ${data.albums.map { it.album.title }} · Artistas: ${data.artists.map { it.artist.title }}")

        // Cambios hechos "en el PC": renombrar una playlist y un Me gusta nuevo.
        data.playlists.firstOrNull()?.let { library.renamePlaylist(it.id, it.name + " (desde el PC)", it.description) }
        library.setLiked(Song("yt:kJQP7kiw5Fk", "Despacito", listOf(ArtistRef("Luis Fonsi"))), true)
        val result = engine.sync(key, state)
        state = result.state
        println("Subidos desde el PC: ${result.pushed}")
        scope.cancel()
    }
}
