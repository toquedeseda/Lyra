package com.lyra.music.playback

import androidx.media3.common.C
import androidx.media3.common.audio.AudioProcessor.AudioFormat
import androidx.media3.common.audio.AudioProcessor.UnhandledAudioFormatException
import androidx.media3.common.audio.BaseAudioProcessor
import androidx.media3.common.util.UnstableApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.log10
import kotlin.math.pow
import kotlin.math.sign
import kotlin.math.sin
import kotlin.math.tanh

/** Ajustes del procesador. Se cambian en caliente desde otro hilo. */
data class AudioFxConfig(
    val eqEnabled: Boolean = false,
    val bandsDb: List<Float> = List(10) { 0f },
    val preampDb: Float = 0f,
    val normalize: Boolean = true,
)

/**
 * Procesado de audio propio, dentro de ExoPlayer (no depende de los efectos del
 * fabricante, que en algunos móviles fallan):
 *  - Ecualizador de 10 bandas (filtros de pico RBJ).
 *  - Volumen igualado: mide la sonoridad media de la canción (ignorando silencios)
 *    y acerca la ganancia a un objetivo poco a poco, para que no "bombee".
 *  - Limitador suave final para que nunca sature.
 */
@UnstableApi
class LyraAudioProcessor : BaseAudioProcessor() {

    @Volatile var config: AudioFxConfig = AudioFxConfig()
    @Volatile private var newTrackRequested = false

    private var sampleRate = 0
    private var channels = 0
    private var filters: Array<Array<Biquad>> = emptyArray()
    private var appliedConfig: AudioFxConfig? = null
    private var preampLinear = 1f

    // Estado de la normalización
    private var gainDb = 0f
    private var gainLinear = 1f
    private var gainStep = 0f
    private var energySum = 0.0
    private var energyBlocks = 0
    private var blockEnergy = 0.0
    private var blockFrames = 0
    private var blockSize = 4800
    private var trackFrames = 0L

    /** Avisa de que empieza otra canción: se vuelve a medir desde cero. */
    fun onNewTrack() {
        newTrackRequested = true
    }

    override fun onConfigure(inputAudioFormat: AudioFormat): AudioFormat {
        if (inputAudioFormat.encoding != C.ENCODING_PCM_16BIT) {
            throw UnhandledAudioFormatException(inputAudioFormat)
        }
        sampleRate = inputAudioFormat.sampleRate
        channels = inputAudioFormat.channelCount
        blockSize = (sampleRate / 10).coerceAtLeast(256)
        appliedConfig = null
        return inputAudioFormat
    }

    override fun queueInput(inputBuffer: ByteBuffer) {
        val remaining = inputBuffer.remaining()
        if (remaining == 0) return
        val output = replaceOutputBuffer(remaining)
        process(inputBuffer, output)
        output.flip()
    }

    private fun process(input: ByteBuffer, output: ByteBuffer) {
        val cfg = config
        if (cfg !== appliedConfig) rebuild(cfg)
        if (newTrackRequested) {
            newTrackRequested = false
            resetMeasurement()
        }
        input.order(ByteOrder.LITTLE_ENDIAN)
        output.order(ByteOrder.LITTLE_ENDIAN)
        val frames = input.remaining() / (2 * channels)
        val activeFilters = if (cfg.eqEnabled) filters else emptyArray()
        var framesUntilUpdate = blockSize - blockFrames

        for (frame in 0 until frames) {
            var frameEnergy = 0.0
            for (ch in 0 until channels) {
                var x = input.short / 32768f
                for (band in activeFilters) x = band[ch].process(x)
                x *= preampLinear
                frameEnergy += (x * x).toDouble()
                var y = x * gainLinear
                y = softLimit(y)
                output.putShort((y * 32767f).toInt().coerceIn(-32768, 32767).toShort())
            }
            blockEnergy += frameEnergy / channels
            blockFrames++
            trackFrames++
            gainLinear += gainStep
            framesUntilUpdate--
            if (framesUntilUpdate <= 0) {
                gainDb = linearToDb(gainLinear)
                val target = endBlock(cfg.normalize)
                // La nueva ganancia se reparte a lo largo del bloque siguiente (sin saltos audibles).
                gainStep = (dbToLinear(target) - gainLinear) / blockSize
                framesUntilUpdate = blockSize
            }
        }
        gainDb = linearToDb(gainLinear)
    }

    /** Cierra un bloque de ~100 ms: actualiza la sonoridad medida y decide la ganancia. */
    private fun endBlock(normalize: Boolean): Float {
        val meanSquare = blockEnergy / blockFrames.coerceAtLeast(1)
        blockEnergy = 0.0
        blockFrames = 0
        val blockDb = 10 * log10(meanSquare + 1e-12)
        if (blockDb > GATE_DB) {
            energySum += meanSquare
            energyBlocks++
        }
        val target = if (normalize && energyBlocks >= 5) {
            val integratedDb = 10 * log10(energySum / energyBlocks + 1e-12)
            (TARGET_DB - integratedDb).toFloat().coerceIn(MAX_CUT_DB, MAX_BOOST_DB)
        } else if (normalize) {
            gainDb
        } else {
            0f
        }
        val seconds = trackFrames.toFloat() / sampleRate.coerceAtLeast(1)
        val maxStepDb = when {
            !normalize -> FAST_SLEW_DB
            seconds < 8f -> FAST_SLEW_DB
            else -> SLOW_SLEW_DB
        } * blockSize / sampleRate.coerceAtLeast(1)
        return gainDb + (target - gainDb).coerceIn(-maxStepDb, maxStepDb)
    }

