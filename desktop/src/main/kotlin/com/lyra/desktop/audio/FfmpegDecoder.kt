package com.lyra.desktop.audio

import org.bytedeco.ffmpeg.avcodec.AVCodec
import org.bytedeco.ffmpeg.avcodec.AVCodecContext
import org.bytedeco.ffmpeg.avcodec.AVPacket
import org.bytedeco.ffmpeg.avformat.AVFormatContext
import org.bytedeco.ffmpeg.avformat.AVIOContext
import org.bytedeco.ffmpeg.avformat.AVStream
import org.bytedeco.ffmpeg.avformat.Read_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avformat.Seek_Pointer_long_int
import org.bytedeco.ffmpeg.avformat.Write_packet_Pointer_BytePointer_int
import org.bytedeco.ffmpeg.avutil.AVChannelLayout
import org.bytedeco.ffmpeg.avutil.AVDictionary
import org.bytedeco.ffmpeg.avutil.AVFrame
import org.bytedeco.ffmpeg.global.avcodec.av_packet_alloc
import org.bytedeco.ffmpeg.global.avcodec.av_packet_free
import org.bytedeco.ffmpeg.global.avcodec.av_packet_unref
import org.bytedeco.ffmpeg.global.avcodec.avcodec_alloc_context3
import org.bytedeco.ffmpeg.global.avcodec.avcodec_find_decoder
import org.bytedeco.ffmpeg.global.avcodec.avcodec_flush_buffers
import org.bytedeco.ffmpeg.global.avcodec.avcodec_free_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_open2
import org.bytedeco.ffmpeg.global.avcodec.avcodec_parameters_to_context
import org.bytedeco.ffmpeg.global.avcodec.avcodec_receive_frame
import org.bytedeco.ffmpeg.global.avcodec.avcodec_send_packet
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_FLAG_BACKWARD
import org.bytedeco.ffmpeg.global.avformat.AVSEEK_SIZE
import org.bytedeco.ffmpeg.global.avformat.av_find_best_stream
import org.bytedeco.ffmpeg.global.avformat.av_read_frame
import org.bytedeco.ffmpeg.global.avformat.av_seek_frame
import org.bytedeco.ffmpeg.global.avformat.avformat_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avformat_close_input
import org.bytedeco.ffmpeg.global.avformat.avformat_find_stream_info
import org.bytedeco.ffmpeg.global.avformat.avformat_open_input
import org.bytedeco.ffmpeg.global.avformat.avio_alloc_context
import org.bytedeco.ffmpeg.global.avformat.avio_context_free
import org.bytedeco.ffmpeg.global.avutil.AVERROR_EOF
import org.bytedeco.ffmpeg.global.avutil.AVERROR_INVALIDDATA
import org.bytedeco.ffmpeg.global.avutil.AVMEDIA_TYPE_AUDIO
import org.bytedeco.ffmpeg.global.avutil.AV_LOG_QUIET
import org.bytedeco.ffmpeg.global.avutil.AV_NOPTS_VALUE
import org.bytedeco.ffmpeg.global.avutil.AV_ROUND_UP
import org.bytedeco.ffmpeg.global.avutil.AV_SAMPLE_FMT_FLT
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_default
import org.bytedeco.ffmpeg.global.avutil.av_channel_layout_uninit
import org.bytedeco.ffmpeg.global.avutil.av_frame_alloc
import org.bytedeco.ffmpeg.global.avutil.av_frame_free
import org.bytedeco.ffmpeg.global.avutil.av_frame_unref
import org.bytedeco.ffmpeg.global.avutil.av_free
import org.bytedeco.ffmpeg.global.avutil.av_log_set_level
import org.bytedeco.ffmpeg.global.avutil.av_malloc
import org.bytedeco.ffmpeg.global.avutil.av_rescale_rnd
import org.bytedeco.ffmpeg.global.avutil.av_strerror
import org.bytedeco.ffmpeg.global.swresample.swr_alloc_set_opts2
import org.bytedeco.ffmpeg.global.swresample.swr_convert
import org.bytedeco.ffmpeg.global.swresample.swr_free
import org.bytedeco.ffmpeg.global.swresample.swr_get_delay
import org.bytedeco.ffmpeg.global.swresample.swr_init
import org.bytedeco.ffmpeg.swresample.SwrContext
import org.bytedeco.javacpp.BytePointer
import org.bytedeco.javacpp.FloatPointer
import org.bytedeco.javacpp.Pointer
import org.bytedeco.javacpp.PointerPointer
import java.io.Closeable
import java.io.IOException

/** El audio no se puede leer (formato roto o que no se entiende): conviene probar otro formato. */
class UnreadableAudioException(message: String) : IOException(message)

