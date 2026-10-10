package com.lyra.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.unit.DpSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Window
import androidx.compose.ui.window.WindowPosition
import androidx.compose.ui.window.application
import androidx.compose.ui.window.rememberWindowState
import coil3.PlatformContext
import coil3.SingletonImageLoader
import com.lyra.desktop.system.MemorySaver
import com.lyra.desktop.ui.FullScreenPlayer
import com.lyra.desktop.ui.LyraActions
import com.lyra.desktop.ui.MainContent
import com.lyra.desktop.ui.Navigator
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.ui.components.PlayingBars
import com.sun.management.GarbageCollectionNotificationInfo
import kotlinx.coroutines.delay
import java.io.File
import java.lang.management.ManagementFactory
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import javax.management.NotificationEmitter
import javax.management.openmbean.CompositeData

/**
 * Mide la memoria de Lyra para Windows con una ventana de verdad: `gradlew :desktop:sondaMemoria`.
 * La ventana se abre fuera de la pantalla y sin coger el foco; recorre el inicio, búsquedas, la
 * biblioteca y la pantalla completa, pone música sin sonido (la cola de la versión de pruebas), se
 * esconde como en la bandeja y apunta en cada paso lo que enseñaría el Administrador de tareas.
 * Opciones (-P…): `opciones="-Xmx…"` (las de Java), `etiqueta=…` (nombre del resultado),
 * `imagenes=antes` (la caché de portadas de antes: un 20 % del máximo de Java) y `soltar=0` (al
 * esconderla no se suelta nada, como antes). Los resultados quedan en desktop/build/memoria.
 */
fun main() {
    System.setProperty("lyra.dev", "1")
    val label = System.getProperty("sonda.etiqueta") ?: "prueba"
    val oldImages = System.getProperty("sonda.imagenes") == "antes"
    val releaseOnHide = System.getProperty("sonda.soltar") != "0"
    val app = AppContainer()
    Lyra.app = app
    SingletonImageLoader.setSafe { context ->
        MemorySaver.imageLoader(context, { app.http }, if (oldImages) (Runtime.getRuntime().maxMemory() * 0.2).toLong() else MemorySaver.IMAGE_MEMORY_BYTES)
    }
    val wasMuted = app.settings.current.muted
    app.settings.update { it.copy(muted = true) }
    app.player.restore()
    val nav = Navigator()
    val actions = LyraActions(app, nav)
    Lyra.actions = actions

    val gc = GcWatch()
    val frames = AtomicInteger()
    var finished = false
    // Si algo cierra la sonda antes de tiempo, quién fue.
    Runtime.getRuntime().addShutdownHook(
        Thread {
            if (finished) return@Thread
            System.err.println("== la sonda se cierra antes de terminar")
            for ((thread, stack) in Thread.getAllStackTraces()) {
                if (stack.any { "Shutdown" in it.className || "exit" in it.methodName.lowercase() }) {
                    System.err.println("hilo ${thread.name}:\n  " + stack.joinToString("\n  "))
                }
            }
        },
    )
    val rows = mutableListOf<String>()
    rows += "%-22s %8s %8s %8s %9s %8s %7s %8s %6s %5s %10s %6s".format(
        "paso", "memoria", "privada", "total", "java", "clases", "código", "portadas", "hilos", "gc", "pausa máx", "cpu",
    )
    println("== sonda de memoria: $label · java ${System.getProperty("java.version")} · ${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")}")

    application {
        var visible by remember { mutableStateOf(true) }
        var full by remember { mutableStateOf(false) }
        var showContent by remember { mutableStateOf(true) }
        var animation by remember { mutableStateOf("") }
        val width = System.getProperty("sonda.ancho")?.toIntOrNull() ?: 1280
        val height = System.getProperty("sonda.alto")?.toIntOrNull() ?: 820
        val windowState = rememberWindowState(position = WindowPosition(4200.dp, 120.dp), size = DpSize(width.dp, height.dp))
        Window(
            onCloseRequest = ::exitApplication,
            visible = visible,
            focusable = false,
            title = "Lyra · sonda de memoria",
            state = windowState,
        ) {
            LaunchedEffect(Unit) { println("dibujo: ${window.renderApi}") }
            Box(Modifier.fillMaxSize().drawWithContent { frames.incrementAndGet(); drawContent() }) {
                if (showContent) {
                    if (full) FullScreenPlayer(actions, onExit = {}) else MainContent(actions, onToggleMini = {})
                }
                // Lo que cuesta dibujar una animación que no para (unas barritas, o un «cargando»).
                when (animation) {
                    "barras" -> PlayingBars(true, Modifier.align(Alignment.Center).padding(4.dp).size(14.dp))
                    "continua" -> CircularProgressIndicator(Modifier.align(Alignment.Center).size(32.dp))
                }
            }
        }
        LaunchedEffect(Unit) {
            suspend fun step(name: String, seconds: Int, action: () -> Unit = {}) {
                action()
                delay(seconds * 1000L)
                val row = measure(name, gc, seconds)
                rows += row
                println("$row  (cuadros: ${frames.getAndSet(0)})")
                nativeMemory()?.let {
                    rows += "    java por dentro: $it"
                    println(rows.last())
                }
            }
            step("abrir (inicio)", 15)
            step("buscar", 10) { nav.navigate(Screen.Search("bad bunny")) }
            step("buscar otra", 10) { nav.navigate(Screen.Search("rosalia")) }
            step("me gusta", 6) { nav.navigate(Screen.Liked) }
            step("historial", 6) { nav.navigate(Screen.History) }
            step("sonando", 20) {
                nav.navigate(Screen.Home)
                app.player.resume()
            }
            step("con barritas", 10) { animation = "barras" }
            step("con «cargando»", 10) { animation = "continua" }
            step("sin animación", 10) { animation = "" }
            step("pantalla completa", 12) { full = true }
            step("vuelve", 8) { full = false }
            step("siguiente canción", 15) { app.player.next() }
            step("minimizada", 15) {
                windowState.isMinimized = true
                if (releaseOnHide) MemorySaver.release()
            }
            step("restaurada", 6) { windowState.isMinimized = false }
            step("bandeja", 15) {
                visible = false
                if (releaseOnHide) {
                    showContent = false
                    MemorySaver.release()
                }
            }
            step("bandeja 1 min", 60)
            step("abrir otra vez", 10) {
                showContent = true
                visible = true
            }
            finished = true
            app.settings.update { it.copy(muted = wasMuted) }
            app.player.pause()
            val out = File("build/memoria").apply { mkdirs() }
            File(out, "$label.txt").writeText(
                "java ${System.getProperty("java.version")} · ${ManagementFactory.getRuntimeMXBean().inputArguments.joinToString(" ")}\n" +
                    "imágenes=${if (oldImages) "antes" else "nuevo"} · soltar=$releaseOnHide\n" + rows.joinToString("\n") + "\n",
            )
            println(rows.joinToString("\n"))
            app.shutdown()
            exitApplication()
        }
    }
}

