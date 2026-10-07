package com.lyra.desktop.system

import com.lyra.desktop.ErrorLog
import com.sun.jna.Callback
import com.sun.jna.Function
import com.sun.jna.Library
import com.sun.jna.Memory
import com.sun.jna.Native
import com.sun.jna.Pointer
import com.sun.jna.WString
import com.sun.jna.ptr.IntByReference
import com.sun.jna.ptr.PointerByReference
import java.util.UUID
import java.util.concurrent.Executors

/**
 * Los controles de música de Windows (System Media Transport Controls), hablando directamente con
 * Windows: las teclas multimedia del teclado, la tarjeta que sale al cambiar el volumen, la pantalla
 * de bloqueo y los auriculares Bluetooth controlan Lyra, y ahí sale la canción con su portada.
 *
 * Todo va en un hilo propio (COM multihilo). Si algo falla (Windows muy antiguo), no pasa nada:
 * Lyra funciona igual, solo que sin esto.
 */
object MediaControls {

    enum class Button { PLAY, PAUSE, STOP, NEXT, PREVIOUS }

    @Volatile var active = false
        private set

    private val worker = Executors.newSingleThreadExecutor { Thread(it, "lyra-controles-windows").apply { isDaemon = true } }
    private var smtc: Pointer? = null
    private var updater: Pointer? = null
    private var music: Pointer? = null
    private var music2: Pointer? = null
    private var uriFactory: Pointer? = null
    private var streamStatics: Pointer? = null
    private var onButton: (Button) -> Unit = {}
    private var lastCover: String? = null

    /** Lo último que se pidió enseñar (por si llega antes de que Windows esté listo). */
    private var pending: (() -> Unit)? = null
    private var pendingStatus: Boolean? = null
    private var statusRequested = false

    private interface Combase : Library {
        fun RoInitialize(initType: Int): Int
        fun WindowsCreateString(source: WString, length: Int, string: PointerByReference): Int
        fun WindowsDeleteString(string: Pointer?): Int
        fun RoGetActivationFactory(classId: Pointer, iid: Pointer, factory: PointerByReference): Int
    }

    private val combase: Combase by lazy { Native.load("combase", Combase::class.java) }

    // ------------------------------------------------------------------ arrancar

    /** Se engancha a la ventana [hwnd] de Lyra. [onPressed] recibe los botones que se pulsen. */
    fun start(hwnd: Pointer, onPressed: (Button) -> Unit) {
        onButton = onPressed
        worker.execute {
            runCatching { init(hwnd) }.onFailure {
                active = false
                ErrorLog.record("Controles de Windows", "No se pudieron activar las teclas multimedia", it)
            }
        }
    }

    private fun init(hwnd: Pointer) {
        val ro = combase.RoInitialize(1) // multihilo
        if (ro < 0 && ro != RPC_E_CHANGED_MODE) error("RoInitialize ${hex(ro)}")

        val interop = activationFactory("Windows.Media.SystemMediaTransportControls", IID_SMTC_INTEROP)
        val controls = PointerByReference()
        check(call(interop, 6, hwnd, guid(IID_SMTC), controls), "GetForWindow")
        val smtc = controls.value
        this.smtc = smtc

        // Botones que se pueden usar.
        call(smtc, 11, 1) // put_IsEnabled
        call(smtc, 13, 1) // put_IsPlayEnabled
        call(smtc, 17, 1) // put_IsPauseEnabled
        call(smtc, 15, 1) // put_IsStopEnabled
        call(smtc, 25, 1) // put_IsPreviousEnabled
        call(smtc, 27, 1) // put_IsNextEnabled

        val display = PointerByReference()
        check(call(smtc, 8, display), "get_DisplayUpdater")
        updater = display.value
        call(display.value, 7, MEDIA_TYPE_MUSIC) // put_Type
        val props = PointerByReference()
        check(call(display.value, 12, props), "get_MusicProperties")
        music = props.value
        val props2 = PointerByReference()
        if (call(props.value, 0, guid(IID_MUSIC_PROPERTIES_2), props2) >= 0) music2 = props2.value

        // Para las portadas: Uri y RandomAccessStreamReference.
        uriFactory = runCatching { activationFactory("Windows.Foundation.Uri", IID_URI_FACTORY) }.getOrNull()
        streamStatics = runCatching { activationFactory("Windows.Storage.Streams.RandomAccessStreamReference", IID_STREAM_REFERENCE_STATICS) }.getOrNull()

        // El aviso de "han pulsado un botón".
        val token = Memory(8)
        check(call(smtc, 32, handler, token), "add_ButtonPressed")
        active = true
        // Lo que se pidió antes de estar listo.
        pending?.invoke()
        pending = null
        if (statusRequested) applyStatus(pendingStatus)
    }