/**
 * Lee cualquier audio (M4A/AAC, WebM/Opus, MP3…) con FFmpeg y lo entrega como muestras estéreo
 * a 48 kHz en coma flotante, listas para mezclar. Lo usa un solo hilo a la vez.
 */
class FfmpegDecoder(private val input: AudioInput) : Closeable {

    private var inputPosition = 0L
    private val ioBytes = ByteArray(IO_BUFFER)
    @Volatile private var ioError: Exception? = null

    // Los callbacks se guardan en campos: si el recolector de basura se los llevara, FFmpeg
    // llamaría a memoria liberada.
    private val readCallback = object : Read_packet_Pointer_BytePointer_int() {
        override fun call(opaque: Pointer?, buf: BytePointer, size: Int): Int = try {
            val n = input.read(inputPosition, ioBytes, 0, minOf(size, ioBytes.size))
            if (n <= 0) {
                AVERROR_EOF
            } else {
                buf.position(0).put(ioBytes, 0, n)
                inputPosition += n
                n
            }
        } catch (e: Exception) {
            ioError = e
            AVERROR_IO
        }
    }

    private val seekCallback = object : Seek_Pointer_long_int() {
        override fun call(opaque: Pointer?, offset: Long, whence: Int): Long = try {
            if (whence and AVSEEK_SIZE != 0) {
                input.length ?: -1L
            } else {
                val target = when (whence and 0x3) {
                    SEEK_SET -> offset
                    SEEK_CUR -> inputPosition + offset
                    SEEK_END -> (input.length ?: return@call -1L) + offset
                    else -> return@call -1L
                }
                if (target < 0) -1L else {
                    inputPosition = target
                    target
                }
            }
        } catch (e: Exception) {
            -1L
        }
    }

    private var avio: AVIOContext? = null
    private var format: AVFormatContext? = null
    private var codec: AVCodecContext? = null
    private val stream: AVStream
    private val streamIndex: Int
    private val packet: AVPacket = av_packet_alloc()
    private val frame: AVFrame = av_frame_alloc()

    private var swr: SwrContext? = null
    private var swrRate = 0
    private var swrFormat = -1
    private var swrChannels = 0
    private var outPointer = FloatPointer(8192L * CHANNELS)
    private var outCapacity = 8192

    /** Lista de un solo puntero (la salida es intercalada): se crea una vez y se reutiliza. */
    private val outArray = PointerPointer<Pointer>(1L)

    private val timeBaseNum: Int
    private val timeBaseDen: Int

    /** Duración de la canción, si el archivo la dice. */
    val durationMs: Long?

    /** Códec (aac, opus, mp3…), para el informe de errores. */
    val codecName: String

    // Muestras ya decodificadas pendientes de entregar.
    private var pending = FloatArray(16_384)
    private var pendingStart = 0
    private var pendingEnd = 0
    private var endOfInput = false
    private var drained = false
    private var decodeErrors = 0

    /** Fotograma (1/48000 s) por el que va la lectura. */
    var positionFrames = 0L
        private set

    /** Tras saltar a un punto, se descartan las muestras anteriores a él. */
    private var discardUntilFrames = -1L

    init {
        Natives.ensureLoaded()
        try {
            val buffer = BytePointer(av_malloc(IO_BUFFER.toLong()))
            avio = avio_alloc_context(buffer, IO_BUFFER, 0, null, readCallback, null as Write_packet_Pointer_BytePointer_int?, seekCallback)
                ?: throw IOException("Sin memoria para leer el audio")
            val ctx = avformat_alloc_context()
            ctx.pb(avio)
            ctx.flags(ctx.flags() or AVFMT_FLAG_CUSTOM_IO)
            val opened = avformat_open_input(ctx, null as String?, null, null as AVDictionary?)
            if (opened < 0) {
                ioError?.let { throw it as? IOException ?: IOException(it.message, it) }
                throw if (opened == AVERROR_INVALIDDATA) {
                    UnreadableAudioException("No se entiende el formato de este audio")
                } else {
                    IOException("No se pudo abrir el audio (${errorText(opened)})")
                }
            }
            format = ctx
            if (avformat_find_stream_info(ctx, null as PointerPointer<*>?) < 0) {
                ioError?.let { throw it as? IOException ?: IOException(it.message, it) }
            }
            streamIndex = av_find_best_stream(ctx, AVMEDIA_TYPE_AUDIO, -1, -1, null as AVCodec?, 0)
            if (streamIndex < 0) throw UnreadableAudioException("Este archivo no tiene audio")
            stream = ctx.streams(streamIndex)
            val params = stream.codecpar()
            val decoder = avcodec_find_decoder(params.codec_id())
                ?: throw UnreadableAudioException("No hay decodificador para este audio")
            codecName = decoder.name()?.string ?: "?"
            val codecContext = avcodec_alloc_context3(decoder)
            codec = codecContext
            if (avcodec_parameters_to_context(codecContext, params) < 0 || avcodec_open2(codecContext, decoder, null as AVDictionary?) < 0) {
                throw UnreadableAudioException("No se pudo preparar el decodificador ($codecName)")
            }
            timeBaseNum = stream.time_base().num()
            timeBaseDen = stream.time_base().den()
            val streamDuration = stream.duration().takeIf { it != AV_NOPTS_VALUE && it > 0 }
                ?.let { it * 1000 * timeBaseNum / timeBaseDen }
            val formatDuration = ctx.duration().takeIf { it != AV_NOPTS_VALUE && it > 0 }?.let { it / 1000 }
            durationMs = streamDuration ?: formatDuration
        } catch (e: Throwable) {
            release()
            throw e
        }
    }

