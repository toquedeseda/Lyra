package com.lyra.music.sync

import com.lyra.music.data.model.AlbumItem
import com.lyra.music.data.model.ArtistRef
import com.lyra.music.data.model.Song
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** El motor de sincronización con un servidor y dos bibliotecas de mentira (móvil y PC). */
class SyncEngineTest {

    /** Lo mismo que hace el servidor de verdad (deploy/lyra-app/sincronizar.mjs). */
    private class FakeServer : SyncApi {
        var rev = 0L
        val records = linkedMapOf<String, SyncRecord>()
        override suspend fun create() = SyncKey("biblioteca000000", "token")
        override suspend fun pairCode(key: SyncKey) = SyncCode("ABC234", 0)
        override suspend fun join(code: String) = SyncKey("biblioteca000000", "token2")
        override suspend fun pull(key: SyncKey, since: Long) = SyncPull(rev, records.values.filter { it.r > since })
        override suspend fun push(key: SyncKey, records: List<SyncRecord>): SyncPushResult {
            var accepted = 0
            for (r in records) {
                val old = this.records[r.id]
                if (old != null && old.u > r.u) continue
                rev++
                this.records[r.id] = r.copy(r = rev)
                accepted++
            }
            return SyncPushResult(rev, accepted)
        }
        override suspend fun leave(key: SyncKey) = Unit
    }

    /**
     * Una biblioteca. Con [lossy], guarda las canciones sin carátula (como si la app guardara
     * menos datos): no debe provocar subidas sin fin.
     */
    private class FakeStore(private val lossy: Boolean = false) : SyncStore {
        val records = linkedMapOf<String, SyncRecord>()
        var applied = 0
        override suspend fun snapshot(): List<SyncRecord> = records.values.toList()
        override suspend fun apply(changes: List<SyncRecord>) {
            for (c in changes) {
                applied++
                if (c.d == null) records.remove(c.id) else records[c.id] = if (lossy) stripCovers(c) else c.copy(u = 0, r = 0)
            }
        }

        private fun stripCovers(record: SyncRecord): SyncRecord {
            if (record.t != SyncTypes.PLAYLIST) return record.copy(u = 0, r = 0)
            val data = SyncJson.decode(PlaylistData.serializer(), record.d!!)!!
            return SyncJson.playlist(record.k, data.copy(songs = data.songs.map { it.copy(thumbnailUrl = null) }))
        }

        fun like(song: Song) {
            records[SyncTypes.LIKE + ":" + song.id] = SyncJson.like(song, 1)
        }

        fun playlist(key: String, name: String, songs: List<Song>) {
            records[SyncTypes.PLAYLIST + ":" + key] = SyncJson.playlist(key, PlaylistData(name, songs = songs))
        }

        fun names() = records.values.filter { it.t == SyncTypes.PLAYLIST }.associate { it.k to SyncJson.decode(PlaylistData.serializer(), it.d!!)!!.name }
    }

    private fun song(n: Int) = Song("yt:cancion$n", "Canción $n", listOf(ArtistRef("Artista")), thumbnailUrl = "https://img/$n.jpg")

    private var time = 1_000L
    private val server = FakeServer()
    private val key = SyncKey("biblioteca000000", "token")

    private class Device(val store: SyncStore, val engine: SyncEngine) {
        var state = SyncState()
    }

    private fun device(store: SyncStore) = Device(store, SyncEngine(server, store) { time })

    private fun Device.sync(): SyncResult = runBlocking {
        time += 1_000
        engine.sync(key, state).also { state = it.state }
    }

    @Test
    fun `lo del movil llega al PC y lo del PC al movil`() {
        val phone = FakeStore().apply {
            like(song(1))
            like(song(2))
            playlist("p1", "Para correr", listOf(song(1), song(3)))
        }
        val pc = FakeStore().apply { like(song(9)) }
        val a = device(phone)
        val b = device(pc)
        a.sync()
        b.sync()
        a.sync()
        assertEquals(phone.records.keys, pc.records.keys)
        assertEquals(setOf("like:yt:cancion1", "like:yt:cancion2", "like:yt:cancion9", "playlist:p1"), pc.records.keys)
    }

    @Test
    fun `cambios y borrados se propagan`() {
        val phone = FakeStore().apply {
            like(song(1))
            playlist("p1", "Para correr", listOf(song(1)))
        }
        val pc = FakeStore()
        val a = device(phone)
        val b = device(pc)
        a.sync()
        b.sync()

        pc.playlist("p1", "Gimnasio", listOf(song(1), song(2)))
        b.sync()
        a.sync()
        assertEquals("Gimnasio", phone.names()["p1"])

        phone.records.remove("like:yt:cancion1")
        a.sync()
        b.sync()
        assertNull(pc.records["like:yt:cancion1"])
    }

    @Test
    fun `si los dos cambian lo mismo gana el mas reciente`() {
        val phone = FakeStore().apply { playlist("p1", "Original", listOf(song(1))) }
        val pc = FakeStore()
        val a = device(phone)
        val b = device(pc)
        a.sync()
        b.sync()
        phone.playlist("p1", "Del móvil", listOf(song(1)))
        pc.playlist("p1", "Del PC", listOf(song(1)))
        a.sync() // el móvil sube antes
        b.sync() // el PC después: es más reciente y gana
        a.sync()
        assertEquals("Del PC", phone.names()["p1"])
        assertEquals("Del PC", pc.names()["p1"])
    }

    @Test
    fun `sin cambios no se sube nada aunque cada app guarde distinto`() {
        val phone = FakeStore().apply { playlist("p1", "Para correr", listOf(song(1), song(2))) }
        val pc = FakeStore(lossy = true)
        val a = device(phone)
        val b = device(pc)
        a.sync()
        b.sync()
        a.sync()
        val quietA = a.sync()
        val quietB = b.sync()
        assertEquals(0, quietA.pushed)
        assertEquals(0, quietB.pushed)
        assertEquals(0, quietA.applied)
        assertEquals(0, quietB.applied)
        assertTrue(server.rev <= 2)
    }

    @Test
    fun `los albumes guardados viajan`() {
        val phone = FakeStore().apply {
            records["album:yt:MPREb_x"] = SyncJson.album(AlbumData(AlbumItem("yt:MPREb_x", "MOTOMAMI"), 5))
        }
        val pc = FakeStore()
        device(phone).sync()
        device(pc).sync()
        assertEquals("MOTOMAMI", SyncJson.decode(AlbumData.serializer(), pc.records["album:yt:MPREb_x"]!!.d!!)!!.album.title)
    }

    @Test
    fun `el codigo se ve con guion`() {
        assertEquals("K7P-2MX", formatPairCode("K7P2MX"))
    }
}
