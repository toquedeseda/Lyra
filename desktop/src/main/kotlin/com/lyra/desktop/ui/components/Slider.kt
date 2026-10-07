package com.lyra.desktop.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.hoverable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsHoveredAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerIcon
import androidx.compose.ui.input.pointer.onPointerEvent
import androidx.compose.ui.input.pointer.pointerHoverIcon
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import com.lyra.desktop.ui.LyraColors

/**
 * Barra fina como la de Spotify (progreso y volumen): al pasar el ratón se ilumina y aparece el
 * círculo. Se puede pinchar o arrastrar; [onCommit] se llama al soltar. Con la rueda del ratón
 * se mueve un poco (si hay [wheelStep]).
 */
@Composable
fun ThinSlider(
    value: Float,
    onChange: (Float) -> Unit,
    modifier: Modifier = Modifier,
    onCommit: (Float) -> Unit = {},
    wheelStep: Float = 0f,
    enabled: Boolean = true,
) {
    val interaction = remember { MutableInteractionSource() }
    val hovered by interaction.collectIsHoveredAsState()
    var dragging by remember { mutableStateOf(false) }
    var dragValue by remember { mutableFloatStateOf(0f) }
    val shown = if (dragging) dragValue else value.coerceIn(0f, 1f)
    val change by rememberUpdatedState(onChange)
    val commit by rememberUpdatedState(onCommit)
    val active = (hovered || dragging) && enabled
    Box(
        modifier
            .height(18.dp)
            .fillMaxWidth()
            .hoverable(interaction)
            .pointerHoverIcon(if (enabled) PointerIcon.Hand else PointerIcon.Default)
            .then(
                if (!enabled) Modifier else Modifier
                    .pointerInput(Unit) {
                        detectTapGestures { offset ->
                            val v = (offset.x / size.width).coerceIn(0f, 1f)
                            change(v)
                            commit(v)
                        }
                    }
                    .pointerInput(Unit) {
                        detectDragGestures(
                            onDragStart = { offset ->
                                dragging = true
                                dragValue = (offset.x / size.width).coerceIn(0f, 1f)
                                change(dragValue)
                            },
                            onDragEnd = {
                                dragging = false
                                commit(dragValue)
                            },
                            onDragCancel = { dragging = false },
                        ) { change, _ ->
                            dragValue = (change.position.x / size.width).coerceIn(0f, 1f)
                            change(dragValue)
                        }
                    }
                    .onPointerEvent(PointerEventType.Scroll) { event ->
                        if (wheelStep > 0f) {
                            val delta = event.changes.first().scrollDelta.y
                            val v = (value - delta * wheelStep).coerceIn(0f, 1f)
                            change(v)
                            commit(v)
                        }
                    },
            ),
    ) {
        Canvas(Modifier.fillMaxWidth().height(18.dp)) {
            val trackHeight = 4.dp.toPx()
            val y = (size.height - trackHeight) / 2
            val radius = CornerRadius(trackHeight / 2)
            drawRoundRect(LyraColors.SurfaceHigher.copy(alpha = 0.9f), Offset(0f, y), Size(size.width, trackHeight), radius)
            val filled = size.width * shown
            drawRoundRect(if (active) LyraColors.Accent else LyraColors.TextPrimary, Offset(0f, y), Size(filled, trackHeight), radius)
            if (active) {
                drawCircle(LyraColors.TextPrimary, radius = 6.dp.toPx(), center = Offset(filled.coerceIn(6.dp.toPx(), size.width - 6.dp.toPx()), size.height / 2))
            }
        }
    }
}
