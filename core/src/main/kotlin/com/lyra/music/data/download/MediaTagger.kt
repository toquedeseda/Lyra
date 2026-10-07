package com.lyra.music.data.download

import java.io.ByteArrayOutputStream
import java.io.File
import java.nio.ByteBuffer

/** Datos que se escriben dentro del archivo para que otros reproductores lo muestren bien. */
data class AudioTags(
    val title: String,
    val artist: String,
    val album: String? = null,
    val albumArtist: String? = null,
    val year: String? = null,
    /** Carátula en JPEG. */
    val cover: ByteArray? = null,
)

/**
 * Escribe título, artista, álbum y carátula en archivos M4A (átomos de iTunes).
 *
 * Funciona con MP4 "normal" y fragmentado (el de YouTube y SoundCloud). Al meter
 * los datos dentro de `moov`, todo lo que va detrás se desplaza, así que se
 * corrigen las posiciones absolutas que guarda el archivo (stco/co64, tfhd, tfra).
 * Si encuentra algo que no sabe tratar, no toca nada y devuelve false.
 */
object Mp4Tagger {

    private class Box(val type: String, val start: Int, val headerSize: Int, val end: Int) {
        val payloadStart get() = start + headerSize
    }

    fun tag(input: File, output: File, tags: AudioTags): Boolean {
        val data = input.readBytes()
        val result = tag(data, tags) ?: return false
        output.writeBytes(result)
        return true
    }

    fun tag(data: ByteArray, tags: AudioTags): ByteArray? = runCatching {
        val top = parseBoxes(data, 0, data.size) ?: return null
        val moov = top.firstOrNull { it.type == "moov" } ?: return null
        val children = parseBoxes(data, moov.payloadStart, moov.end) ?: return null

        val payload = ByteArrayOutputStream()
        children.filter { it.type != "udta" }.forEach { payload.write(data, it.start, it.end - it.start) }
        payload.write(buildUdta(tags))
        val newMoov = box("moov", payload.toByteArray())
        val delta = newMoov.size - (moov.end - moov.start)

        // Lo que va detrás de moov se desplaza: se corrigen sus posiciones absolutas.
        if (!patchChunkOffsets(newMoov, 0, newMoov.size, delta, mediaAfterMoov = top.any { it.type == "mdat" && it.start > moov.start })) {
            return null
        }
        val tail = data.copyOfRange(moov.end, data.size)
        if (!patchFragments(tail, delta)) return null

        ByteArrayOutputStream(data.size + newMoov.size).apply {
            write(data, 0, moov.start)
            write(newMoov)
            write(tail)
        }.toByteArray()
    }.getOrNull()

    private fun parseBoxes(data: ByteArray, from: Int, to: Int): List<Box>? {
        val boxes = mutableListOf<Box>()
        var pos = from
        while (pos + 8 <= to) {
            var size = readUInt(data, pos)
            val type = String(data, pos + 4, 4, Charsets.ISO_8859_1)
            var header = 8
            when (size) {
                1L -> {
                    if (pos + 16 > to) return null
                    size = ByteBuffer.wrap(data, pos + 8, 8).long
                    header = 16
                }
                0L -> size = (to - pos).toLong()
            }
            if (size < header || pos + size > to) return null
            boxes += Box(type, pos, header, (pos + size).toInt())
            pos += size.toInt()
        }
        return boxes
    }

    /** Suma [delta] a las tablas stco/co64 (solo si los datos van detrás de moov). */
    private fun patchChunkOffsets(buf: ByteArray, from: Int, to: Int, delta: Int, mediaAfterMoov: Boolean): Boolean {
        val boxes = parseBoxes(buf, from, to) ?: return false
        for (b in boxes) {
            when (b.type) {
                "moov", "trak", "mdia", "minf", "stbl" -> {
                    if (!patchChunkOffsets(buf, b.payloadStart, b.end, delta, mediaAfterMoov)) return false
                }
                "stco" -> if (mediaAfterMoov) {
                    val count = readUInt(buf, b.payloadStart + 4).toInt()
                    for (i in 0 until count) {
                        val at = b.payloadStart + 8 + i * 4
                        writeUInt(buf, at, readUInt(buf, at) + delta)
                    }
                }
                "co64" -> if (mediaAfterMoov) {
                    val count = readUInt(buf, b.payloadStart + 4).toInt()
                    for (i in 0 until count) {
                        val at = b.payloadStart + 8 + i * 8
                        ByteBuffer.wrap(buf, at, 8).putLong(ByteBuffer.wrap(buf, at, 8).long + delta)
                    }
                }
            }
        }
        return true
    }

