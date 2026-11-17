package com.copernicotv.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Pruebas de la ruta software E7-T12 (S2/S3): mapa MIME → codec libavcodec,
 * integración del decoder externo en la política de E7-T11 y veredicto por
 * inventario.
 */
class DecodersSoftwareTest {
    private val externo = DecoderSoftwareVideo()

    @Test
    fun `el mapa cubre los tres codecs de la matriz inicial`() {
        assertEquals("mpeg2video", DecodersSoftware.codecDeMime("video/mpeg2"))
        assertEquals("mpeg4", DecodersSoftware.codecDeMime("video/mp4v-es"))
        assertEquals("h263", DecodersSoftware.codecDeMime("video/3gpp"))
    }

    @Test
    fun `el mapa es insensible a mayusculas y rechaza lo no compilado`() {
        assertEquals("mpeg2video", DecodersSoftware.codecDeMime("VIDEO/MPEG2"))
        assertNull(DecodersSoftware.codecDeMime("video/avc"))
        assertNull(DecodersSoftware.codecDeMime("video/hevc"))
        assertNull(DecodersSoftware.codecDeMime(null))
        assertNull(DecodersSoftware.codecDeMime("video/mpeg"))
    }

    @Test
    fun `el decoder externo declara solo los codecs compilados`() {
        val mpeg2 = externo.declarar("video/mpeg2")
        assertEquals("ffmpeg:mpeg2video", mpeg2?.nombre)
        assertEquals(TipoDecoder.SOFTWARE, mpeg2?.tipo)
        assertNull(externo.declarar("video/avc"))
    }

    @Test
    fun `hardware presente gana y el externo no se usa`() {
        val hardware =
            DecoderVideo(
                nombre = "c2.exynos.mpeg2.decoder",
                mime = "video/mpeg2",
                tipo = TipoDecoder.HARDWARE,
            )
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mpeg2"),
                listOf(hardware),
                listOf(externo),
            )
        assertEquals(MotivoSoporteVideo.SOPORTADO, seleccion.motivo)
        assertEquals("c2.exynos.mpeg2.decoder", seleccion.decoder)
        assertEquals(TipoDecoder.HARDWARE, seleccion.tipo)
    }

    @Test
    fun `software nativo del dispositivo gana sobre el externo`() {
        val nativo =
            DecoderVideo(
                nombre = "c2.android.mpeg4.decoder",
                mime = "video/mp4v-es",
                tipo = TipoDecoder.SOFTWARE,
            )
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mp4v-es"),
                listOf(nativo),
                listOf(externo),
            )
        assertEquals("c2.android.mpeg4.decoder", seleccion.decoder)
    }

    @Test
    fun `sin decoder nativo el externo toma el turno`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/mpeg2"),
                emptyList(),
                listOf(externo),
            )
        assertEquals(MotivoSoporteVideo.SOPORTADO, seleccion.motivo)
        assertEquals("ffmpeg:mpeg2video", seleccion.decoder)
        assertEquals(TipoDecoder.SOFTWARE, seleccion.tipo)
    }

    @Test
    fun `mime fuera del subconjunto sigue sin soporte`() {
        val seleccion =
            seleccionarDecoderVideo(
                DeclaracionFormatoVideo(mime = "video/hevc"),
                emptyList(),
                listOf(externo),
            )
        assertEquals(MotivoSoporteVideo.MIME_NO_SOPORTADO, seleccion.motivo)
    }

    @Test
    fun `el nombre del decoder reportado identifica el codec`() {
        assertTrue(DecodersSoftware.nombreDeCodec("h263").startsWith("ffmpeg:"))
        assertEquals("ffmpeg:h263", DecodersSoftware.nombreDeCodec("h263"))
    }
}
