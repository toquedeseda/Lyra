package com.lyra.music.playback

import java.io.File

/**
 * Lo fuerte que suena cada canción, medido la primera vez que se escucha entera (o casi). Con eso,
 * el volumen igualado acierta desde el primer segundo las siguientes veces, en vez de ir subiendo o
 * bajando al empezar mientras la mide. Se guarda en un archivo pequeño (las últimas 5000 canciones).
 */
class LoudnessMemory(private val file: File, private val max: Int = 5_000) {

    private val levels = object : LinkedHashMap<String, Float>(256, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, Float>?) = size > max
    }
    private var dirty = false

    init {
        runCatching {
            file.readLines().forEach { line ->
                val tab = line.lastIndexOf('\t')
                if (tab <= 0) return@forEach
                val db = line.substring(tab + 1).toFloatOrNull() ?: return@forEach
                if (valid(db)) levels[line.substring(0, tab)] = db
            }
        }
    }

    /** La sonoridad media (dB) de [songId], si ya se midió. */
    @Synchronized
    fun get(songId: String): Float? = levels[songId]

    /** Apunta lo medido de [songId] tras escuchar [seconds] segundos (si es poco, no vale). */
    @Synchronized
    fun remember(songId: String, db: Float, seconds: Float, durationSeconds: Float? = null): Boolean {
        if (!valid(db)) return false
        // Con medio minuto (o casi toda una canción corta) la media ya es fiable.
        val enough = seconds >= MIN_SECONDS || (durationSeconds != null && durationSeconds > 0 && seconds >= durationSeconds * 0.7f)
        if (!enough) return false
        val previous = levels[songId]
        if (previous != null && kotlin.math.abs(previous - db) < 0.2f) return false
        levels[songId] = db
        dirty = true
        return true
    }

    /** Guarda en disco si hay algo nuevo (en un archivo aparte que luego se cambia por el bueno). */
    fun save() {
        val text = synchronized(this) {
            if (!dirty) return
            dirty = false
            levels.entries.joinToString("\n", postfix = "\n") { (id, db) -> "$id\t${"%.2f".format(java.util.Locale.ROOT, db)}" }
        }
        runCatching {
            file.parentFile?.mkdirs()
            val temp = File(file.path + ".tmp")
            temp.writeText(text)
            java.nio.file.Files.move(
                temp.toPath(), file.toPath(),
                java.nio.file.StandardCopyOption.REPLACE_EXISTING, java.nio.file.StandardCopyOption.ATOMIC_MOVE,
            )
        }
    }

    private fun valid(db: Float) = !db.isNaN() && db in -70f..0f

    private companion object {
        const val MIN_SECONDS = 30f
    }
}
