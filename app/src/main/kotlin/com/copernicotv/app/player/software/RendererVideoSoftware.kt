package com.copernicotv.app.player.software

import android.opengl.EGL14
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Handler
import android.view.Surface
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.decoder.CryptoConfig
import androidx.media3.decoder.Decoder
import androidx.media3.decoder.DecoderInputBuffer
import androidx.media3.decoder.VideoDecoderOutputBuffer
import androidx.media3.exoplayer.RendererCapabilities
import androidx.media3.exoplayer.video.DecoderVideoRenderer
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.copernicotv.core.player.DeclaracionFormatoVideo
import com.copernicotv.core.player.DecoderSoftwareVideo
import com.copernicotv.core.player.DecoderVideo
import com.copernicotv.core.player.DecodersSoftware
import com.copernicotv.core.player.seleccionarDecoderVideo
import java.nio.ByteBuffer

/**
 * Renderer de video por software (E7-T12, S2).
 *
 * Extiende `DecoderVideoRenderer` de la API pública de Media3 1.5.0: la base
 * maneja lectura de fuente, cola del decoder, sincronización de
 * presentación y descarte de frames; esta clase solo decide soporte,
 * construye el decoder y dibuja los planos YUV sobre la Surface con
 * EGL/GLES2 (matrices BT601/BT709/BT2020 según el colorspace del frame).
 *
 * Soporte: reporta `FORMAT_HANDLED` solo cuando la política pura de
 * `:core` recae en el decoder externo FFmpeg — es decir, cuando ningún
 * decoder nativo del dispositivo (hardware ni software MediaCodec) declara
 * el MIME. Hardware primero garantizado por la misma política de E7-T11;
 * H.264 nunca entra por este camino (regresión obligatoria intacta).
 */
class RendererVideoSoftware(
    eventHandler: Handler?,
    eventListener: VideoRendererEventListener?,
    private val inventarioNativo: () -> List<DecoderVideo>,
) : DecoderVideoRenderer(
        MS_UNION_PERMITIDO,
        eventHandler,
        eventListener,
        MAX_FRAMES_DESCARTADOS_AVISO,
    ) {
    private val externo = DecoderSoftwareVideo()
    private val salida = SalidaEgl()
    private var decoderActual: DecoderFfmpeg? = null

    override fun getName(): String = NOMBRE

    override fun supportsFormat(format: Format): Int {
        if (format.drmInitData != null) {
            return RendererCapabilities.create(C.FORMAT_UNSUPPORTED_DRM)
        }
        val mime = format.sampleMimeType
        val codec = DecodersSoftware.codecDeMime(mime) ?: return noSoportado()
        val declaracion =
            DeclaracionFormatoVideo(
                mime = mime,
                codec = format.codecs,
                ancho = format.width.takeIf { it > 0 },
                alto = format.height.takeIf { it > 0 },
            )
        val seleccion = seleccionarDecoderVideo(declaracion, inventarioNativo(), listOf(externo))
        return if (seleccion.decoder == DecodersSoftware.nombreDeCodec(codec)) {
            RendererCapabilities.create(
                C.FORMAT_HANDLED,
                RendererCapabilities.ADAPTIVE_NOT_SUPPORTED,
                RendererCapabilities.TUNNELING_NOT_SUPPORTED,
            )
        } else {
            noSoportado()
        }
    }

    private fun noSoportado(): Int = RendererCapabilities.create(C.FORMAT_UNSUPPORTED_TYPE)

    override fun createDecoder(
        format: Format,
        cryptoConfig: CryptoConfig?,
    ): Decoder<DecoderInputBuffer, VideoDecoderOutputBuffer, DecoderExcepcionFfmpeg> {
        if (cryptoConfig != null) {
            throw DecoderExcepcionFfmpeg("ffmpeg_drm_no_soportado")
        }
        val codec =
            DecodersSoftware.codecDeMime(format.sampleMimeType)
                ?: throw DecoderExcepcionFfmpeg("ffmpeg_mime_sin_codec:${format.sampleMimeType}")
        return DecoderFfmpeg(codec).also { decoderActual = it }
    }

    override fun setDecoderOutputMode(modo: Int) {
        decoderActual?.establecerModoSalida(modo)
    }

    override fun releaseDecoder() {
        decoderActual = null
        super.releaseDecoder()
    }

    override fun renderOutputBufferToSurface(
        salida: VideoDecoderOutputBuffer,
        surface: Surface,
    ) {
        this.salida.asegurar(surface)
        this.salida.dibujar(salida)
    }

    override fun onDisabled() {
        try {
            salida.liberar()
        } finally {
            super.onDisabled()
        }
    }

    private companion object {
        const val NOMBRE = "RendererVideoSoftware"
        const val MS_UNION_PERMITIDO = 5_000L
        const val MAX_FRAMES_DESCARTADOS_AVISO = 50
    }
}