    /**
     * Llena [out] con hasta [frames] fotogramas estéreo intercalados (desde [offset] fotogramas).
     * Devuelve cuántos ha puesto; 0 significa que la canción se ha acabado.
     */
    fun read(out: FloatArray, offset: Int, frames: Int): Int {
        var written = 0
        while (written < frames) {
            val available = (pendingEnd - pendingStart) / CHANNELS
            if (available > 0) {
                val n = minOf(available, frames - written)
                System.arraycopy(pending, pendingStart, out, (offset + written) * CHANNELS, n * CHANNELS)
                pendingStart += n * CHANNELS
                written += n
                positionFrames += n
                continue
            }
            if (drained) break
            decodeMore()
        }
        return written
    }

    /** Salta a [positionMs]. False si este audio no deja saltar. */
    fun seek(positionMs: Long): Boolean {
        val target = positionMs.coerceAtLeast(0) * timeBaseDen / (1000L * timeBaseNum)
        val result = av_seek_frame(format, streamIndex, target, AVSEEK_FLAG_BACKWARD)
        if (result < 0) return false
        avcodec_flush_buffers(codec)
        resetResampler()
        pendingStart = 0
        pendingEnd = 0
        endOfInput = false
        drained = false
        positionFrames = positionMs.coerceAtLeast(0) * SAMPLE_RATE / 1000
        discardUntilFrames = positionFrames
        return true
    }

    private fun decodeMore() {
        while (true) {
            val received = avcodec_receive_frame(codec, frame)
            if (received == 0) {
                val produced = convert(frame)
                av_frame_unref(frame)
                if (produced > 0) return
                continue
            }
            if (received == AVERROR_EOF) {
                flushResampler()
                drained = true
                return
            }
            if (received != AVERROR_EAGAIN) {
                // Un fotograma roto: se salta. Si pasa mucho, el audio no se puede leer.
                if (++decodeErrors > 50) throw UnreadableAudioException("El audio llega roto (${errorText(received)})")
            }
            if (endOfInput) {
                avcodec_send_packet(codec, null as AVPacket?)
                continue
            }
            val read = av_read_frame(format, packet)
            if (read < 0) {
                ioError?.let { error ->
                    ioError = null
                    throw error as? IOException ?: IOException(error.message, error)
                }
                endOfInput = true
                avcodec_send_packet(codec, null as AVPacket?)
                continue
            }
            if (packet.stream_index() == streamIndex) {
                val sent = avcodec_send_packet(codec, packet)
                if (sent < 0 && sent != AVERROR_EAGAIN && ++decodeErrors > 50) {
                    av_packet_unref(packet)
                    throw UnreadableAudioException("El audio llega roto (${errorText(sent)})")
                }
            }
            av_packet_unref(packet)
        }
    }