private fun mb(bytes: Long) = "%.0f".format(bytes / 1048576.0)

private var lastCpuNanos = 0L

private fun measure(name: String, gc: GcWatch, seconds: Int): String {
    val usage = MemorySaver.usage()
    val heap = ManagementFactory.getMemoryMXBean().heapMemoryUsage
    val pools = ManagementFactory.getMemoryPoolMXBeans()
    val meta = pools.filter { it.name == "Metaspace" || it.name == "Compressed Class Space" }.sumOf { it.usage.committed }
    val code = pools.filter { it.name.startsWith("CodeHeap") || it.name == "Code Cache" }.sumOf { it.usage.committed }
    val images = runCatching { SingletonImageLoader.get(PlatformContext.INSTANCE).memoryCache?.size ?: 0L }.getOrDefault(0L)
    val threads = ManagementFactory.getThreadMXBean().threadCount
    val (count, maxPause) = gc.take()
    // Procesador gastado en el paso: % de un núcleo.
    val cpuNanos = (ManagementFactory.getOperatingSystemMXBean() as com.sun.management.OperatingSystemMXBean).processCpuTime
    val cpu = (cpuNanos - lastCpuNanos) / 1e7 / seconds
    lastCpuNanos = cpuNanos
    return "%-22s %8s %8s %8s %4s/%-4s %8s %7s %8s %6d %5d %8d ms %5.0f%%".format(
        name,
        usage?.let { mb(it.privateWorkingSet) } ?: "?",
        usage?.let { mb(it.privateBytes) } ?: "?",
        usage?.let { mb(it.workingSet) } ?: "?",
        mb(heap.used), mb(heap.committed),
        mb(meta), mb(code), mb(images), threads, count, maxPause, cpu,
    )
}

/**
 * Con -XX:NativeMemoryTracking=summary: lo que Java tiene reservado por dentro, por partes (MB). Lo
 * que falte hasta la memoria privada del proceso es de fuera de Java (dibujo, FFmpeg, Windows…).
 */
private fun nativeMemory(): String? = runCatching {
    val server = ManagementFactory.getPlatformMBeanServer()
    val text = server.invoke(
        javax.management.ObjectName("com.sun.management:type=DiagnosticCommand"),
        "vmNativeMemory", arrayOf<Any>(arrayOf("summary")), arrayOf(Array<String>::class.java.name),
    ) as String
    if ("not enabled" in text) return null
    val parts = Regex("""-\s+(.+?) \(reserved=\d+KB, committed=(\d+)KB""").findAll(text)
        .map { it.groupValues[1].trim() to it.groupValues[2].toLong() / 1024 }
        .filter { it.second >= 1 }
        .joinToString(" · ") { "${it.first} ${it.second}" }
    val total = Regex("""Total: reserved=\d+KB, committed=(\d+)KB""").find(text)?.groupValues?.get(1)?.toLong()?.div(1024)
    "total $total · $parts"
}.getOrNull()

/** Cuántas veces ha parado Java para recoger basura y la parada más larga (desde la última vez). */
private class GcWatch {
    private val count = AtomicInteger()
    private val maxPause = AtomicLong()

    init {
        for (bean in ManagementFactory.getGarbageCollectorMXBeans()) {
            (bean as? NotificationEmitter)?.addNotificationListener({ notification, _ ->
                if (notification.type != GarbageCollectionNotificationInfo.GARBAGE_COLLECTION_NOTIFICATION) return@addNotificationListener
                val info = GarbageCollectionNotificationInfo.from(notification.userData as CompositeData)
                // Lo concurrente no para la música; solo cuentan las paradas.
                if ("concurrent" in info.gcCause.lowercase() || "Concurrent" in info.gcName) return@addNotificationListener
                count.incrementAndGet()
                maxPause.accumulateAndGet(info.gcInfo.duration) { a, b -> maxOf(a, b) }
            }, null, null)
        }
    }

    fun take(): Pair<Int, Long> = count.getAndSet(0) to maxPause.getAndSet(0)
}
