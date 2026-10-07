package com.lyra.desktop

import com.lyra.desktop.data.Library
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import com.lyra.music.sync.HttpSyncApi
import com.lyra.music.sync.PlaylistData
import com.lyra.music.sync.SyncEngine
import com.lyra.music.sync.SyncJson
import com.lyra.music.sync.SyncRecord
import com.lyra.music.sync.SyncState
import com.lyra.music.sync.SyncStore
import com.lyra.music.sync.SyncUnpairedException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import java.nio.file.Files

/**
 * La sincronización de verdad, contra el servidor de la web de Lyra: un "móvil" de mentira y la
 * biblioteca del PC (solo con LYRA_LIVE_TESTS=1). Al acabar, los dos se desemparejan y la
 * biblioteca de prueba se borra del servidor.
 */
class SyncLiveTest {

    @Before
    fun onlyLive() = assumeTrue(System.getenv("LYRA_LIVE_TESTS") == "1")

    private class PhoneStore : SyncStore {
        val records = linkedMapOf<String, SyncRecord>()
        override suspend fun snapshot() = records.values.toList()
        override suspend fun apply(changes: List<SyncRecord>) {
            changes.forEach { if (it.d == null) records.remove(it.id) else records[it.id] = it.copy(u = 0, r = 0) }
        }
    }

    private fun song(n: Int) = Song("yt:prueba0000$n", "Canción de prueba $n", listOf(ArtistRef("Lyra")))

    @Test
    fun `el movil y el PC acaban con la misma biblioteca`() = runBlocking {
        val api = HttpSyncApi(OkHttpClient())
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        val pcLibrary = Library(Files.createTempFile("biblioteca", ".json").toFile(), scope)
        val pcStore = object : SyncStore {
            override suspend fun snapshot() = pcLibrary.syncSnapshot()
            override suspend fun apply(changes: List<SyncRecord>) = pcLibrary.applySync(changes)
        }
        val phone = PhoneStore().apply {
            records["like:" + song(1).id] = SyncJson.like(song(1), 10)
            records["playlist:p-movil"] = SyncJson.playlist("p-movil", PlaylistData("Del móvil", songs = listOf(song(1), song(2))))
        }
        pcLibrary.setLiked(song(3), true)

        // El móvil activa la sincronización y da el código; el PC se une con él.
        val phoneKey = api.create()
        val code = api.pairCode(phoneKey)
        val pcKey = api.join(code.codigo.substring(0, 3) + "-" + code.codigo.substring(3))
        val phoneEngine = SyncEngine(api, phone)
        val pcEngine = SyncEngine(api, pcStore)
        var phoneState = phoneEngine.sync(phoneKey, SyncState()).state
        var pcState = pcEngine.sync(pcKey, SyncState()).state
        phoneState = phoneEngine.sync(phoneKey, phoneState).state

        assertTrue("el PC tiene el Me gusta del móvil", pcLibrary.isLiked(song(1).id))
        assertEquals("Del móvil", pcLibrary.playlist("p-movil")?.name)
        assertEquals(listOf(song(1).id, song(2).id), pcLibrary.playlist("p-movil")?.songIds)
        assertTrue("el móvil tiene el Me gusta del PC", "like:" + song(3).id in phone.records)

        // Cambios en el PC (renombrar y quitar un Me gusta) llegan al móvil.
        pcLibrary.renamePlaylist("p-movil", "Renombrada en el PC", null)
        pcLibrary.setLiked(song(1), false)
        pcState = pcEngine.sync(pcKey, pcState).state
        phoneState = phoneEngine.sync(phoneKey, phoneState).state
        assertEquals("Renombrada en el PC", SyncJson.decode(PlaylistData.serializer(), phone.records["playlist:p-movil"]!!.d!!)!!.name)
        assertFalse("like:" + song(1).id in phone.records)

        // Sin cambios, no se sube nada.
        assertEquals(0, pcEngine.sync(pcKey, pcState).pushed)
        assertEquals(0, phoneEngine.sync(phoneKey, phoneState).pushed)

        // Limpieza: los dos se desemparejan y la biblioteca de prueba desaparece del servidor.
        api.leave(pcKey)
        api.leave(phoneKey)
        val gone = runCatching { api.pull(phoneKey, 0) }.exceptionOrNull()
        assertTrue(gone is SyncUnpairedException)
        scope.cancel()
    }
}