    private fun rebuild(cfg: AudioFxConfig) {
        appliedConfig = cfg
        val maxBoost = if (cfg.eqEnabled) cfg.bandsDb.maxOrNull()?.coerceAtLeast(0f) ?: 0f else 0f
        // Deja margen cuando se suben bandas para que el limitador trabaje poco.
        preampLinear = if (cfg.eqEnabled) dbToLinear(cfg.preampDb - maxBoost * 0.5f) else 1f
        if (channels == 0 || sampleRate == 0) {
            filters = emptyArray()
            return
        }
        filters = BAND_FREQUENCIES.indices
            .filter { index -> abs(cfg.bandsDb.getOrElse(index) { 0f }) > 0.05f && BAND_FREQUENCIES[index] < sampleRate * 0.45f }
            .map { index ->
                Array(channels) { Biquad.peaking(sampleRate, BAND_FREQUENCIES[index], BAND_Q, cfg.bandsDb[index]) }
            }
            .toTypedArray()
    }

    private fun resetMeasurement() {
        energySum = 0.0
        energyBlocks = 0
        blockEnergy = 0.0
        blockFrames = 0
        trackFrames = 0
    }

    override fun onFlush() {
        filters.forEach { band -> band.forEach { it.clear() } }
        resetMeasurement()
    }

    override fun onReset() {
        filters = emptyArray()
        appliedConfig = null
        gainDb = 0f
        gainLinear = 1f
        gainStep = 0f
        resetMeasurement()
    }

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
            /** Filtro de pico (Audio EQ Cookbook de R. Bristow-Johnson). */
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

    companion object {
        val BAND_FREQUENCIES = floatArrayOf(31f, 62f, 125f, 250f, 500f, 1000f, 2000f, 4000f, 8000f, 16000f)
        private const val BAND_Q = 1.1f
        private const val TARGET_DB = -17.0
        private const val GATE_DB = -50.0
        private const val MAX_BOOST_DB = 8f
        private const val MAX_CUT_DB = -12f
        private const val FAST_SLEW_DB = 4f
        private const val SLOW_SLEW_DB = 0.6f
        private const val LIMIT_THRESHOLD = 0.891f // -1 dBFS

        fun dbToLinear(db: Float): Float = 10f.pow(db / 20f)
        fun linearToDb(linear: Float): Float = 20f * log10(linear.coerceAtLeast(1e-6f))

        fun softLimit(x: Float): Float {
            val magnitude = abs(x)
            if (magnitude <= LIMIT_THRESHOLD) return x
            val headroom = 1f - LIMIT_THRESHOLD
            return sign(x) * (LIMIT_THRESHOLD + headroom * tanh((magnitude - LIMIT_THRESHOLD) / headroom))
        }
    }
}

/** Presets del ecualizador (10 bandas: 31 Hz … 16 kHz). */
object EqPresets {
    data class Preset(val key: String, val label: String, val bands: List<Float>)

    val all = listOf(
        Preset("flat", "Plano", listOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("bass", "Más graves", listOf(6f, 5f, 4f, 2f, 0f, 0f, 0f, 0f, 0f, 0f)),
        Preset("bass_extreme", "Graves a tope", listOf(9f, 8f, 6f, 3f, 0f, -1f, -1f, 0f, 0f, 0f)),
        Preset("treble", "Más agudos", listOf(0f, 0f, 0f, 0f, 0f, 1f, 3f, 4f, 5f, 6f)),
        Preset("pop", "Pop", listOf(-1f, 1f, 3f, 4f, 2f, 0f, -1f, -1f, 1f, 2f)),
        Preset("rock", "Rock", listOf(4f, 3f, 1f, -1f, -2f, -1f, 1f, 3f, 4f, 4f)),
        Preset("electronic", "Electrónica", listOf(5f, 4f, 1f, 0f, -2f, 1f, 0f, 1f, 4f, 5f)),
        Preset("hiphop", "Hip hop / Reggaeton", listOf(6f, 5f, 2f, 3f, -1f, -1f, 1f, 0f, 2f, 3f)),
        Preset("acoustic", "Acústica", listOf(3f, 3f, 2f, 1f, 1f, 1f, 2f, 2f, 2f, 1f)),
        Preset("vocal", "Voces", listOf(-2f, -2f, -1f, 1f, 3f, 4f, 3f, 2f, 0f, -1f)),
        Preset("classical", "Clásica", listOf(3f, 2f, 1f, 0f, 0f, 0f, -1f, -1f, 1f, 3f)),
        Preset("jazz", "Jazz", listOf(3f, 2f, 1f, 2f, -1f, -1f, 0f, 1f, 2f, 3f)),
        Preset("night", "Noche (suave)", listOf(-4f, -3f, -2f, 0f, 1f, 1f, 0f, -1f, -3f, -4f)),
    )

    fun byKey(key: String): Preset? = all.firstOrNull { it.key == key }
}
