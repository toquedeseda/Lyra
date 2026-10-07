package com.lyra.desktop

import com.sun.jna.platform.win32.KnownFolders
import com.sun.jna.platform.win32.Shell32Util
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption

/**
 * Dónde guarda Lyra sus cosas en Windows:
 *  - %APPDATA%\Lyra: biblioteca, ajustes, informe de errores y caché.
 *  - Música\Lyra: las canciones descargadas (como en el móvil).
 * Al probar desde el código (sin instalar) se usa %APPDATA%\Lyra-dev para no tocar lo de verdad.
 */
object Paths {
    private val dev = System.getProperty("lyra.dev") == "1"

    val data: File = File(System.getenv("APPDATA") ?: System.getProperty("user.home"), if (dev) "Lyra-dev" else "Lyra")
        .apply { mkdirs() }

    val cache: File = File(data, "cache").apply { mkdirs() }
    val audioCache: File = File(cache, "audio")
    val hlsCache: File = File(cache, "hls")
    val imageCache: File = File(cache, "imagenes")
    val httpCache: File = File(cache, "http")
    val natives: File = File(cache, "nativos")
    val updates: File = File(cache, "actualizaciones")
    val covers: File = File(data, "portadas").apply { mkdirs() }

    val library: File = File(data, "biblioteca.json")
    val settings: File = File(data, "ajustes.json")
    val queue: File = File(data, "cola.json")
    val homeCache: File = File(data, "inicio.json")
    val alternatives: File = File(data, "alternativas.json")
    val lyricsCache: File = File(cache, "letras")
    val errors: File = File(data, "errores.jsonl")
    val instanceLock: File = File(data, "abierta.lock")
    val instancePort: File = File(data, "abierta.puerto")

    /** La carpeta Música de Windows (aunque esté movida a OneDrive u otro disco). */
    val musicFolder: File by lazy {
        runCatching { File(Shell32Util.getKnownFolderPath(KnownFolders.FOLDERID_Music)) }.getOrNull()
            ?.takeIf { it.isDirectory }
            ?: File(System.getProperty("user.home"), "Music")
    }

    fun downloadsFolder(custom: String?): File =
        (custom?.takeIf { it.isNotBlank() }?.let(::File) ?: File(musicFolder, "Lyra")).apply { mkdirs() }

    init {
        // FFmpeg y compañía se copian aquí la primera vez, no en la carpeta del usuario.
        System.setProperty("org.bytedeco.javacpp.cachedir", natives.absolutePath)
    }
}

/** Escribe en un archivo aparte y luego lo cambia por el bueno: si se corta a medias, el anterior sigue entero. */
fun File.writeTextSafely(text: String) {
    val temp = File(path + ".tmp")
    temp.writeText(text)
    Files.move(temp.toPath(), toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE)
}