    // ------------------------------------------------------------------ actualizar

    /** Lo que suena (título, artista, álbum y portada). Null: nada. */
    fun update(title: String?, artist: String?, album: String?, coverUrl: String?) {
        worker.execute {
            if (!active) {
                pending = { update(title, artist, album, coverUrl) }
                return@execute
            }
            runCatching {
                val display = updater ?: return@runCatching
                if (title == null) {
                    call(display, 16) // ClearAll
                    call(display, 7, MEDIA_TYPE_MUSIC)
                    call(display, 17) // Update
                    lastCover = null
                    return@runCatching
                }
                music?.let { props ->
                    withHString(title) { call(props, 7, it) } // put_Title
                    withHString(artist.orEmpty()) { call(props, 11, it) } // put_Artist
                    withHString(artist.orEmpty()) { call(props, 9, it) } // put_AlbumArtist
                }
                music2?.let { props -> withHString(album.orEmpty()) { call(props, 7, it) } } // put_AlbumTitle
                if (coverUrl != lastCover) {
                    lastCover = coverUrl
                    setThumbnail(display, coverUrl)
                }
                call(display, 17) // Update
            }.onFailure { ErrorLog.record("Controles de Windows", "No se pudo actualizar la canción", it) }
        }
    }

    fun setPlaying(playing: Boolean?) {
        worker.execute {
            statusRequested = true
            pendingStatus = playing
            if (active) applyStatus(playing)
        }
    }

    private fun applyStatus(playing: Boolean?) {
        val controls = smtc ?: return
        val status = when (playing) {
            null -> STATUS_STOPPED
            true -> STATUS_PLAYING
            false -> STATUS_PAUSED
        }
        runCatching { call(controls, 7, status) } // put_PlaybackStatus
    }

    private fun setThumbnail(display: Pointer, url: String?) {
        val factory = uriFactory ?: return
        val statics = streamStatics ?: return
        if (url == null || !url.startsWith("http")) {
            call(display, 11, Pointer.NULL)
            return
        }
        val uri = PointerByReference()
        val created = withHString(url) { call(factory, 6, it, uri) } // CreateUri
        if (created < 0) return
        val reference = PointerByReference()
        if (call(statics, 7, uri.value, reference) >= 0) { // CreateFromUri
            call(display, 11, reference.value) // put_Thumbnail
            release(reference.value)
        }
        release(uri.value)
    }

    // ------------------------------------------------------------------ el aviso de botón (objeto COM hecho a mano)

    private interface QueryInterface : Callback {
        fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int
    }

    private interface RefCount : Callback {
        fun invoke(self: Pointer): Int
    }

    private interface Invoke : Callback {
        fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int
    }

    // Se guardan en campos para que no se los lleve el recolector de basura.
    private val queryInterface = object : QueryInterface {
        override fun invoke(self: Pointer, riid: Pointer, out: Pointer): Int {
            val asked = readGuid(riid)
            return if (asked == IID_UNKNOWN || asked == IID_AGILE || asked == IID_BUTTON_HANDLER) {
                out.setPointer(0, self)
                0
            } else {
                out.setPointer(0, Pointer.NULL)
                E_NOINTERFACE
            }
        }
    }
    private val addRef = object : RefCount {
        override fun invoke(self: Pointer): Int = 1
    }
    private val release = object : RefCount {
        override fun invoke(self: Pointer): Int = 1
    }
    private val invoke = object : Invoke {
        override fun invoke(self: Pointer, sender: Pointer?, args: Pointer?): Int {
            if (args == null) return 0
            val value = IntByReference()
            if (call(args, 6, value) >= 0) { // get_Button
                val button = when (value.value) {
                    0 -> Button.PLAY
                    1 -> Button.PAUSE
                    2 -> Button.STOP
                    6 -> Button.NEXT
                    7 -> Button.PREVIOUS
                    else -> null
                }
                if (button != null) runCatching { onButton(button) }
            }
            return 0
        }
    }

