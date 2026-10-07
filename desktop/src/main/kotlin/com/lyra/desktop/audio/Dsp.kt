package com.lyra.desktop.audio

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.math.tanh

/** Ajustes del sonido (los mismos que en el móvil). Se cambian en caliente. */
data class AudioFx(
    val eqEnabled: Boolean = false,
    val bandsDb: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    val normalize: Boolean = true,
)

class Biquad(
    private val b0: Float, private val b1: Float, private val b2: Float,
    private val a1: Float, private val a2: Float,
) {
    private var x1 = 0f
    private var x2 = 0f
    private var y1 = 0f
    private var y2 = 0f

    fun process(x: Float): Float {
        val y = b0 * x + b1 * x1 + b2 * x2 - a1 * y1 - a2 * y2
        x2 = x1; x1 = x
        y2 = y1; y1 = y
        return y
    }

    fun clear() {
        x1 = 0f; x2 = 0f; y1 = 0f; y2 = 0f
    }

    companion object {
        private fun normalized(b0: Double, b1: Double, b2: Double, a0: Double, a1: Double, a2: Double) =
            Biquad((b0 / a0).toFloat(), (b1 / a0).toFloat(), (b2 / a0).toFloat(), (a1 / a0).toFloat(), (a2 / a0).toFloat())

        fun lowPass(sampleRate: Int, frequency: Float, q: Float = 0.707f): Biquad {
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized((1 - c) / 2, 1 - c, (1 - c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun highPass(sampleRate: Int, frequency: Float, q: Float = 0.707f): Biquad {
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized((1 + c) / 2, -(1 + c), (1 + c) / 2, 1 + alpha, -2 * c, 1 - alpha)
        }

        fun bandPass(sampleRate: Int, frequency: Float, q: Float): Biquad {
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val c = cos(w0)
            return normalized(alpha, 0.0, -alpha, 1 + alpha, -2 * c, 1 - alpha)
        }

        /** Filtro de pico (Audio EQ Cookbook de R. Bristow-Johnson), como en el móvil. */
        fun peaking(sampleRate: Int, frequency: Float, q: Float, gainDb: Float): Biquad {
            val a = 10.0.pow(gainDb / 40.0)
            val w0 = 2 * PI * frequency / sampleRate
            val alpha = sin(w0) / (2 * q)
            val a0 = 1 + alpha / a
            return Biquad(
                b0 = ((1 + alpha * a) / a0).toFloat(),
                b1 = ((-2 * cos(w0)) / a0).toFloat(),
                b2 = ((1 - alpha * a) / a0).toFloat(),
                a1 = ((-2 * cos(w0)) / a0).toFloat(),
                a2 = ((1 - alpha / a) / a0).toFloat(),
            )
        }
    }
}

object Dsp {
    val BAND_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
    const val BAND_Q = 1.1f
    private const val LIMIT_THRESHOLD = 0.891f // -1 dBFS

    fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
    fun linearToDb(linear: Float): Float = 20f * log10(linear.coerceAtLeast(1e-6f))

    /** Limitador suave: nunca satura, aunque se suban mucho las bandas. */
    fun softLimit(x: Float): Float {
        val magnitude = abs(x)
        if (magnitude <= LIMIT_THRESHOLD) return x
        val headroom = 1f - LIMIT_THRESHOLD
        return sign(x) * (LIMIT_THRESHOLD + headroom * tanh((magnitude - LIMIT_THRESHOLD) / headroom))
    }
}

/** Ecualizador de 10 bandas sobre estéreo intercalado (el mismo que en el móvil). */
class Equalizer(private val sampleRate: Int) {
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var applied: AudioFx? = null
    private var preamp = 1f

    fun process(buffer: FloatArray, frames: Int, fx: AudioFx) {
        if (fx !== applied) rebuild(fx)
        if (!fx.eqEnabled) return
        val active = filters
        for (frame in 0 until frames) {
            for (ch in 0 until 2) {
                val index = frame * 2 + ch
                var x = buffer[index]
                for (band in active) x = band[ch].process(x)
                buffer[index] = x * preamp
            }
        }
    }

    private fun rebuild(fx: AudioFx) {
        applied = fx
        val maxBoost = if (fx.eqEnabled) fx.bandsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f else 0f
        // Deja margen cuando se suben bandas para que el limitador trabaje poco.
        preamp = if (fx.eqEnabled) Dsp.dbToLinear(fx.preampDb - maxBoost * 0.5f) else 1f
        filters = Dsp.BAND_FREQUENCIES.indices
            .filter { abs(fx.bandsDb.getOrElse(it) { 0f }) > 0.05f && Dsp.BAND_FREQUENCIES[it] < sampleRate * 0.45f }
            .map { index -> Array(2) { Biquad.peaking(sampleRate, Dsp.BAND_FREQUENCIES[index], Dsp.BAND_Q, fx.bandsDb[index]) } }
            .toTypedArray()
    }

    fun clear() = filters.forEach { band -> band.forEach { it.clear() } }
}

/**
 * Volumen igualado de una canción: mide su sonoridad media (sin contar silencios) y acerca la
 * ganancia a un objetivo poco a poco, para que no "bombee". Igual que en el móvil.
 */
class LoudnessNormalizer(private val sampleRate: Int) {
    private val blockSize = sampleRate / 10
    private var gainLinear = 1f
    private var gainStep = 0f
    private var energySum = 0.0
    private var energyBlocks = 0
    private var blockEnergy = 0.0
    private var blockFrames = 0
    private var trackFrames = 0L

    /** Sonoridad media de la canción hasta ahora (dB), o NaN si aún no se sabe. */
    @Volatile var trackDb: Float = Float.NaN
        private set

    fun process(buffer: FloatArray, offset: Int, frames: Int, enabled: Boolean) {
        for (frame in 0 until frames) {
            val i = (offset + frame) * 2
            val l = buffer[i]
            val r = buffer[i + 1]
            blockEnergy += (l * l + r * r) / 2.0
            buffer[i] = l * gainLinear
            buffer[i + 1] = r * gainLinear
            blockFrames++
            trackFrames++
            gainLinear += gainStep
            if (blockFrames >= blockSize) {
                val target = endBlock(enabled)
                gainStep = (Dsp.dbToLinear(target) - gainLinear) / blockSize
            }
        }
    }

    private fun endBlock(enabled: Boolean): Float {
        val meanSquare = blockEnergy / blockFrames.coerceAtLeast(1)
        blockEnergy = 0.0
        blockFrames = 0
        val blockDb = 10 * log10(meanSquare + 1e-12)
        if (blockDb > GATE_DB) {
            energySum += meanSquare
            energyBlocks++
        }
        if (energyBlocks >= 20) trackDb = (10 * log10(energySum / energyBlocks + 1e-12)).toFloat()
        val gainDb = Dsp.linearToDb(gainLinear)
        val target = if (enabled && energyBlocks >= 5) {
            (TARGET_DB - 10 * log10(energySum / energyBlocks + 1e-12)).toFloat().coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        } else if (enabled) {
            gainDb
        } else {
            0f
        }
        val seconds = trackFrames.toFloat() / sampleRate
        val maxStepDb = (if (!enabled || seconds < 8f) FAST_SLEW_DB else SLOW_SLEW_DB) * blockSize / sampleRate
        return gainDb + (target - gainDb).coerceIn(-maxStepDb, maxStepDb)
    }

    /** Tras saltar a otro punto: se sigue con lo medido, sin cambios bruscos. */
    fun onSeek() {
        blockEnergy = 0.0
        blockFrames = 0
    }

    private companion object {
        const val TARGET_DB = -17.0
        const val GATE_DB = -50.0
        const val MAX_BOOST_DB = 8f
        const val MAX_CUT_DB = -12f
        const val FAST_SLEW_DB = 4f
        const val SLOW_SLEW_DB = 0.6f
    }
}

/**
 * Niveles de graves, medios y agudos de lo que suena (para las barritas). Se guardan con su hora
 * para leerlos con el mismo retraso con el que salen por los altavoces.
 */
class AudioLevels(sampleRate: Int) {
    private val low = Biquad.lowPass(sampleRate, 160f)
    private val mid = Biquad.bandPass(sampleRate, 1100f, 0.8f)
    private val high = Biquad.highPass(sampleRate, 5000f)
    private val window = sampleRate / 40
    private var lowEnergy = 0.0
    private var midEnergy = 0.0
    private var highEnergy = 0.0
    private var frames = 0

    private val size = 128
    private val times = LongArray(size)
    private val values = FloatArray(size * 3)
    private var head = 0
    private var count = 0
    private val peak = FloatArray(3) { 1e-3f }
    private val floor = FloatArray(3) { 1e-3f }

    fun analyse(buffer: FloatArray, frameCount: Int, playsAtNanos: Long) {
        for (frame in 0 until frameCount) {
            val sample = (buffer[frame * 2] + buffer[frame * 2 + 1]) * 0.5f
            val l = low.process(sample)
            val m = mid.process(sample)
            val h = high.process(sample)
            lowEnergy += (l * l).toDouble()
            midEnergy += (m * m).toDouble()
            highEnergy += (h * h).toDouble()
            if (++frames >= window) {
                val n = frames.toDouble()
                push(playsAtNanos, sqrt(lowEnergy / n).toFloat(), sqrt(midEnergy / n).toFloat(), sqrt(highEnergy / n).toFloat())
                lowEnergy = 0.0
                midEnergy = 0.0
                highEnergy = 0.0
                frames = 0
            }
        }
    }

    @Synchronized
    private fun push(time: Long, l: Float, m: Float, h: Float) {
        times[head] = time
        values[head * 3] = normalize(0, l)
        values[head * 3 + 1] = normalize(1, m)
        values[head * 3 + 2] = normalize(2, h)
        head = (head + 1) % size
        if (count < size) count++
    }

    private fun normalize(band: Int, value: Float): Float {
        peak[band] = maxOf(peak[band] * 0.993f, value, 1e-4f)
        floor[band] = if (value < floor[band]) value else floor[band] + (value - floor[band]) * 0.02f
        if (peak[band] < 0.002f) return 0f
        val range = maxOf(peak[band] - floor[band], peak[band] * 0.3f)
        return ((value - floor[band]) / range).coerceIn(0f, 1f)
    }

    /** Nivel (0..1) de cada banda que suena en [atNanos]. False si no hay datos recientes. */
    @Synchronized
    fun sample(atNanos: Long, out: FloatArray): Boolean {
        for (i in 0 until count) {
            val index = (head - 1 - i + size) % size
            if (times[index] <= atNanos) {
                if (atNanos - times[index] > 350_000_000L) return false
                out[0] = values[index * 3]
                out[1] = values[index * 3 + 1]
                out[2] = values[index * 3 + 2]
                return true
            }
        }
        return false
    }
}
