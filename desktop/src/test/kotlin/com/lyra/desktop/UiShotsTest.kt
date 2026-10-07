package com.lyra.desktop

import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.unit.Density
import androidx.compose.ui.use
import coil3.ImageLoader
import coil3.SingletonImageLoader
import coil3.network.okhttp.OkHttpNetworkFetcherFactory
import com.lyra.desktop.ui.LocalActions
import com.lyra.desktop.ui.LyraActions
import com.lyra.desktop.ui.MainContent
import com.lyra.desktop.ui.Navigator
import com.lyra.desktop.ui.Screen
import org.jetbrains.skia.EncodedImageFormat
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File

/**
 * Capturas de las pantallas de Lyra para Windows sin abrir ninguna ventana (para revisarlas).
 * Solo con LYRA_UI_SHOTS=1; se guardan en desktop/build/capturas.
 */
class UiShotsTest {

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
        for (name in screens) {
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
            ImageComposeScene(1280, 820, Density(1f)) {
                CompositionLocalProvider(LocalActions provides actions) {
                    MainContent(actions, onToggleMini = {})
                }
            }.use { scene ->
                // Unos segundos para que lleguen los datos y las portadas.
                var time = 0L
                repeat(60) {
                    scene.render(time)
                    Thread.sleep(150)
                    time += 150_000_000L
                }
                val image = scene.render(time)
                val file = File(out, name.replace(Regex("[^A-Za-z0-9_-]"), "_") + ".png")
                file.writeBytes(image.encodeToData(EncodedImageFormat.PNG)!!.bytes)
                println("captura: ${file.absolutePath}")
            }
        }
        app.shutdown()
    }
}