    private val vtable: Memory = Memory(4L * Native.POINTER_SIZE).apply {
        setPointer(0L, com.sun.jna.CallbackReference.getFunctionPointer(queryInterface))
        setPointer(1L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(addRef))
        setPointer(2L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(release))
        setPointer(3L * Native.POINTER_SIZE, com.sun.jna.CallbackReference.getFunctionPointer(invoke))
    }

    private val handler: Memory = Memory(Native.POINTER_SIZE.toLong()).apply { setPointer(0, vtable) }

    // ------------------------------------------------------------------ ayudas COM / WinRT

    /** Llama al método [index] de la tabla de la interfaz [self]. */
    private fun call(self: Pointer, index: Int, vararg args: Any?): Int {
        val table = self.getPointer(0)
        val function = Function.getFunction(table.getPointer(index.toLong() * Native.POINTER_SIZE))
        return function.invokeInt(arrayOf<Any?>(self, *args))
    }

    private fun release(self: Pointer?) {
        if (self != null) runCatching { call(self, 2) }
    }

    private fun check(result: Int, what: String) {
        if (result < 0) error("$what falló (${hex(result)})")
    }

    private fun activationFactory(className: String, iid: String): Pointer {
        val factory = PointerByReference()
        val result = withHString(className) { combase.RoGetActivationFactory(it, guid(iid), factory) }
        check(result, "RoGetActivationFactory($className)")
        return factory.value
    }

    private inline fun <T> withHString(text: String, block: (Pointer) -> T): T {
        val ref = PointerByReference()
        check(combase.WindowsCreateString(WString(text), text.length, ref), "WindowsCreateString")
        try {
            return block(ref.value ?: Pointer.NULL)
        } finally {
            combase.WindowsDeleteString(ref.value)
        }
    }

    /** Un GUID en memoria, con el formato de Windows. */
    private fun guid(text: String): Memory {
        val uuid = UUID.fromString(text)
        val msb = uuid.mostSignificantBits
        val lsb = uuid.leastSignificantBits
        return Memory(16).apply {
            setInt(0, (msb ushr 32).toInt())
            setShort(4, (msb ushr 16).toShort())
            setShort(6, msb.toShort())
            for (i in 0 until 8) setByte(8L + i, (lsb ushr (56 - 8 * i)).toByte())
        }
    }

    private fun readGuid(pointer: Pointer): String {
        val d1 = pointer.getInt(0).toLong() and 0xFFFFFFFFL
        val d2 = pointer.getShort(4).toInt() and 0xFFFF
        val d3 = pointer.getShort(6).toInt() and 0xFFFF
        val d4 = (0 until 8).joinToString("") { "%02x".format(pointer.getByte(8L + it)) }
        return "%08x-%04x-%04x-%s-%s".format(d1, d2, d3, d4.substring(0, 4), d4.substring(4))
    }

    private fun hex(value: Int) = "0x%08X".format(value)

    private const val IID_UNKNOWN = "00000000-0000-0000-c000-000000000046"
    private const val IID_AGILE = "94ea2b94-e9cc-49e0-c0ff-ee64ca8f5b90"
    private const val IID_BUTTON_HANDLER = "0557e996-7b23-5bae-aa81-ea0d671143a4"
    private const val IID_SMTC = "99fa3ff4-1742-42a6-902e-087d41f965ec"
    private const val IID_SMTC_INTEROP = "ddb0472d-c911-4a1f-86d9-dc3d71a95f5a"
    private const val IID_MUSIC_PROPERTIES_2 = "00368462-97d3-44b9-b00f-008afcefaf18"
    private const val IID_URI_FACTORY = "44a9796f-723e-4fdf-a218-033e75b0c084"
    private const val IID_STREAM_REFERENCE_STATICS = "857309dc-3fbf-4e7d-986f-ef3b1a07a964"
    private const val MEDIA_TYPE_MUSIC = 1
    private const val STATUS_STOPPED = 2
    private const val STATUS_PLAYING = 3
    private const val STATUS_PAUSED = 4
    private const val E_NOINTERFACE = 0x80004002.toInt()
    private const val RPC_E_CHANGED_MODE = 0x80010106.toInt()
}
