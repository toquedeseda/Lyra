package com.lyra.music.ui.settings

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.util.UnstableApi
import com.lyra.music.playback.EqPresets
import com.lyra.music.playback.LyraAudioProcessor
import com.lyra.music.ui.LocalActions
import com.lyra.music.ui.components.BackBar
import com.lyra.music.ui.components.ChipRow
import com.lyra.music.ui.theme.LyraColors
import kotlin.math.roundToInt

@UnstableApi
@Composable
fun EqualizerScreen(contentPadding: PaddingValues) {
    val actions = LocalActions.current
    val settings = actions.container.settings
    val s by settings.flow.collectAsState()

    // Copia local para que los deslizadores respondan al instante.
    val bands = remember { mutableStateListOf<Float>().apply { addAll(List(10) { 0f }) } }
    LaunchedEffect(s.eqBands) {
        s.eqBands.forEachIndexed { index, value -> if (index < bands.size) bands[index] = value }
    }
    val presetKeys = EqPresets.all.map { it.key }
    val selectedPreset = presetKeys.indexOf(s.eqPreset)

    fun save(preset: String = "custom") = actions.launch {
        settings.update { it.copy(eqBands = bands.toList(), eqPreset = preset, eqEnabled = true) }
    }

    Column(Modifier.fillMaxSize()) {
        BackBar("Ecualizador")
        Column(
            Modifier
                .verticalScroll(rememberScrollState())
                .padding(bottom = contentPadding.calculateBottomPadding() + 24.dp),
        ) {
            SwitchRow("Activar ecualizador", "Se aplica a todo lo que suena, también a las descargas.", s.eqEnabled) { value ->
                actions.launch { settings.update { it.copy(eqEnabled = value) } }
            }
            Text(
                "Presets",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 16.dp, top = 16.dp, bottom = 8.dp),
            )
            ChipRow(
                labels = EqPresets.all.map { it.label } + if (selectedPreset < 0) listOf("Personalizado") else emptyList(),
                selected = if (selectedPreset >= 0) selectedPreset else EqPresets.all.size,
                onSelect = { index ->
                    EqPresets.all.getOrNull(index)?.let { preset ->
                        preset.bands.forEachIndexed { i, v -> bands[i] = v }
                        save(preset.key)
                    }
                },
            )
            Spacer(Modifier.height(24.dp))
            Row(
                Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 8.dp),
                horizontalArrangement = Arrangement.SpaceEvenly,
            ) {
                LyraAudioProcessor.BAND_FREQUENCIES.forEachIndexed { index, frequency ->
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Text(
                            (if (bands[index] > 0) "+" else "") + formatDb(bands[index]),
                            fontSize = 10.sp,
                            color = LyraColors.TextSecondary,
                        )
                        Spacer(Modifier.height(6.dp))
                        VerticalBand(
                            value = bands[index],
                            enabled = s.eqEnabled,
                            onChange = { bands[index] = it },
                            onDone = { save() },
                        )
                        Spacer(Modifier.height(6.dp))
                        Text(frequencyLabel(frequency), fontSize = 10.sp, color = LyraColors.TextSecondary)
                    }
                }
            }
            Spacer(Modifier.height(16.dp))
            SliderRow(
                title = "Preamplificador",
                valueLabel = "${formatDb(s.eqPreamp)} dB",
                value = s.eqPreamp,
                range = -12f..6f,
                steps = 0,
                description = "Bájalo si al subir bandas notas que satura.",
            ) { value -> actions.launch { settings.update { it.copy(eqPreamp = (value * 2).roundToInt() / 2f) } } }
            TextButton(
                onClick = {
                    for (i in bands.indices) bands[i] = 0f
                    actions.launch { settings.update { it.copy(eqBands = List(10) { 0f }, eqPreset = "flat", eqPreamp = 0f) } }
                },
                modifier = Modifier.padding(horizontal = 8.dp),
            ) { Text("Restablecer", color = Color.White) }
        }
    }
}

@Composable
private fun VerticalBand(value: Float, enabled: Boolean, onChange: (Float) -> Unit, onDone: () -> Unit) {
    val current by rememberUpdatedState(value)
    val change by rememberUpdatedState(onChange)
    val done by rememberUpdatedState(onDone)
    val activeColor = if (enabled) Color.White else LyraColors.TextTertiary
    Canvas(
        Modifier
            .width(28.dp)
            .height(200.dp)
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                val range = size.height.toFloat()
                detectVerticalDragGestures(
                    onDragEnd = { done() },
                    onVerticalDrag = { pointer, amount ->
                        pointer.consume()
                        val next = (current - amount / range * 24f).coerceIn(-12f, 12f)
                        change((next * 2).roundToInt() / 2f)
                    },
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(onDoubleTap = {
                    change(0f)
                    done()
                })
            },
    ) {
        val centerX = size.width / 2
        val top = 8.dp.toPx()
        val bottom = size.height - 8.dp.toPx()
        val zeroY = (top + bottom) / 2
        val y = zeroY - (value / 12f) * (bottom - top) / 2
        drawLine(Color(0xFF3A3A3A), Offset(centerX, top), Offset(centerX, bottom), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawLine(Color(0xFF555555), Offset(centerX - 6.dp.toPx(), zeroY), Offset(centerX + 6.dp.toPx(), zeroY), strokeWidth = 1.dp.toPx())
        drawLine(activeColor, Offset(centerX, zeroY), Offset(centerX, y), strokeWidth = 4.dp.toPx(), cap = StrokeCap.Round)
        drawCircle(activeColor, radius = 9.dp.toPx(), center = Offset(centerX, y))
    }
}

private fun formatDb(value: Float): String =
    if (value == value.roundToInt().toFloat()) value.roundToInt().toString() else String.format(java.util.Locale.US, "%.1f", value)

private fun frequencyLabel(hz: Float): String = if (hz >= 1000) "${(hz / 1000).roundToInt()}k" else hz.roundToInt().toString()
