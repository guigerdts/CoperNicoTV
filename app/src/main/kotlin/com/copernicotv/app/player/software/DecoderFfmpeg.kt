package com.copernicotv.app.player.software

import androidx.media3.common.C
import androidx.media3.decoder.DecoderException
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.DecoderOutputBuffer
import androidx.media3.decoder.SimpleDecoder
import androidx.media3.decoder.VideoDecoderOutputBuffer
import com.copernicotv.core.player.DecodersSoftware
import java.nio.ByteBuffer

/**
 * Excepción del decoder FFmpeg (E7-T12). El mensaje es un código seguro
 * para el diagnóstico: nunca contenido del stream ni rutas locales.
 */
class DecoderExcepcionFfmpeg(mensaje: String, causa: Throwable? = null) :
    DecoderException(mensaje, causa)

/**
 * Decoder de video por software sobre libavcodec (E7-T12, S2).
 *
 * Extiende [SimpleDecoder]: Media3 le dedica un hilo de decodificación
 * propio, de modo que la decodificación por software nunca bloquea el hilo
 * principal (criterio de aceptación). Cada instancia abre su propio
 * decoder nativo por [codecName] (`mpeg2video`, `mpeg4`, `h263`) y el JNI
 * copia los planos YUV des-stridendo filas para la subida GLES2.
 *
 * El reinicio de estado (`avcodec_flush_buffers`) ocurre dentro de
 * [decode] cuando Media3 marca `reset`, que es el punto del ciclo de vida
 * donde `SimpleDecoder` pide reconstruir el estado interno.
 */
class DecoderFfmpeg(private val codecName: String) :
    SimpleDecoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderExcepcionFfmpeg>(
        buffersEntrada(NUM_BUFFERS),
        buffersSalida(NUM_BUFFERS),
    ),
    DecoderOutputBuffer.Owner<VideoDecoderOutputBuffer> {
    private var handle: Long = 0L
    private var modoSalida: Int = C.VIDEO_OUTPUT_MODE_YUV

    init {
        handle = nativeCreateDecoder(codecName)
        if (handle == 0L) {
            throw DecoderExcepcionFfmpeg("ffmpeg_no_inicializado:$codecName")
        }
    }

    /** Nombre reportado al diagnóstico (E7-T12, S4). */
    override fun getName(): String = DecodersSoftware.nombreDeCodec(codecName)

    override fun createInputBuffer(): DecoderInputBuffer = DecoderInputBuffer(DecoderInputBuffer.BUFFER_REPLACEMENT_MODE_DIRECT)

    override fun createOutputBuffer(): VideoDecoderOutputBuffer = VideoDecoderOutputBuffer(this)

    override fun createUnexpectedDecodeException(error: Throwable): DecoderExcepcionFfmpeg =
        DecoderExcepcionFfmpeg("ffmpeg_error_inesperado:${error.javaClass.simpleName}", error)

    fun establecerModoSalida(modo: Int) {
        modoSalida = modo
    }

    override fun decode(
        entrada: DecoderInputBuffer,
        salida: VideoDecoderOutputBuffer,
        reinicio: Boolean,
    ): DecoderExcepcionFfmpeg? {
        val manejador = handle
        if (manejador == 0L) {
            return DecoderExcepcionFfmpeg("ffmpeg_decoder_liberado")
        }
        if (reinicio) {
            nativeFlushDecoder(manejador)
        }
        if (entrada.isEndOfStream) {
            nativeSendPacket(manejador, null, 0, 0, 0L)
            salida.init(entrada.timeUs, modoSalida, null)
            salida.addFlag(C.BUFFER_FLAG_END_OF_STREAM)
            return null
        }
        val datos = entrada.data ?: return DecoderExcepcionFfmpeg("ffmpeg_paquete_sin_datos")
        val envio =
            nativeSendPacket(manejador, datos, datos.position(), datos.remaining(), entrada.timeUs)
        if (envio != 0) {
            return DecoderExcepcionFfmpeg("ffmpeg_error_envio:$envio")
        }
        return recibirFrame(manejador, entrada, salida)
    }

    /** Recibe un frame decodificado y lo copia a los planos de la salida. */
    private fun recibirFrame(
        manejador: Long,
        entrada: DecoderInputBuffer,
        salida: VideoDecoderOutputBuffer,
    ): DecoderExcepcionFfmpeg? {
        val info = IntArray(3)
        val pts = LongArray(1)
        val estado = nativeReceiveFrame(manejador, info, pts)
        return when (estado) {
            0, -100 -> null // EAGAIN o drain: sin salida en esta vuelta
            1 -> {
                val ancho = info[0]
                val alto = info[1]
                val espacio = info[2]
                val tiempo =
                    if (pts[0] == Long.MIN_VALUE) entrada.timeUs else pts[0]
                salida.init(tiempo, modoSalida, null)
                if (!salida.initForYuvFrame(ancho, alto, ancho, (ancho + 1) / 2, espacio)) {
                    return DecoderExcepcionFfmpeg("ffmpeg_salida_yuv_inaceptable")
                }
                val planos = salida.yuvPlanes
                if (planos == null) {
                    return DecoderExcepcionFfmpeg("ffmpeg_planos_ausentes")
                }
                val copia =
                    nativeCopyFrame(
                        manejador,
                        arrayOf(planos[0], planos[1], planos[2]),
                    )
                if (copia != 0) {
                    return DecoderExcepcionFfmpeg("ffmpeg_error_copia_planos:$copia")
                }
                null
            }
            else -> DecoderExcepcionFfmpeg("ffmpeg_error_decodificacion:$estado")
        }
    }

    override fun releaseOutputBuffer(salida: VideoDecoderOutputBuffer) {
        // Contrato de VideoDecoderOutputBuffer.Owner: devolver a la cola.
        super.releaseOutputBuffer(salida)
    }

    override fun release() {
        /* Primero SimpleDecoder (espera al hilo de decodificación), luego el
         * estado nativo: libera sin carrera contra el hilo de decode. */
        super.release()
        if (handle != 0L) {
            nativeReleaseDecoder(handle)
            handle = 0L
        }
    }

    private external fun nativeCreateDecoder(codecName: String): Long

    private external fun nativeSendPacket(
        handle: Long,
        datos: ByteBuffer?,
        offset: Int,
        size: Int,
        ptsUs: Long,
    ): Int

    private external fun nativeReceiveFrame(
        handle: Long,
        info: IntArray,
        pts: LongArray,
    ): Int

    private external fun nativeCopyFrame(
        handle: Long,
        planos: Array<ByteBuffer>,
    ): Int

    private external fun nativeFlushDecoder(handle: Long)

    private external fun nativeReleaseDecoder(handle: Long)

    private companion object {
        const val NUM_BUFFERS = 4

        @Suppress("UNCHECKED_CAST")
        fun buffersEntrada(cantidad: Int): Array<DecoderInputBuffer> =
            arrayOfNulls<DecoderInputBuffer>(cantidad) as Array<DecoderInputBuffer>

        @Suppress("UNCHECKED_CAST")
        fun buffersSalida(cantidad: Int): Array<VideoDecoderOutputBuffer> =
            arrayOfNulls<VideoDecoderOutputBuffer>(cantidad) as Array<VideoDecoderOutputBuffer>
    }
}
