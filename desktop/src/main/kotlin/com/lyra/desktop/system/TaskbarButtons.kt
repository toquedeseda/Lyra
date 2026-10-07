package com.lyra.desktop.system

import com.lyra.desktop.ErrorLog
import com.sun.jna.CallbackReference
import com.sun.jna.Function
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.platform.win32.Advapi32Util
import com.sun.jna.platform.win32.Guid
import com.sun.jna.platform.win32.Ole32
import com.sun.jna.platform.win32.User32
import com.sun.jna.platform.win32.WinDef
import com.sun.jna.platform.win32.WinReg
import com.sun.jna.platform.win32.WinUser
import com.sun.jna.ptr.PointerByReference
import com.sun.jna.win32.StdCallLibrary
import org.jetbrains.skia.EncodedImageFormat
import org.jetbrains.skia.Paint
import org.jetbrains.skia.PathBuilder
import org.jetbrains.skia.RRect
import org.jetbrains.skia.Surface
import java.util.concurrent.Executors

/**
 * Los botones de la miniatura de la barra de tareas, como en Spotify: al pasar el ratón por el icono
 * de Lyra salen «Anterior», «Reproducir / Pausa» y «Siguiente».
 *
 * Habla con Windows directamente (ITaskbarList3) en un hilo propio. Los clics llegan a la ventana
 * como mensajes (WM_COMMAND), así que se escuchan los mensajes de la ventana antes que Java. Si algo
 * falla, Lyra sigue igual, solo que sin los botones.
 */
object TaskbarButtons {

    enum class Button { PREVIOUS, PLAY_PAUSE, NEXT }

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lyra-barra-de-tareas").apply { isDaemon = true } }
    private var taskbar: Pointer? = null
    private var hwnd: WinDef.HWND? = null
    @Volatile private var oldProc: Pointer? = null
    @Volatile private var taskbarCreated = 0
    @Volatile private var onClick: (Button) -> Unit = {}
    private var added = false
    private var lastResult = 0
    private var playing: Boolean? = null
    private val icons = HashMap<String, WinDef.HICON>()

    private interface User32Icons : StdCallLibrary {
        fun CreateIconFromResourceEx(bits: ByteArray, size: Int, icon: Boolean, version: Int, cx: Int, cy: Int, flags: Int): WinDef.HICON?
    }

    private val user32Icons: User32Icons by lazy { Native.load("user32", User32Icons::class.java) }

    // ------------------------------------------------------------------ arrancar

    /** Pone los botones en la ventana [window] de Lyra. [onPressed] recibe los que se pulsen. */
    fun start(window: Pointer, onPressed: (Button) -> Unit) {
        onClick = onPressed
        val handle = WinDef.HWND(window)
        runCatching {
            // Explorer avisa con este mensaje cuando crea (o vuelve a crear) el botón de la barra.
            taskbarCreated = User32.INSTANCE.RegisterWindowMessage("TaskbarButtonCreated")
            // Primero se apunta a quién pasarle los mensajes, y luego se engancha (sin hueco entre medias).
            oldProc = Pointer(User32.INSTANCE.GetWindowLongPtr(handle, GWLP_WNDPROC).toLong())
            val previous = User32.INSTANCE.SetWindowLongPtr(handle, GWLP_WNDPROC, CallbackReference.getFunctionPointer(proc))
            check(previous != null && Pointer.nativeValue(previous) != 0L) { "SetWindowLongPtr" }
            oldProc = previous
        }.onFailure {
            ErrorLog.record("Barra de tareas", "No se pudieron poner los botones de la miniatura", it)
            return
        }
        worker.execute {
            runCatching {
                Ole32.INSTANCE.CoInitializeEx(null, COINIT_APARTMENTTHREADED)
                val created = PointerByReference()
                val result = Ole32.INSTANCE.CoCreateInstance(
                    Guid.GUID.fromString(CLSID_TASKBAR_LIST), null, CLSCTX_INPROC_SERVER, Guid.GUID.fromString(IID_TASKBAR_LIST3), created,
                )
                check(result.toInt() >= 0) { "CoCreateInstance 0x%08X".format(result.toInt()) }
                taskbar = created.value
                call(created.value, 3) // HrInit
                hwnd = handle
                addButtons()
                // Si el botón de la barra aún no existía, se reintenta un poco (o llega «TaskbarButtonCreated»).
                repeat(5) {
                    if (added) return@runCatching
                    Thread.sleep(2_000)
                    addButtons()
                }
                if (!added) ErrorLog.record("Barra de tareas", "Windows no dejó poner los botones de la miniatura (0x%08X)".format(lastResult))
            }.onFailure { ErrorLog.record("Barra de tareas", "No se pudieron poner los botones de la miniatura", it) }
        }
    }

