package com.lyra.music.ui.player

import androidx.compose.foundation.Canvas
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import com.lyra.music.playback.AudioLevels
import com.lyra.music.ui.theme.LyraColors
import kotlinx.coroutines.isActive
import kotlin.math.pow
import kotlin.math.sin

/**
 * Lo que se mueve con la música en el reproductor: barritas bajo la portada (graves a la
 * izquierda, agudos a la derecha, leídas del audio que suena de verdad) y un pulso de la
 * portada con cada golpe del bajo. Solo se redibuja; la pantalla no se recompone en cada
 * fotograma.
 */
@Stable
class MusicMotion {
    val bars = FloatArray(BARS) { 0.08f }

    /** Sube en cada fotograma: quien dibuja lo lee para repintarse. */
    var frame by mutableIntStateOf(0)

    /** Golpe del bajo: 1 justo al sonar y baja a 0 en unas décimas. */
    var pulse by mutableFloatStateOf(0f)

    companion object {
        const val BARS = 44
    }
}

@Composable
fun rememberMusicMotion(playing: Boolean, enabled: Boolean): MusicMotion {
    val motion = remember { MusicMotion() }
    LaunchedEffect(playing, enabled) {
        if (!enabled) {
            motion.pulse = 0f
            return@LaunchedEffect
        }
        val raw = FloatArray(3)
        val bars = motion.bars
        var bassAverage = 0.3f
        var sinceBeat = 0
        val start = System.nanoTime()
        while (isActive) {
            withFrameNanos { }
            val now = System.nanoTime()
            val live = playing && AudioLevels.sample(now - AudioLevels.LATENCY_NANOS, raw)
            val t = (now - start) / 1_000_000_000f
            var settled = true
            for (i in bars.indices) {
                val position = i / (bars.size - 1f)
                val target = when {
                    live -> {
                        // Mezcla de graves, medios y agudos según dónde esté la barrita.
                        val low = (1f - position * 2f).coerceAtLeast(0f)
                        val high = (position * 2f - 1f).coerceAtLeast(0f)
                        val mid = 1f - low - high
                        // Un poco más sensibles: con música suave también se mueven.
                        val level = (raw[0] * low + raw[1] * mid + raw[2] * high).coerceAtLeast(0f).pow(0.75f) * 1.25f
                        // Cada barrita con su propio vaivén, para que no parezcan tres bloques.
                        val wobble = 0.72f + 0.28f * sin(t * (4.1f + i * 0.53f) + i * 1.9f)
                        (level * wobble).coerceIn(0.05f, 1f)
                    }
                    playing -> 0.18f + 0.12f * sin(t * (2.6f + i * 0.31f) + i)
                    else -> 0.06f
                }
                // Sube rápido y baja despacio, como un vúmetro.
                val k = if (target > bars[i]) 0.55f else 0.13f
                bars[i] += (target - bars[i]) * k
                if (kotlin.math.abs(target - bars[i]) > 0.004f) settled = false
            }
            if (live) {
                val bass = raw[0]
                bassAverage += (bass - bassAverage) * 0.04f
                sinceBeat++
                // Golpe: el bajo salta claramente por encima de lo que venía sonando.
                if (sinceBeat > 8 && bass > bassAverage * 1.28f + 0.06f) {
                    motion.pulse = 1f
                    sinceBeat = 0
                }
            }
            motion.pulse *= 0.86f
            motion.frame++
            // En pausa, cuando todo está quieto, se deja de animar.
            if (!playing && settled && motion.pulse < 0.01f) break
        }
    }
    return motion
}

/** Escala de la portada con el golpe del bajo (muy sutil). */
fun MusicMotion.coverScale(): Float = 1f + 0.028f * pulse

/** Fila de barritas finas, centradas en vertical (como una onda). */
@Composable
fun MusicBars(motion: MusicMotion, playing: Boolean, modifier: Modifier = Modifier) {
    Canvas(modifier) {
        motion.frame // Se repinta en cada fotograma.
        val count = motion.bars.size
        val gap = size.width * 0.011f
        val barWidth = (size.width - gap * (count - 1)) / count
        val color = if (playing) LyraColors.Accent.copy(alpha = 0.85f) else LyraColors.Accent.copy(alpha = 0.35f)
        for (i in 0 until count) {
            val height = (size.height * (0.12f + 0.88f * motion.bars[i])).coerceAtMost(size.height)
            drawRoundRect(
                color = color,
                topLeft = Offset(i * (barWidth + gap), (size.height - height) / 2f),
                size = Size(barWidth, height),
                cornerRadius = CornerRadius(barWidth / 2f),
            )
        }
    }
}
