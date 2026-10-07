package com.lyra.desktop.system

import com.lyra.desktop.ErrorLog
import com.lyra.desktop.Paths
import com.lyra.desktop.update.Updater
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import java.io.RandomAccessFile
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.channels.FileLock
import kotlin.concurrent.thread

/** Detalles de Windows: barra de título oscura, abrir al encender el PC y una sola ventana abierta. */
object WindowsSystem {

    private interface Dwmapi : Library {
        fun DwmSetWindowAttribute(hwnd: WinDef.HWND, attribute: Int, value: Pointer, size: Int): Int
    }

    private val dwm: Dwmapi? by lazy { runCatching { Native.load("dwmapi", Dwmapi::class.java) }.getOrNull() }

    /**
     * Barra de título del color de Lyra (negra), como las apps modernas de Windows 11.
     * En Windows 10 se queda en modo oscuro normal.
     */
    fun styleTitleBar(window: java.awt.Window, argb: Long) {
        val api = dwm ?: return
        runCatching {
            val hwnd = WinDef.HWND(Native.getWindowPointer(window))
            val on = Memory(4).apply { setInt(0, 1) }
            api.DwmSetWindowAttribute(hwnd, DWMWA_USE_IMMERSIVE_DARK_MODE, on, 4)
            val r = ((argb shr 16) and 0xFF).toInt()
            val g = ((argb shr 8) and 0xFF).toInt()
            val b = (argb and 0xFF).toInt()
            val colorRef = Memory(4).apply { setInt(0, (b shl 16) or (g shl 8) or r) }
            api.DwmSetWindowAttribute(hwnd, DWMWA_CAPTION_COLOR, colorRef, 4)
            val text = Memory(4).apply { setInt(0, 0x00F1F3F3) }
            api.DwmSetWindowAttribute(hwnd, DWMWA_TEXT_COLOR, text, 4)
        }
    }

    private const val DWMWA_USE_IMMERSIVE_DARK_MODE = 20
    private const val DWMWA_CAPTION_COLOR = 35
    private const val DWMWA_TEXT_COLOR = 36

    // ------------------------------------------------------------------ abrir al encender

    private const val RUN_KEY = "Software\\Microsoft\\Windows\\CurrentVersion\\Run"

    /** Solo si está instalada (hace falta el Lyra.exe al que apuntar). */
    val canStartWithWindows: Boolean get() = Updater.launcherPath() != null

    fun setStartWithWindows(enabled: Boolean) {
        val launcher = Updater.launcherPath() ?: return
        runCatching {
            if (enabled) {
                Advapi32Util.registrySetStringValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, "Lyra", "\"$launcher\" --minimized")
            } else if (Advapi32Util.registryValueExists(WinReg.HKEY_CURRENT_USER, RUN_KEY, "Lyra")) {
                Advapi32Util.registryDeleteValue(WinReg.HKEY_CURRENT_USER, RUN_KEY, "Lyra")
            }
        }.onFailure { ErrorLog.record("Ajustes", "No se pudo cambiar el inicio con Windows", it) }
    }

    // ------------------------------------------------------------------ una sola Lyra abierta

    private var lock: FileLock? = null
    private var server: ServerSocket? = null

    /**
     * True si esta es la única Lyra abierta. Si ya había otra, le pasa [args] (para que se
     * enseñe y abra el enlace, si lo hay) y devuelve false.
     */
    fun acquireSingleInstance(args: List<String>, onMessage: (List<String>) -> Unit): Boolean {
        val channel = RandomAccessFile(Paths.instanceLock, "rw").channel
        val acquired = runCatching { channel.tryLock() }.getOrNull()
        if (acquired == null) {
            runCatching {
                val port = Paths.instancePort.readText().trim().toInt()
                Socket(InetAddress.getLoopbackAddress(), port).use { socket ->
                    socket.getOutputStream().write((listOf("mostrar") + args).joinToString("\t").plus("\n").toByteArray(Charsets.UTF_8))
                }
            }
            return false
        }
        lock = acquired
        runCatching {
            val socket = ServerSocket(0, 8, InetAddress.getLoopbackAddress())
            server = socket
            Paths.instancePort.writeText(socket.localPort.toString())
            thread(name = "lyra-una-sola", isDaemon = true) {
                while (true) {
                    val client = runCatching { socket.accept() }.getOrNull() ?: break
                    runCatching {
                        client.use { c ->
                            val line = c.getInputStream().bufferedReader(Charsets.UTF_8).readLine().orEmpty()
                            val parts = line.split('\t')
                            if (parts.firstOrNull() == "mostrar") onMessage(parts.drop(1).filter { it.isNotBlank() })
                        }
                    }
                }
            }
        }
        return true
    }
}