    /** Lo que suena: cambia el botón del centro (null: no hay nada puesto y se apagan). */
    fun setPlaying(value: Boolean?) {
        worker.execute {
            playing = value
            if (added) runCatching { update() }
        }
    }

    // ------------------------------------------------------------------ los mensajes de la ventana

    // En un campo para que no se lo lleve el recolector de basura (Windows lo llama siempre).
    private val proc = object : WinUser.WindowProc {
        override fun callback(hwnd: WinDef.HWND, uMsg: Int, wParam: WinDef.WPARAM, lParam: WinDef.LPARAM): WinDef.LRESULT {
            try {
                if (uMsg == WM_COMMAND) {
                    val w = wParam.toLong()
                    if (((w ushr 16) and 0xFFFF).toInt() == THBN_CLICKED) {
                        val button = when ((w and 0xFFFF).toInt()) {
                            ID_PREVIOUS -> Button.PREVIOUS
                            ID_PLAY_PAUSE -> Button.PLAY_PAUSE
                            ID_NEXT -> Button.NEXT
                            else -> null
                        }
                        if (button != null) {
                            runCatching { onClick(button) }
                            return WinDef.LRESULT(0)
                        }
                    }
                } else if (uMsg == taskbarCreated && taskbarCreated != 0) {
                    // Explorer se ha reiniciado o la ventana vuelve de la bandeja: hay que ponerlos otra vez.
                    worker.execute {
                        added = false
                        runCatching { addButtons() }
                    }
                }
            } catch (_: Throwable) {
                // Nunca se corta un mensaje de la ventana por esto.
            }
            val previous = oldProc ?: return User32.INSTANCE.DefWindowProc(hwnd, uMsg, wParam, lParam)
            return User32.INSTANCE.CallWindowProc(previous, hwnd, uMsg, wParam, lParam)
        }
    }

    // ------------------------------------------------------------------ los botones

    private fun addButtons() {
        val list = taskbar ?: return
        val handle = hwnd ?: return
        val buttons = buttons()
        // Solo se pueden añadir una vez por botón de la barra; si ya estaban, se actualizan.
        lastResult = call(list, 15, handle.pointer, 3, buttons) // ThumbBarAddButtons
        added = lastResult >= 0 || call(list, 16, handle.pointer, 3, buttons) >= 0 // ThumbBarUpdateButtons
    }

    private fun update() {
        val list = taskbar ?: return
        val handle = hwnd ?: return
        call(list, 16, handle.pointer, 3, buttons()) // ThumbBarUpdateButtons
    }

    /** Los tres THUMBBUTTON seguidos en memoria (552 bytes cada uno en 64 bits). */
    private fun buttons(): Memory {
        val light = lightTheme()
        val enabled = playing != null
        val memory = Memory(BUTTON_SIZE * 3L).apply { clear() }
        fun put(index: Int, id: Int, kind: String, tip: String) {
            val base = index * BUTTON_SIZE.toLong()
            memory.setInt(base, THB_ICON or THB_TOOLTIP or THB_FLAGS)
            memory.setInt(base + 4, id)
            memory.setPointer(base + 16, icon(kind, light)?.pointer ?: Pointer.NULL)
            memory.setWideString(base + 24, tip)
            memory.setInt(base + 544, if (enabled) THBF_ENABLED else THBF_DISABLED)
        }
        put(0, ID_PREVIOUS, "previous", "Anterior")
        put(1, ID_PLAY_PAUSE, if (playing == true) "pause" else "play", if (playing == true) "Pausa" else "Reproducir")
        put(2, ID_NEXT, "next", "Siguiente")
        return memory
    }