    /** Pasa un fotograma de FFmpeg a estéreo 48 kHz y lo deja en [pending]. */
    private fun convert(frame: AVFrame): Int {
        val inRate = frame.sample_rate()
        val inFormat = frame.format()
        val inLayout = frame.ch_layout()
        if (swr == null || inRate != swrRate || inFormat != swrFormat || inLayout.nb_channels() != swrChannels) {
            resetResampler()
            val outLayout = AVChannelLayout()
            av_channel_layout_default(outLayout, CHANNELS)
            val context = SwrContext()
            val setup = swr_alloc_set_opts2(context, outLayout, AV_SAMPLE_FMT_FLT, SAMPLE_RATE, inLayout, inFormat, inRate, 0, null)
            av_channel_layout_uninit(outLayout)
            if (setup < 0 || swr_init(context) < 0) throw UnreadableAudioException("No se pudo convertir el audio")
            swr = context
            swrRate = inRate
            swrFormat = inFormat
            swrChannels = inLayout.nb_channels()
        }
        val context = swr!!
        val inSamples = frame.nb_samples()
        val maxOut = av_rescale_rnd(swr_get_delay(context, inRate.toLong()) + inSamples, SAMPLE_RATE.toLong(), inRate.toLong(), AV_ROUND_UP).toInt() + 32
        val converted = runResampler(context, frame.extended_data(), inSamples, maxOut)
        if (converted <= 0) return 0
        // Hora de este fotograma, para descartar lo anterior al punto al que se ha saltado.
        var skip = 0
        if (discardUntilFrames >= 0) {
            val pts = frame.best_effort_timestamp().takeIf { it != AV_NOPTS_VALUE } ?: frame.pts()
            if (pts != AV_NOPTS_VALUE) {
                val startFrames = pts * SAMPLE_RATE * timeBaseNum / timeBaseDen
                skip = (discardUntilFrames - startFrames).coerceIn(0, converted.toLong()).toInt()
                if (startFrames + converted >= discardUntilFrames) discardUntilFrames = -1
            } else {
                discardUntilFrames = -1
            }
        }
        return keep(converted, skip)
    }

    private fun runResampler(context: SwrContext, input: PointerPointer<*>?, inSamples: Int, maxOut: Int): Int {
        if (outCapacity < maxOut) {
            outPointer.close()
            outPointer = FloatPointer((maxOut * CHANNELS).toLong())
            outCapacity = maxOut
        }
        // Ojo: PointerPointer(puntero) no hace una lista, reinterpreta esa memoria. Se usa put().
        outArray.put(0L, outPointer)
        val count = swr_convert(context, outArray, maxOut, input, inSamples)
        if (count < 0) throw UnreadableAudioException("No se pudo convertir el audio")
        return count
    }

    /** Copia lo convertido a [pending], saltándose las primeras [skip] muestras. */
    private fun keep(count: Int, skip: Int): Int {
        val keepFrames = count - skip
        if (keepFrames <= 0) return 0
        // Lo que quedaba sin entregar se mueve al principio.
        val leftover = pendingEnd - pendingStart
        if (leftover > 0 && pendingStart > 0) System.arraycopy(pending, pendingStart, pending, 0, leftover)
        pendingStart = 0
        pendingEnd = leftover
        val needed = pendingEnd + keepFrames * CHANNELS
        if (needed > pending.size) pending = pending.copyOf(maxOf(needed, pending.size * 2))
        outPointer.position((skip * CHANNELS).toLong())
        outPointer.get(pending, pendingEnd, keepFrames * CHANNELS)
        outPointer.position(0)
        pendingEnd += keepFrames * CHANNELS
        return keepFrames
    }

    private fun flushResampler() {
        val context = swr ?: return
        val delay = swr_get_delay(context, SAMPLE_RATE.toLong()).toInt()
        if (delay <= 0) return
        val count = runResampler(context, null, 0, delay + 32)
        if (count > 0) keep(count, 0)
    }

    private fun resetResampler() {
        swr?.let { swr_free(it) }
        swr = null
    }

    private fun release() {
        resetResampler()
        codec?.let { avcodec_free_context(it) }
        codec = null
        format?.let { avformat_close_input(it) }
        format = null
        avio?.let { context ->
            // FFmpeg puede haber cambiado el búfer: se libera el que tenga ahora.
            context.buffer()?.let { av_free(it) }
            avio_context_free(context)
        }
        avio = null
        av_packet_free(packet)
        av_frame_free(frame)
        outArray.close()
        outPointer.close()
    }

    override fun close() {
        release()
        input.close()
    }

    companion object {
        const val SAMPLE_RATE = 48_000
        const val CHANNELS = 2
        private const val IO_BUFFER = 64 * 1024
        private const val AVFMT_FLAG_CUSTOM_IO = 0x0080
        private const val AVERROR_EAGAIN = -11
        private const val AVERROR_IO = -5
        private const val SEEK_SET = 0
        private const val SEEK_CUR = 1
        private const val SEEK_END = 2

        fun errorText(code: Int): String {
            val buffer = ByteArray(128)
            av_strerror(code, buffer, buffer.size.toLong())
            return String(buffer).trimEnd('\u0000').ifBlank { "error $code" }
        }
    }
}

/** Carga las piezas nativas de FFmpeg (la primera vez se copian a la carpeta de Lyra). */
object Natives {
    @Volatile private var loaded = false

    fun ensureLoaded() {
        if (loaded) return
        synchronized(this) {
            if (loaded) return
            av_log_set_level(AV_LOG_QUIET)
            loaded = true
        }
    }
}
