package com.lyra.music.debug

import android.app.Activity
import android.content.ComponentName
import android.graphics.BitmapFactory
import android.media.MediaMetadata
import android.media.browse.MediaBrowser
import android.media.session.MediaController
import android.net.Uri
import android.os.Bundle
import android.provider.MediaStore
import android.util.Log
import androidx.media3.session.SessionToken
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.guava.await
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * Prueba de Android Auto sin coche (solo en depuración). Se conecta a Lyra como el coche
 * (MediaBrowser clásico), recorre las pestañas, busca, toca cosas y prueba la voz. Lo
 * apunta en logcat (AutoProbe) y en `files/auto_probe.txt`.
 *
 * adb shell am start -n com.lyra.music.debug/com.lyra.music.debug.AutoProbeActivity \
 *     --es steps browse,search --es query "bad bunny" --es open liked --es play shuffle::liked \
 *     --es voice "mis me gusta" --es focus vnd.android.cursor.item/artist
 */
class AutoProbeActivity : Activity() {

    private val scope = MainScope()
    private val log = StringBuilder()
    private lateinit var browser: MediaBrowser

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val steps = intent.getStringExtra("steps")?.split(',')?.map { it.trim() }?.toSet().orEmpty()
        scope.launch {
            runCatching {
                probe(
                    steps = steps,
                    query = intent.getStringExtra("query") ?: "bad bunny",
                    open = intent.getStringExtra("open"),
                    play = intent.getStringExtra("play"),
                    voice = intent.getStringExtra("voice"),
                    focus = intent.getStringExtra("focus"),
                )
            }.onFailure { line("ERROR $it") }
            line("FIN")
            File(filesDir, "auto_probe.txt").writeText(log.toString())
            if (::browser.isInitialized) browser.disconnect()
            finish()
        }
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun line(text: String) {
        Log.i("AutoProbe", text)
        log.appendLine(text)
    }

    private suspend fun probe(steps: Set<String>, query: String, open: String?, play: String?, voice: String?, focus: String?) {
        browser = connect()
        line("Conectado. raíz=${browser.root} extras=${dump(browser.extras)}")

        if ("browse" in steps) {
            val tabs = children(browser.root)
            line("== Pestañas (${tabs.size})")
            tabs.forEach { line(describe(it) + " -> " + icon(it.description.iconUri)) }
            for (tab in tabs) {
                val items = children(tab.mediaId ?: continue)
                line("== ${tab.description.title} (${items.size})")
                items.take(60).forEach { line("  " + describe(it)) }
                items.take(4).forEach { line("    portada «${it.description.title}»: " + icon(it.description.iconUri)) }
            }
        }

        if (open != null) {
            val items = children(open)
            line("== Abrir $open (${items.size})")
            items.take(40).forEach { line("  " + describe(it)) }
            items.take(3).forEach { line("    portada «${it.description.title}»: " + icon(it.description.iconUri)) }
        }

        if ("search" in steps) {
            val results = search(query)
            line("== Buscar «$query» (${results.size})")
            results.forEach { line("  " + describe(it)) }
        }

        val controller = MediaController(this, browser.sessionToken)
        if ("off" in steps) {
            // Empieza sin aleatorio.
            val media3 = media3()
            if (media3.shuffleModeEnabled) controller.transportControls.sendCustomAction("lyra.shuffle", null)
            media3.release()
            delay(800)
        }
        if (play != null) {
            controller.transportControls.playFromMediaId(play, null)
            delay(7_000)
            report(controller, "Tocar $play")
            // ¿Suena solo lo de la lista? (para "shuffle::<lista>" y "<lista>::<canción>")
            val list = when {
                play.startsWith("shuffle::") -> play.removePrefix("shuffle::")
                play.contains("::") && !play.startsWith("radio::") && !play.startsWith("artistradio::") -> play.substringBefore("::")
                else -> null
            }
            if (list != null) checkOnlyFrom(list)
            if ("toggle" in steps) {
                // Como si se pulsara el aleatorio del coche (apagar si está, y encender).
                val media3 = media3()
                if (media3.shuffleModeEnabled) {
                    controller.transportControls.sendCustomAction("lyra.shuffle", null)
                    delay(800)
                }
                controller.transportControls.sendCustomAction("lyra.shuffle", null)
                delay(1_500)
                media3.release()
                checkOrder()
            }
        }
        if (voice != null) {
            val extras = Bundle().apply { focus?.let { putString(MediaStore.EXTRA_MEDIA_FOCUS, it) } }
            controller.transportControls.playFromSearch(voice, extras)
            delay(10_000)
            report(controller, "Voz «$voice»" + (focus?.let { " ($it)" } ?: ""))
        }
    }

    private suspend fun report(controller: MediaController, what: String) {
        val metadata = controller.metadata
        line("== $what")
        line("  suena: ${metadata?.getString(MediaMetadata.METADATA_KEY_TITLE)} — ${metadata?.getString(MediaMetadata.METADATA_KEY_ARTIST)}")
        line("  estado=${controller.playbackState?.state} cola=${controller.queue?.size} sesión=${dump(controller.extras)}")
        controller.playbackState?.customActions?.forEach { line("  botón: ${it.action} «${it.name}»") }
        val media3 = media3()
        line("  aleatorio=${media3.shuffleModeEnabled} repetir=${media3.repeatMode} canciones=${media3.mediaItemCount} índice=${media3.currentMediaItemIndex}")
        media3.release()
    }

    /** Comprueba que la cola tiene solo canciones de [list] (ninguna de fuera). */
    private suspend fun checkOnlyFrom(list: String) {
        val inList = songIdsOf(list)
        val media3 = media3()
        val queue = (0 until media3.mediaItemCount).map { media3.getMediaItemAt(it).mediaId }
        val foreign = queue.filter { it !in inList }
        line("  cola=${queue.size} de la lista=${inList.size} de fuera=${foreign.size} ${foreign.take(5)}")
        media3.release()
    }