/**
 * Salida GL para una Surface: contexto EGL lazy, tres texturas LUMINANCE
 * (Y, U, V de planos ya sin stride) y un quad con conversión YUV→RGB según
 * el colorspace del frame. Se recrea si la Surface cambia (canal, rotación)
 * y se libera al deshabilitar el renderer.
 */
private class SalidaEgl {
    private var display: EGLDisplay = EGL14.EGL_NO_DISPLAY
    private var context: EGLContext = EGL14.EGL_NO_CONTEXT
    private var superficieEgl: EGLSurface = EGL14.EGL_NO_SURFACE
    private var superficieActual: Surface? = null
    private var configActual: android.opengl.EGLConfig? = null
    private val texturas = IntArray(3)
    private var programa = 0
    private var anchoTextura = 0
    private var altoTextura = 0
    private var localMatriz = 0
    private val posiciones =
        bufferDirecto(
            floatArrayOf(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f),
        )
    private val coordenadas =
        bufferDirecto(
            floatArrayOf(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f),
        )

    /** Prepara contexto y superficie; reutiliza si es la misma Surface. */
    fun asegurar(surface: Surface) {
        if (superficieActual === surface && context != EGL14.EGL_NO_CONTEXT) {
            return
        }
        liberar()
        display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
        if (display == EGL14.EGL_NO_DISPLAY) {
            throw DecoderExcepcionFfmpeg("ffmpeg_egl_display_no_disponible")
        }
        val version = intArrayOf(0, 0)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw DecoderExcepcionFfmpeg("ffmpeg_egl_initialize_fallo")
        }
        context = crearContexto()
        superficieEgl = crearSuperficie(surface)
        if (!EGL14.eglMakeCurrent(display, superficieEgl, superficieEgl, context)) {
            liberar()
            throw DecoderExcepcionFfmpeg("ffmpeg_egl_makecurrent_fallo")
        }
        superficieActual = surface
        inicializarGl()
    }

    /** Elige configuración GLES2 y crea el contexto. */
    private fun crearContexto(): EGLContext {
        val atributosConfig =
            intArrayOf(
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                EGL14.EGL_WINDOW_BIT,
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<android.opengl.EGLConfig>(1)
        val numConfigs = intArrayOf(0)
        if (
            !EGL14.eglChooseConfig(display, atributosConfig, 0, configs, 0, 1, numConfigs, 0) ||
            numConfigs[0] == 0
        ) {
            throw DecoderExcepcionFfmpeg("ffmpeg_egl_config_no_encontrado")
        }
        configActual = configs[0]
        val atributosContexto = intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE)
        val contextoCreado =
            EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT, atributosContexto, 0)
        if (contextoCreado == EGL14.EGL_NO_CONTEXT) {
            throw DecoderExcepcionFfmpeg("ffmpeg_egl_context_no_creado")
        }
        return contextoCreado
    }

    /** Crea la superficie de ventana sobre la Surface de Media3. */
    private fun crearSuperficie(surface: Surface): EGLSurface {
        val atributos = intArrayOf(EGL14.EGL_NONE)
        val creada =
            EGL14.eglCreateWindowSurface(display, configActual, surface, atributos, 0)
        if (creada == EGL14.EGL_NO_SURFACE) {
            throw DecoderExcepcionFfmpeg("ffmpeg_egl_surface_no_creada")
        }
        return creada
    }

    /** Sube los planos YUV y dibuja el quad convertidos a RGB. */
    fun dibujar(buffer: VideoDecoderOutputBuffer) {
        val planos = buffer.yuvPlanes ?: throw DecoderExcepcionFfmpeg("ffmpeg_planos_ausentes")
        val ancho = buffer.width
        val alto = buffer.height
        if (ancho <= 0 || alto <= 0) {
            throw DecoderExcepcionFfmpeg("ffmpeg_medidas_invalidas")
        }
        if (ancho != anchoTextura || alto != altoTextura) {
            anchoTextura = ancho
            altoTextura = alto
        }
        val uvAncho = (ancho + 1) / 2
        val uvAlto = (alto + 1) / 2
        val medidas = intArrayOf(ancho, alto, uvAncho, uvAlto, uvAncho, uvAlto)
        for (i in 0..2) {
            GLES20.glActiveTexture(GLES20.GL_TEXTURE0 + i)
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texturas[i])
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            val plano = planos[i]
            plano.position(0)
            GLES20.glTexImage2D(
                GLES20.GL_TEXTURE_2D,
                0,
                GLES20.GL_LUMINANCE,
                medidas[i * 2],
                medidas[i * 2 + 1],
                0,
                GLES20.GL_LUMINANCE,
                GLES20.GL_UNSIGNED_BYTE,
                plano,
            )
        }
        val errorTextura = GLES20.glGetError()
        if (errorTextura != GLES20.GL_NO_ERROR) {
            throw DecoderExcepcionFfmpeg("ffmpeg_gl_error_textura:$errorTextura")
        }
        GLES20.glUseProgram(programa)
        GLES20.glUniform1i(localMatriz, buffer.colorspace)
        GLES20.glViewport(0, 0, ancho, alto)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        if (!EGL14.eglSwapBuffers(display, superficieEgl)) {
            throw DecoderExcepcionFfmpeg("ffmpeg_egl_swap_fallo")
        }
    }

    /** Libera la superficie, el contexto y los recursos GL asociados. */
    fun liberar() {
        if (display == EGL14.EGL_NO_DISPLAY) {
            return
        }
        if (programa != 0) {
            GLES20.glDeleteProgram(programa)
            programa = 0
        }
        GLES20.glDeleteTextures(3, texturas, 0)
        if (superficieEgl != EGL14.EGL_NO_SURFACE) {
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            EGL14.eglDestroySurface(display, superficieEgl)
            superficieEgl = EGL14.EGL_NO_SURFACE
        }
        if (context != EGL14.EGL_NO_CONTEXT) {
            EGL14.eglDestroyContext(display, context)
            context = EGL14.EGL_NO_CONTEXT
        }
        superficieActual = null
    }

    /** Compila el programa, crea las tres texturas y fija los atributos. */
    private fun inicializarGl() {
        programa = compilarPrograma()
        GLES20.glGenTextures(3, texturas, 0)
        GLES20.glClearColor(0f, 0f, 0f, 1f)
        GLES20.glUseProgram(programa)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programa, "uY"), 0)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programa, "uU"), 1)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(programa, "uV"), 2)
        localMatriz = GLES20.glGetUniformLocation(programa, "uMatriz")
        val localPos = GLES20.glGetAttribLocation(programa, "aPos")
        val localCoord = GLES20.glGetAttribLocation(programa, "aCoord")
        GLES20.glVertexAttribPointer(localPos, 2, GLES20.GL_FLOAT, false, 0, posiciones)
        GLES20.glVertexAttribPointer(localCoord, 2, GLES20.GL_FLOAT, false, 0, coordenadas)
        GLES20.glEnableVertexAttribArray(localPos)
        GLES20.glEnableVertexAttribArray(localCoord)
    }

    private fun bufferDirecto(valores: FloatArray): java.nio.FloatBuffer =
        java.nio.ByteBuffer
            .allocateDirect(valores.size * 4)
            .order(java.nio.ByteOrder.nativeOrder())
            .asFloatBuffer()
            .apply {
                put(valores)
                rewind()
            }

    private fun compilarPrograma(): Int {
        val vertex =
            """
            attribute vec2 aPos;
            attribute vec2 aCoord;
            varying vec2 vCoord;
            void main() {
                vCoord = aCoord;
                gl_Position = vec4(aPos, 0.0, 1.0);
            }
            """.trimIndent()
        val fragment =
            """
            precision mediump float;
            varying vec2 vCoord;
            uniform sampler2D uY;
            uniform sampler2D uU;
            uniform sampler2D uV;
            uniform int uMatriz;
            void main() {
                float y = texture2D(uY, vCoord).r - 0.0625;
                float u = texture2D(uU, vCoord).r - 0.5;
                float v = texture2D(uV, vCoord).r - 0.5;
                float r;
                float g;
                float b;
                if (uMatriz == 2) {
                    r = 1.164 * y + 1.793 * v;
                    g = 1.164 * y - 0.213 * u - 0.533 * v;
                    b = 1.164 * y + 2.112 * u;
                } else if (uMatriz == 3) {
                    r = 1.164 * y + 1.678 * v;
                    g = 1.164 * y - 0.246 * u - 0.691 * v;
                    b = 1.164 * y + 2.079 * u;
                } else {
                    r = 1.164 * y + 1.596 * v;
                    g = 1.164 * y - 0.391 * u - 0.813 * v;
                    b = 1.164 * y + 2.018 * u;
                }
                gl_FragColor = vec4(clamp(r, 0.0, 1.0), clamp(g, 0.0, 1.0), clamp(b, 0.0, 1.0), 1.0);
            }
            """.trimIndent()
        val shaderVertex = compilarShader(GLES20.GL_VERTEX_SHADER, vertex)
        val shaderFragment = compilarShader(GLES20.GL_FRAGMENT_SHADER, fragment)
        val programa = GLES20.glCreateProgram()
        GLES20.glAttachShader(programa, shaderVertex)
        GLES20.glAttachShader(programa, shaderFragment)
        GLES20.glLinkProgram(programa)
        val estado = IntArray(1)
        GLES20.glGetProgramiv(programa, GLES20.GL_LINK_STATUS, estado, 0)
        if (estado[0] != GLES20.GL_TRUE) {
            throw DecoderExcepcionFfmpeg("ffmpeg_gl_link_fallo")
        }
        return programa
    }

    private fun compilarShader(
        tipo: Int,
        fuente: String,
    ): Int {
        val shader = GLES20.glCreateShader(tipo)
        GLES20.glShaderSource(shader, fuente)
        GLES20.glCompileShader(shader)
        val estado = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, estado, 0)
        if (estado[0] != GLES20.GL_TRUE) {
            throw DecoderExcepcionFfmpeg("ffmpeg_gl_compilacion_fallo")
        }
        return shader
    }
}