    /** En MP4 fragmentado: base_data_offset explícitos (tfhd) y el índice tfra de mfra. */
    private fun patchFragments(tail: ByteArray, delta: Int): Boolean {
        val boxes = parseBoxes(tail, 0, tail.size) ?: return false
        for (b in boxes) {
            when (b.type) {
                "moof" -> {
                    val trafs = parseBoxes(tail, b.payloadStart, b.end) ?: return false
                    for (traf in trafs.filter { it.type == "traf" }) {
                        val inner = parseBoxes(tail, traf.payloadStart, traf.end) ?: return false
                        inner.firstOrNull { it.type == "tfhd" }?.let { tfhd ->
                            val flags = readUInt(tail, tfhd.payloadStart) and 0xFFFFFF
                            if (flags and 0x1L != 0L) {
                                val at = tfhd.payloadStart + 8
                                ByteBuffer.wrap(tail, at, 8).putLong(ByteBuffer.wrap(tail, at, 8).long + delta)
                            }
                        }
                    }
                }
                "mfra" -> {
                    val inner = parseBoxes(tail, b.payloadStart, b.end) ?: return false
                    for (tfra in inner.filter { it.type == "tfra" }) {
                        val version = tail[tfra.payloadStart].toInt()
                        val sizes = readUInt(tail, tfra.payloadStart + 8)
                        val trafLen = ((sizes shr 4) and 3).toInt() + 1
                        val trunLen = ((sizes shr 2) and 3).toInt() + 1
                        val sampleLen = (sizes and 3).toInt() + 1
                        val count = readUInt(tail, tfra.payloadStart + 12).toInt()
                        var at = tfra.payloadStart + 16
                        repeat(count) {
                            if (version == 1) {
                                at += 8
                                ByteBuffer.wrap(tail, at, 8).putLong(ByteBuffer.wrap(tail, at, 8).long + delta)
                                at += 8
                            } else {
                                at += 4
                                writeUInt(tail, at, readUInt(tail, at) + delta)
                                at += 4
                            }
                            at += trafLen + trunLen + sampleLen
                        }
                    }
                }
            }
        }
        return true
    }

    private fun buildUdta(tags: AudioTags): ByteArray {
        val ilst = ByteArrayOutputStream()
        fun text(name: ByteArray, value: String?) {
            if (value.isNullOrBlank()) return
            ilst.write(box(name, dataAtom(1, value.toByteArray(Charsets.UTF_8))))
        }
        text(atomName("nam"), tags.title)
        text(atomName("ART"), tags.artist)
        text(atomName("alb"), tags.album)
        text("aART".toByteArray(Charsets.ISO_8859_1), tags.albumArtist)
        text(atomName("day"), tags.year)
        text(atomName("too"), "Lyra")
        tags.cover?.let { ilst.write(box("covr", dataAtom(13, it))) }

        val hdlr = ByteArrayOutputStream().apply {
            write(ByteArray(4)) // versión y flags
            write(ByteArray(4)) // pre_defined
            write("mdir".toByteArray(Charsets.ISO_8859_1))
            write("appl".toByteArray(Charsets.ISO_8859_1))
            write(ByteArray(8))
            write(0) // nombre vacío
        }.toByteArray()
        val meta = ByteArrayOutputStream().apply {
            write(ByteArray(4)) // meta es un "full box"
            write(box("hdlr", hdlr))
            write(box("ilst", ilst.toByteArray()))
        }.toByteArray()
        return box("udta", box("meta", meta))
    }

    /** Átomo "data": tipo 1 = texto UTF-8, 13 = JPEG. */
    private fun dataAtom(type: Int, payload: ByteArray): ByteArray = box(
        "data",
        ByteBuffer.allocate(8 + payload.size).putInt(type).putInt(0).put(payload).array(),
    )