    /** Orden real de reproducción: las de la radio (R) tienen que ir detrás de todas las de la lista. */
    private suspend fun checkOrder() {
        val media3 = media3()
        val timeline = media3.currentTimeline
        val order = mutableListOf<Int>()
        var index = media3.currentMediaItemIndex
        while (index != androidx.media3.common.C.INDEX_UNSET && order.size <= timeline.windowCount) {
            order += index
            index = timeline.getNextWindowIndex(index, androidx.media3.common.Player.REPEAT_MODE_OFF, media3.shuffleModeEnabled)
        }
        val marks = order.map { if (media3.getMediaItemAt(it).mediaMetadata.extras?.getBoolean("lyra.radio") == true) "R" else "L" }
        val firstRadio = marks.indexOf("R")
        val ownAfterRadio = if (firstRadio < 0) 0 else marks.drop(firstRadio).count { it == "L" }
        line("  aleatorio=${media3.shuffleModeEnabled} orden desde la actual: ${marks.joinToString("")}")
        line("  de la lista después de la radio: $ownAfterRadio (tiene que ser 0)")
        media3.release()
    }

    /** Canciones de una lista (y de las playlists que tenga dentro, si es una carpeta). */
    private suspend fun songIdsOf(list: String): Set<String> {
        val items = children(list)
        val songs = items.mapNotNull { it.mediaId }
            .filter { it.contains("::") && !it.startsWith("shuffle::") && !it.startsWith("artistradio::") }
            .map { it.substringAfter("::") }
            .toMutableSet()
        items.filter { it.isBrowsable }.forEach { child -> child.mediaId?.let { songs += songIdsOf(it) } }
        return songs
    }

    private suspend fun media3(): androidx.media3.session.MediaController {
        val token = SessionToken(this, ComponentName(this, "com.lyra.music.playback.PlaybackService"))
        return androidx.media3.session.MediaController.Builder(this, token).buildAsync().await()
    }

    private suspend fun connect(): MediaBrowser = suspendCancellableCoroutine { cont ->
        // Las mismas pistas que manda Android Auto al conectarse.
        val hints = Bundle().apply {
            putInt("androidx.media.MediaBrowserCompat.Extras.KEY_ROOT_CHILDREN_LIMIT", 4)
            putBoolean("android.media.browse.CONTENT_STYLE_SUPPORTED", true)
        }
        var created: MediaBrowser? = null
        created = MediaBrowser(
            this,
            ComponentName(this, "com.lyra.music.playback.PlaybackService"),
            object : MediaBrowser.ConnectionCallback() {
                override fun onConnected() {
                    if (cont.isActive) cont.resume(created!!)
                }

                override fun onConnectionFailed() {
                    if (cont.isActive) cont.resumeWithException(IllegalStateException("No se pudo conectar"))
                }
            },
            hints,
        )
        created.connect()
    }

    private suspend fun children(id: String): List<MediaBrowser.MediaItem> = withTimeout(40_000) {
        suspendCancellableCoroutine { cont ->
            browser.subscribe(
                id,
                object : MediaBrowser.SubscriptionCallback() {
                    override fun onChildrenLoaded(parentId: String, children: MutableList<MediaBrowser.MediaItem>) {
                        browser.unsubscribe(id)
                        if (cont.isActive) cont.resume(children.toList())
                    }

                    override fun onError(parentId: String) {
                        if (cont.isActive) cont.resume(emptyList())
                    }
                },
            )
        }
    }

    /** El MediaBrowser del sistema no busca: la búsqueda se prueba con el de Media3 (misma llamada en Lyra). */
    private suspend fun search(query: String): List<androidx.media3.common.MediaItem> = withTimeout(40_000) {
        val token = SessionToken(this@AutoProbeActivity, ComponentName(this@AutoProbeActivity, "com.lyra.music.playback.PlaybackService"))
        val media3 = androidx.media3.session.MediaBrowser.Builder(this@AutoProbeActivity, token).buildAsync().await()
        try {
            media3.search(query, null).await()
            media3.getSearchResult(query, 0, 100, null).await().value.orEmpty()
        } finally {
            media3.release()
        }
    }

    private fun describe(item: androidx.media3.common.MediaItem): String {
        val m = item.mediaMetadata
        val flags = (if (m.isBrowsable == true) "B" else "") + (if (m.isPlayable == true) "P" else "")
        return "[$flags] ${item.mediaId} | ${m.title} | ${m.subtitle ?: m.artist} | ${dump(m.extras, skip = setOf("lyra.song"))}"
    }

    private fun describe(item: MediaBrowser.MediaItem): String {
        val d = item.description
        val flags = (if (item.isBrowsable) "B" else "") + (if (item.isPlayable) "P" else "")
        return "[$flags] ${d.mediaId} | ${d.title} | ${d.subtitle} | ${dump(d.extras, skip = setOf("lyra.song"))}"
    }

    @Suppress("DEPRECATION")
    private fun dump(bundle: Bundle?, skip: Set<String> = emptySet()): String =
        bundle?.keySet()?.filterNot { it in skip }?.sorted()?.joinToString(", ", "{", "}") { "${it.substringAfterLast('.')}=${bundle.get(it)}" } ?: "{}"

    private suspend fun icon(uri: Uri?): String = withContext(Dispatchers.IO) {
        if (uri == null) return@withContext "sin portada"
        runCatching {
            contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it) }?.let { "${it.width}x${it.height} (${uri.path})" } ?: "vacía ($uri)"
        }.getOrElse { "ERROR ${it.message} ($uri)" }
    }
}
