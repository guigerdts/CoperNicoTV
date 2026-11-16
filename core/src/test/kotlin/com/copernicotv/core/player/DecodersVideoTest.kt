package com.copernicotv.core.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test

/**
 * Política de selección de decodificadores de video (E7-T11, S1–S3).
 *
 * Los datos del inventario llegan del dispositivo (MediaCodecList); aquí
 * solo se prueba la decisión pura: hardware primero, software después,
 * externo al final, con motivo honesto cuando nada sirve.
 */
class DecodersVideoTest {
    private val h264Hardware =
        DecoderVideo(
            nombre = "c2.exynos.h264.decoder",
            mime = "video/avc",
            tipo = TipoDecoder.HARDWARE,
            perfiles = listOf(0x42, 0x4D),
            anchoMax = 1920,
            altoMax = 1080,
        )
    private val h264Software =
        DecoderVideo(
            nombre = "c2.android.avc.decoder",
            mime = "video/avc",
            tipo = TipoDecoder.SOFTWARE,
            perfiles = listOf(0x42, 0x64),
            anchoMax = 1920,
            altoMax = 1080,
        )
    private val mpeg2Hardware =
        DecoderVideo(
            nombre = "OMX.sprd.mpeg2.decoder",
            mime = "video/mpeg2",
            tipo = TipoDecoder.HARDWARE,
        )

    @Test
    fun `el codec string avc declara perfil y nivel hexadecimales`() {
        assertEquals(PerfilNivel(0x64, 0x28), perfilNivelDeCodec("avc1.640028"))
        assertEquals(PerfilNivel(0x42, 0x20), perfilNivelDeCodec("avc3.42C020"))
    }

    @Test
    fun `los codec strings sin perfil parseable quedan desconocidos`() {
        assertNull(perfilNivelDeCodec(null))
        assertNull(perfilNivelDeCodec(""))
        assertNull(perfilNivelDeCodec("hev1.1.6.L93.B0"))
        assertNull(perfilNivelDeCodec("mpg1"))
    }

    @Test
    fun `sin pista de video no hay nada que evaluar`() {
        assertEquals(
            MotivoSoporteVideo.PISTA_AUSENTE,
            seleccionarDecoderVideo(DeclaracionFormatoVideo(mime = null), listOf(h264Hardware)).motivo,
        )
    }

    @Test
    fun `un mime sin decoder en el dispositivo es no soportado`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mpeg2"),
                listOf(h264Hardware),
            )
        assertEquals(MotivoSoporteVideo.MIME_NO_SOPORTADO, seleccion.motivo)
        assertNull(seleccion.decoder)
    }

    @Test
    fun `el decoder hardware se prefiere sobre el software`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/avc", codec = "avc1.4D401E"),
                listOf(h264Software, h264Hardware),
            )
        assertEquals(MotivoSoporteVideo.SOPORTADO, seleccion.motivo)
        assertEquals("c2.exynos.h264.decoder", seleccion.decoder)
        assertEquals(TipoDecoder.HARDWARE, seleccion.tipo)
    }

    @Test
    fun `el software responde cuando el hardware no soporta el perfil`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/avc", codec = "avc1.640028"),
                listOf(h264Hardware, h264Software),
            )
        assertEquals(MotivoSoporteVideo.SOPORTADO, seleccion.motivo)
        assertEquals("c2.android.avc.decoder", seleccion.decoder)
        assertEquals(TipoDecoder.SOFTWARE, seleccion.tipo)
    }

    @Test
    fun `el perfil incompatible se rechaza con la lista de decoders descartados`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/avc", codec = "avc1.530028"),
                listOf(h264Hardware, h264Software),
            )
        assertEquals(MotivoSoporteVideo.PERFIL_NO_SOPORTADO, seleccion.motivo)
        assertNull(seleccion.decoder)
        assertEquals(listOf("c2.exynos.h264.decoder", "c2.android.avc.decoder"), seleccion.rechazados)
    }

    @Test
    fun `la resolucion fuera de limites se rechaza aunque el perfil sirva`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/avc", codec = "avc1.42C028", ancho = 3840, alto = 2160),
                listOf(h264Hardware),
            )
        assertEquals(MotivoSoporteVideo.RESOLUCION_NO_SOPORTADA, seleccion.motivo)
        assertNull(seleccion.decoder)
    }

    @Test
    fun `el perfil desconocido no finge rechazo de perfil`() {
        // MPEG-2 suele llegar sin codec string: la evaluación por MIME y
        // resolución es lo único que el dispositivo realmente declara.
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mpeg2", codec = null),
                listOf(mpeg2Hardware),
            )
        assertEquals(MotivoSoporteVideo.SOPORTADO, seleccion.motivo)
        assertEquals("OMX.sprd.mpeg2.decoder", seleccion.decoder)
    }

    @Test
    fun `el decoder externo entra solo cuando no hay nativo para el mime`() {
        val externo =
            object : DecoderVideoExterno {
                override val nombre = "externo-ffmpeg-mpeg2"
                override val tipo = TipoDecoder.SOFTWARE

                override fun declarar(mime: String): DecoderVideo? =
                    if (mime == "video/mpeg2") {
                        DecoderVideo(nombre, mime, tipo)
                    } else {
                        null
                    }
            }

        val conNativo =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mpeg2"),
                listOf(mpeg2Hardware),
                listOf(externo),
            )
        assertEquals("OMX.sprd.mpeg2.decoder", conNativo.decoder)

        val sinNativo =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mpeg2"),
                emptyList(),
                listOf(externo),
            )
        assertEquals(MotivoSoporteVideo.SOPORTADO, sinNativo.motivo)
        assertEquals("externo-ffmpeg-mpeg2", sinNativo.decoder)
        assertEquals(TipoDecoder.SOFTWARE, sinNativo.tipo)
    }

    @Test
    fun `sin inventario y sin externos la pista es definitivamente no soportada`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mpeg2"),
                emptyList(),
                emptyList(),
            )
        assertEquals(MotivoSoporteVideo.MIME_NO_SOPORTADO, seleccion.motivo)
        assertNull(seleccion.decoder)
    }

    @Test
    fun `la resolucion desconocida no se rechaza por resolucion`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/avc", codec = "avc1.42C028", ancho = null, alto = null),
                listOf(h264Hardware),
            )
        assertEquals(MotivoSoporteVideo.SOPORTADO, seleccion.motivo)
        assertEquals("c2.exynos.h264.decoder", seleccion.decoder)
    }
}
