package com.lyra.music.ui.components

import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.zIndex

/**
 * Reordenar arrastrando desde un asa. Mientras se arrastra solo cambia la lista
 * local ([onMove]); al soltar se llama a [onDrop] con la posición inicial y la final.
 * [firstIndex] es el índice (en la LazyColumn) del primer elemento reordenable.
 */
class ReorderState(
    private val listState: LazyListState,
    private val firstIndex: Int,
    private val onMove: (from: Int, to: Int) -> Unit,
    private val onDrop: (from: Int, to: Int) -> Unit,
) {
    var dragging by mutableStateOf<Int?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    private var startIndex = -1

    fun start(index: Int) {
        dragging = index
        startIndex = index
        offset = 0f
    }

    fun drag(delta: Float) {
        val current = dragging ?: return
        offset += delta
        val items = listState.layoutInfo.visibleItemsInfo
        val info = items.firstOrNull { it.index == current + firstIndex } ?: return
        val center = info.offset + offset + info.size / 2f
        val target = items.firstOrNull {
            it.index != info.index && it.index >= firstIndex &&
                center >= it.offset && center <= it.offset + it.size
        } ?: return
        val to = target.index - firstIndex
        onMove(current, to)
        offset += (info.offset - target.offset).toFloat()
        dragging = to
    }

    fun end() {
        val end = dragging
        if (end != null && startIndex >= 0 && end != startIndex) onDrop(startIndex, end)
        dragging = null
        offset = 0f
        startIndex = -1
    }
}

@Composable
fun rememberReorderState(
    listState: LazyListState,
    firstIndex: Int,
    onMove: (Int, Int) -> Unit,
    onDrop: (Int, Int) -> Unit,
): ReorderState {
    val move by rememberUpdatedState(onMove)
    val drop by rememberUpdatedState(onDrop)
    return remember(listState, firstIndex) {
        ReorderState(listState, firstIndex, { a, b -> move(a, b) }, { a, b -> drop(a, b) })
    }
}

/** Para el elemento de la lista: se desplaza con el dedo mientras se arrastra. */
fun Modifier.reorderItem(state: ReorderState, index: Int): Modifier =
    if (state.dragging == index) {
        this
            .zIndex(1f)
            .graphicsLayer {
                translationY = state.offset
                shadowElevation = 12f
            }
    } else {
        this
    }

/** Para el asa de arrastre. */
@Composable
fun Modifier.reorderHandle(state: ReorderState, index: Int): Modifier {
    val currentIndex by rememberUpdatedState(index)
    return this.pointerInput(state) {
        detectDragGestures(
            onDragStart = { state.start(currentIndex) },
            onDrag = { change, amount ->
                change.consume()
                state.drag(amount.y)
            },
            onDragEnd = { state.end() },
            onDragCancel = { state.end() },
        )
    }
}
