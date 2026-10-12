package com.lyra.music

import com.lyra.music.playback.QueueEditor
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** La cola como en Spotify: lo añadido va donde se ve, también con el aleatorio puesto. */
class QueueEditorTest {

    // Orden aleatorio de una lista de 6 (índices); suena la 4 (posición 2 del orden).
    private val order = listOf(3, 0, 4, 1, 5, 2)

    @Test
    fun loAnadidoVaJustoDetrasDeLaQueSuena() {
        // Dos nuevas (índices 6 y 7) justo detrás de la que suena (posición 2 → se meten en la 3).
        assertEquals(listOf(3, 0, 4, 6, 7, 1, 5, 2), QueueEditor.insertedOrder(order, 3, listOf(6, 7)))
    }

    @Test
    fun moverQuedaDondeSeSuelta() {
        // Las que vienen tras la 4 son 1, 5, 2: la última pasa a ser la siguiente.
        assertEquals(listOf(3, 0, 4, 2, 1, 5), QueueEditor.movedOrder(order, 2, from = 2, to = 0))
        assertNull(QueueEditor.movedOrder(order, 2, from = 1, to = 1))
        assertNull(QueueEditor.movedOrder(order, 2, from = 0, to = 7))
    }

    @Test
    fun vaciarQuitaPorTramosDeAtrasAdelante() {
        // Las que vienen (1, 5, 2) se quitan como 5, luego 1..2: sin descolocar las demás.
        assertEquals(listOf(5..5, 1..2), QueueEditor.removalRanges(listOf(1, 5, 2)))
        assertEquals(listOf(4..9), QueueEditor.removalRanges((4..9).toList()))
        assertEquals(emptyList<IntRange>(), QueueEditor.removalRanges(emptyList()))
    }
}
