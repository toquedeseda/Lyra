package com.lyra.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.lyra.desktop.system.TaskbarButtons
import com.lyra.desktop.ui.FullScreenPlayer
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraActions
import com.lyra.desktop.ui.MainContent
import com.lyra.desktop.ui.Navigator
import com.lyra.desktop.ui.Screen
import com.lyra.desktop.update.UpdateState
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Capturas de las pantallas de Lyra para Windows sin abrir ninguna ventana (para revisarlas).
 * Solo con LYRA_UI_SHOTS=1; se guardan en desktop/build/capturas. «ajustes~12» baja 12 pasos de
 * la rueda del ratón en el centro antes de capturar; «home@1040» usa una ventana de 1040 de ancho y
 * «home+update» enseña el botón de «Reiniciar para actualizar». «pantalla» es la pantalla completa
 * (con la cola guardada de la versión de pruebas) e «iconos» guarda los de la barra de tareas.
 */
class UiShotsTest {

    @OptIn(ExperimentalComposeUiApi::class)
    @Test
    fun capturas() {
        assumeTrue(System.getenv("LYRA_UI_SHOTS") == "1")
        System.setProperty("lyra.dev", "1")
        val app = AppContainer()
        SingletonImageLoader.setSafe { context ->
            ImageLoader.Builder(context).components { add(OkHttpNetworkFetcherFactory(callFactory = { app.http })) }.build()
        }
        val out = File("build/capturas").apply { mkdirs() }
        val screens = (System.getenv("LYRA_UI_SCREENS") ?: "home").split(',')
        // Lo que sonaba la última vez (parado), para que se vea el reproductor con una canción.
        app.player.restore()
        for (entry in screens) {
            if (entry == "iconos") {
                for (kind in listOf("previous", "play", "pause", "next")) {
                    File(out, "icono_${kind}_claro.png").writeBytes(TaskbarButtons.drawIcon(kind, 48, 0xFFF3F3F1.toInt()))
                    File(out, "icono_${kind}_oscuro.png").writeBytes(TaskbarButtons.drawIcon(kind, 48, 0xFF1C1C1F.toInt()))
                }
                continue
            }
            val base = entry.substringBefore('~')
            val wheel = entry.substringAfter('~', "0").toIntOrNull() ?: 0
            val width = Regex("""@(\d+)""").find(base)?.groupValues?.get(1)?.toInt() ?: 1280
            val name = base.replace("+update", "").replace("+controles", "").replace(Regex("""@\d+"""), "")
            app.updater.showForTest(if ("+update" in base) UpdateState.Ready("9.9.9", "", File("prueba.msi")) else UpdateState.None)
            val nav = Navigator()
            val actions = LyraActions(app, nav)
            when {
                name == "home" -> Unit
                name == "ajustes" -> nav.navigate(Screen.Settings)
                name == "megusta" -> nav.navigate(Screen.Liked)
                name == "descargas" -> nav.navigate(Screen.Downloads)
                name == "historial" -> nav.navigate(Screen.History)
                name.startsWith("buscar:") -> nav.navigate(Screen.Search(name.removePrefix("buscar:")))
                name.startsWith("album:") -> nav.navigate(Screen.Album(name.removePrefix("album:")))
                name.startsWith("artista:") -> nav.navigate(Screen.Artist(name.removePrefix("artista:")))
                name.startsWith("playlist:") -> nav.navigate(Screen.RemotePlaylist(name.removePrefix("playlist:")))
                name.startsWith("lista:") -> nav.navigate(Screen.LocalPlaylist(name.removePrefix("lista:")))
            }
            ImageComposeScene(width, 820, Density(1f)) {
                CompositionLocalProvider(LocalActions provides actions) {
                    if (name == "pantalla") FullScreenPlayer(actions, onExit = {}) else MainContent(actions, onToggleMini = {})
                }
            }.use { scene ->
                // Unos segundos para que lleguen los datos y las portadas.
                var time = 0L
                repeat(60) {
                    scene.render(time)
                    Thread.sleep(150)
                    time += 150_000_000L
                }
                repeat(wheel) {
                    scene.sendPointerEvent(PointerEventType.Scroll, Offset(620f, 400f), scrollDelta = Offset(0f, 1f))
                    repeat(4) {
                        time += 50_000_000L
                        scene.render(time)
                    }
                }
                if ("+controles" in base) {
                    // Mover el ratón: en pantalla completa vuelven a salir los controles.
                    scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f, 400f))
                    scene.sendPointerEvent(PointerEventType.Move, Offset(width / 2f + 20f, 410f))
                    repeat(8) {
                        time += 50_000_000L
                        scene.render(time)
                    }
                }
                val image = scene.render(time)
                val file = File(out, entry.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".png")
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("captura: ${file.absolutePath}")
            }
        }
        app.shutdown()
    }
}
