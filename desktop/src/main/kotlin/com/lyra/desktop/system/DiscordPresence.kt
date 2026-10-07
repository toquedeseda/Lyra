package com.lyra.desktop.system

import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonObject
import java.io.RandomAccessFile
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.UUID
import java.util.concurrent.Executors

/**
 * «Escuchando Lyra» en tu perfil de Discord (si Discord está abierto en el PC). Habla con Discord
 * por su tubería local (lo mismo que hacen Spotify y los juegos); no necesita cuenta ni claves.
 */
object DiscordPresence {
    /**
     * La «aplicación» de Discord que da nombre al estado («Escuchando Lyra»). Se crea gratis en
     * https://discord.com/developers/applications con el nombre Lyra; el número no es secreto.
     */
    const val APP_ID = ""

    val available: Boolean get() = APP_ID.isNotBlank()

    data class Track(
        val title: String,
        val artists: String,
        val album: String?,
        val coverUrl: String?,
        val link: String?,
        val startedAt: Long,
        val durationMs: Long?,
    )

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lyra-discord").apply { isDaemon = true } }
    private var pipe: RandomAccessFile? = null
    private var lastSent: Track? = null
    private var failedAt = 0L

    /** Enseña [track] (o nada si es null) en el estado de Discord. */
    fun show(track: Track?) {
        if (!available) return
        worker.execute {
            if (track == lastSent) return@execute
            runCatching {
                val connection = connect() ?: return@execute
                send(connection, 1, activityPayload(track))
                read(connection)
                lastSent = track
            }.onFailure { close() }
        }
    }

    fun clear() = show(null)

    private fun connect(): RandomAccessFile? {
        pipe?.let { return it }
        // Sin Discord abierto no se insiste a cada canción: se vuelve a probar a los 30 s.
        if (System.currentTimeMillis() - failedAt < 30_000) return null
        for (i in 0..9) {
            val file = runCatching { RandomAccessFile("\\\\.\\pipe\\discord-ipc-$i", "rw") }.getOrNull() ?: continue
            return runCatching {
                send(file, 0, buildJsonObject {
                    put("v", 1)
                    put("client_id", APP_ID)
                })
                read(file)
                pipe = file
                file
            }.getOrElse {
                runCatching { file.close() }
                null
            }
        }
        failedAt = System.currentTimeMillis()
        return null
    }

    private fun activityPayload(track: Track?): JsonObject = buildJsonObject {
        put("cmd", "SET_ACTIVITY")
        put("nonce", UUID.randomUUID().toString())
        putJsonObject("args") {
            put("pid", ProcessHandle.current().pid())
            if (track == null) {
                put("activity", kotlinx.serialization.json.JsonNull)
            } else {
                putJsonObject("activity") {
                    put("type", 2) // «Escuchando»
                    put("status_display_type", 1) // en la lista de miembros sale la canción
                    put("details", track.title.take(120))
                    put("state", track.artists.take(120).ifBlank { "Lyra" })
                    putJsonObject("timestamps") {
                        put("start", track.startedAt)
                        track.durationMs?.takeIf { it > 0 }?.let { put("end", track.startedAt + it) }
                    }
                    putJsonObject("assets") {
                        track.coverUrl?.let { put("large_image", it) }
                        put("large_text", (track.album ?: track.title).take(120))
                    }
                    track.link?.let { link ->
                        put("buttons", buildJsonArray {
                            add(buildJsonObject {
                                put("label", "Escuchar")
                                put("url", link)
                            })
                        })
                    }
                }
            }
        }
    }

    private fun send(file: RandomAccessFile, op: Int, payload: JsonObject) {
        val body = payload.toString().toByteArray(Charsets.UTF_8)
        val header = ByteBuffer.allocate(8).order(ByteOrder.LITTLE_ENDIAN).putInt(op).putInt(body.size).array()
        file.write(header + body)
    }

    private fun read(file: RandomAccessFile) {
        val header = ByteArray(8)
        file.readFully(header)
        val length = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN).getInt(4)
        if (length in 1..1_000_000) file.readFully(ByteArray(length))
    }

    private fun close() {
        runCatching { pipe?.close() }
        pipe = null
        lastSent = null
        failedAt = System.currentTimeMillis()
    }
}
