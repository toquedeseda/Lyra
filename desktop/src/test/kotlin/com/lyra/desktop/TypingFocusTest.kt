package com.lyra.desktop

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.use
import com.lyra.desktop.ui.components.SearchBox
import com.lyra.desktop.ui.components.Typing
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.awt.event.KeyEvent as AwtKeyEvent

/** La barra espaciadora en un buscador escribe un espacio y no pausa la música. */
class TypingFocusTest {

    @OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
    @Test
    fun spaceInSearchBoxTypesInsteadOfPausing() {
        var toggles = 0
        var text by mutableStateOf("")
        var shown by mutableStateOf(true)
        val focus = FocusRequester()
        ImageComposeScene(500, 120) {
            // Como los atajos de la ventana: les llega lo que el cuadro de texto no se queda.
            Box(Modifier.onKeyEvent { if (it.type == KeyEventType.KeyDown && it.key == Key.Spacebar && !Typing.active) toggles++; false }) {
                if (shown) {
                    SearchBox(text, { text = it }, "Buscar", focusRequester = focus)
                    LaunchedEffect(Unit) { focus.requestFocus() }
                }
            }
        }.use { scene ->
            scene.render(0)
            scene.render(16_000_000)
            assertTrue(Typing.active)

            val source = java.awt.Canvas()
            val pressed = AwtKeyEvent(source, AwtKeyEvent.KEY_PRESSED, 0, 0, AwtKeyEvent.VK_SPACE, ' ')
            val typed = AwtKeyEvent(source, AwtKeyEvent.KEY_TYPED, 0, 0, AwtKeyEvent.VK_UNDEFINED, ' ')
            scene.sendKeyEvent(KeyEvent(Key.Spacebar, KeyEventType.KeyDown, codePoint = ' '.code, nativeEvent = pressed))
            scene.sendKeyEvent(KeyEvent(Key.Unknown, KeyEventType.Unknown, codePoint = ' '.code, nativeEvent = typed))
            scene.render(32_000_000)
            assertEquals(" ", text)
            assertEquals(0, toggles)

            // Si el buscador desaparece con el cursor dentro, el espacio vuelve a pausar.
            shown = false
            scene.render(48_000_000)
            assertFalse(Typing.active)
        }
    }
}