    /** La barra de tareas en tema claro: los iconos van en oscuro para que se vean. */
    private fun lightTheme(): Boolean = runCatching {
        Advapi32Util.registryGetIntValue(WinReg.HKEY_CURRENT_USER, "Software\\Microsoft\\Windows\\CurrentVersion\\Themes\\Personalize", "SystemUsesLightTheme") == 1
    }.getOrDefault(false)

    private fun icon(kind: String, light: Boolean): WinDef.HICON? {
        val key = "$kind-$light"
        icons[key]?.let { return it }
        val size = User32.INSTANCE.GetSystemMetrics(SM_CXSMICON).takeIf { it in 12..64 } ?: 16
        val png = drawIcon(kind, size, if (light) 0xFF1C1C1F.toInt() else 0xFFF3F3F1.toInt())
        val handle = user32Icons.CreateIconFromResourceEx(png, png.size, true, 0x00030000, size, size, 0) ?: return null
        icons[key] = handle
        return handle
    }

    /** Los iconos, dibujados aquí mismo (nítidos a cualquier tamaño): ⏮ ▶ ⏸ ⏭. */
    internal fun drawIcon(kind: String, size: Int, color: Int): ByteArray {
        val surface = Surface.makeRasterN32Premul(size, size)
        val canvas = surface.canvas
        val s = size.toFloat()
        val paint = Paint().apply {
            this.color = color
            isAntiAlias = true
        }
        fun triangle(x1: Float, y1: Float, x2: Float, y2: Float, x3: Float, y3: Float) {
            canvas.drawPath(PathBuilder().moveTo(x1 * s, y1 * s).lineTo(x2 * s, y2 * s).lineTo(x3 * s, y3 * s).closePath().detach(), paint)
        }
        fun bar(left: Float, right: Float, top: Float = 0.2f, bottom: Float = 0.8f) {
            canvas.drawRRect(RRect.makeLTRB(left * s, top * s, right * s, bottom * s, s * 0.05f), paint)
        }
        when (kind) {
            "play" -> triangle(0.3f, 0.18f, 0.3f, 0.82f, 0.82f, 0.5f)
            "pause" -> {
                bar(0.24f, 0.42f, 0.18f, 0.82f)
                bar(0.58f, 0.76f, 0.18f, 0.82f)
            }
            "next" -> {
                triangle(0.2f, 0.2f, 0.2f, 0.8f, 0.64f, 0.5f)
                bar(0.68f, 0.8f)
            }
            "previous" -> {
                triangle(0.8f, 0.2f, 0.8f, 0.8f, 0.36f, 0.5f)
                bar(0.2f, 0.32f)
            }
        }
        return surface.makeImageSnapshot().encodeToData(EncodedImageFormat.PNG)!!.bytes
    }

    // ------------------------------------------------------------------ ayudas COM

    /** Llama al método [index] de la tabla de la interfaz [self]. */
    private fun call(self: Pointer, index: Int, vararg args: Any?): Int {
        val table = self.getPointer(0)
        val function = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE))
        return function.invokeInt(arrayOf<Any?>(self, *args))
    }

    private const val CLSID_TASKBAR_LIST = "{56FDF344-FD6D-11d0-958A-006097C9A090}"
    private const val IID_TASKBAR_LIST3 = "{EA1AFB91-9E28-4B86-90E9-9E9F8A5EEFAF}"
    private const val COINIT_APARTMENTTHREADED = 0x2
    private const val CLSCTX_INPROC_SERVER = 0x1
    private const val GWLP_WNDPROC = -4
    private const val WM_COMMAND = 0x0111
    private const val THBN_CLICKED = 0x1800
    private const val SM_CXSMICON = 49
    private const val BUTTON_SIZE = 552
    private const val THB_ICON = 0x2
    private const val THB_TOOLTIP = 0x4
    private const val THB_FLAGS = 0x8
    private const val THBF_ENABLED = 0x0
    private const val THBF_DISABLED = 0x1
    private const val ID_PREVIOUS = 1
    private const val ID_PLAY_PAUSE = 2
    private const val ID_NEXT = 3
}