    private fun atomName(name: String) = byteArrayOf(0xA9.toByte()) + name.toByteArray(Charsets.ISO_8859_1)

    private fun box(type: String, payload: ByteArray) = box(type.toByteArray(Charsets.ISO_8859_1), payload)

    private fun box(type: ByteArray, payload: ByteArray): ByteArray =
        ByteBuffer.allocate(8 + payload.size).putInt(8 + payload.size).put(type).put(payload).array()

    private fun readUInt(data: ByteArray, at: Int): Long =
        ((data[at].toLong() and 0xFF) shl 24) or ((data[at + 1].toLong() and 0xFF) shl 16) or
            ((data[at + 2].toLong() and 0xFF) shl 8) or (data[at + 3].toLong() and 0xFF)

    private fun writeUInt(data: ByteArray, at: Int, value: Long) {
        data[at] = (value shr 24).toByte()
        data[at + 1] = (value shr 16).toByte()
        data[at + 2] = (value shr 8).toByte()
        data[at + 3] = value.toByte()
    }
}

/** Escribe una etiqueta ID3v2.3 (título, artista, álbum y carátula) al principio de un MP3. */
object Id3Tagger {

    fun tag(input: File, output: File, tags: AudioTags): Boolean = runCatching {
        output.writeBytes(tag(input.readBytes(), tags))
        true
    }.getOrDefault(false)

    fun tag(data: ByteArray, tags: AudioTags): ByteArray {
        val frames = ByteArrayOutputStream()
        fun text(id: String, value: String?) {
            if (value.isNullOrBlank()) return
            // Codificación 1 = UTF-16 con BOM (la más compatible en ID3v2.3).
            val body = byteArrayOf(1) + byteArrayOf(0xFF.toByte(), 0xFE.toByte()) + value.toByteArray(Charsets.UTF_16LE)
            frames.write(frame(id, body))
        }
        text("TIT2", tags.title)
        text("TPE1", tags.artist)
        text("TALB", tags.album)
        text("TPE2", tags.albumArtist)
        text("TYER", tags.year)
        text("TENC", "Lyra")
        tags.cover?.let { cover ->
            val body = ByteArrayOutputStream().apply {
                write(0) // ISO-8859-1 para el tipo MIME y la descripción
                write("image/jpeg".toByteArray(Charsets.ISO_8859_1))
                write(0)
                write(3) // portada delantera
                write(0) // descripción vacía
                write(cover)
            }.toByteArray()
            frames.write(frame("APIC", body))
        }
        val payload = frames.toByteArray()
        val header = byteArrayOf('I'.code.toByte(), 'D'.code.toByte(), '3'.code.toByte(), 3, 0, 0) + syncsafe(payload.size)
        return header + payload + data.copyOfRange(existingTagLength(data), data.size)
    }

    /** Si el MP3 ya trae una etiqueta ID3v2, se sustituye por la nuestra. */
    private fun existingTagLength(data: ByteArray): Int {
        if (data.size < 10 || data[0] != 'I'.code.toByte() || data[1] != 'D'.code.toByte() || data[2] != '3'.code.toByte()) return 0
        val size = ((data[6].toInt() and 0x7F) shl 21) or ((data[7].toInt() and 0x7F) shl 14) or
            ((data[8].toInt() and 0x7F) shl 7) or (data[9].toInt() and 0x7F)
        val footer = if (data[5].toInt() and 0x10 != 0) 10 else 0
        return (10 + size + footer).coerceAtMost(data.size)
    }

    private fun frame(id: String, body: ByteArray): ByteArray =
        ByteBuffer.allocate(10 + body.size).put(id.toByteArray(Charsets.ISO_8859_1)).putInt(body.size).putShort(0).put(body).array()

    private fun syncsafe(value: Int) = byteArrayOf(
        ((value shr 21) and 0x7F).toByte(),
        ((value shr 14) and 0x7F).toByte(),
        ((value shr 7) and 0x7F).toByte(),
        (value and 0x7F).toByte(),
    )
}
