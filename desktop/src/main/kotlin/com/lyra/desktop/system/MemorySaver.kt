package com.lyra.desktop.system

import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import coil3.memory.MemoryCache
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import coil3.request.crossfade
import com.lyra.desktop.Paths
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import okhttp3.OkHttpClient
import okio.Path.Companion.toOkioPath

/**
 * Que Lyra gaste poca memoria. Las portadas ya dibujadas que se guardan en memoria tienen un tope
 * fijo (las demás se vuelven a leer del disco, que es casi igual de rápido) y, con la ventana
 * escondida en la bandeja, se suelta lo que solo hacía falta para verla y se le devuelve a Windows.
 */
object MemorySaver {
    /** Portadas ya dibujadas que se guardan: unas 300 de las de las listas o 40 de las grandes. */
    const val IMAGE_MEMORY_BYTES = 32L * 1024 * 1024

    fun imageLoader(context: PlatformContext, http: () -> OkHttpClient, memoryBytes: Long = IMAGE_MEMORY_BYTES): ImageLoader =
        ImageLoader.Builder(context)
            .components { add(OkHttpNetworkFetcherFactory(callFactory = http)) }
            .memoryCache { MemoryCache.Builder().maxSizeBytes(memoryBytes).build() }
            .diskCache { DiskCache.Builder().directory(Paths.imageCache.toOkioPath()).maxSizeBytes(300L * 1024 * 1024).build() }
            .crossfade(true)
            .build()

    /** La memoria de Lyra: [privateWorkingSet] es la que enseña el Administrador de tareas. */
    class Usage(val privateWorkingSet: Long, val privateBytes: Long, val workingSet: Long)

    private interface Kernel32Memory : Library {
        fun GetCurrentProcess(): Pointer
        fun K32GetProcessMemoryInfo(process: Pointer, counters: Pointer, size: Int): Boolean
        fun SetProcessWorkingSetSize(process: Pointer, minimum: Long, maximum: Long): Boolean
    }

    private val kernel32: Kernel32Memory? by lazy { runCatching { Native.load("kernel32", Kernel32Memory::class.java) }.getOrNull() }

    fun usage(): Usage? = runCatching {
        val lib = kernel32 ?: return null
        // PROCESS_MEMORY_COUNTERS_EX2 (Windows 10 1809 o más nuevo).
        val counters = Memory(COUNTERS_SIZE.toLong()).apply { clear() }
        counters.setInt(0, COUNTERS_SIZE)
        if (!lib.K32GetProcessMemoryInfo(lib.GetCurrentProcess(), counters, COUNTERS_SIZE)) return null
        Usage(privateWorkingSet = counters.getLong(80), privateBytes = counters.getLong(72), workingSet = counters.getLong(16))
    }.getOrNull()

    /**
     * Mientras no se usa Lyra: fuera las portadas guardadas en memoria (las que se ven siguen), Java
     * recoge lo que sobra (una parada de unas centésimas; la música lleva más guardado y no se corta)
     * y Windows se lleva lo que no se está usando. Si algo vuelve a hacer falta, lo trae al momento.
     */
    fun release() {
        runCatching { SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.clear() }
        System.gc()
        trimWorkingSet()
    }

    fun trimWorkingSet() {
        runCatching { kernel32?.let { it.SetProcessWorkingSetSize(it.GetCurrentProcess(), -1, -1) } }
    }

    private const val COUNTERS_SIZE = 96
}
