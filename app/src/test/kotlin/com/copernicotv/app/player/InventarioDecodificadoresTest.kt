package com.copernicotv.app.player

import com.copernicotv.core.player.DeclaracionFormatoVideo
import com.copernicotv.core.player.DecoderVideo
import com.copernicotv.core.player.DecoderVideoExterno
import com.copernicotv.core.player.MotivoSoporteVideo
import com.copernicotv.core.player.TipoDecoder
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * Consulta del inventario de decoders sobre una fuente inyectada (E7-T11, T2).
 *
 * La conversión real `MediaCodecInfo → DecoderVideo` requiere el framework
 * Android y se cubre con pruebas instrumentadas; aquí se prueba la consulta
 * con la política de `:core`, incluyendo los decoders externos de la fase 2.
 */
class InventarioDecodificadoresTest {
    private val h264 =
        DecoderVideo(
            nombre = "c2.exynos.h264.decoder",
            mime = "video/avc",
            tipo = TipoDecoder.HARDWARE,
            perfiles = listOf(0x42, 0x4D, 0x64),
            anchoMax = 1920,
            altoMax = 1080,
        )

    @Test
    fun `la consulta delega en la política con el inventario del dispositivo`() {
        val inventario = InventarioDecodificadores(fuente = { listOf(h264) })

        val seleccion =
            inventario.seleccionar(
                DeclaracionFormatoVideo(mime = "video/avc", codec = "avc1.4D401E", ancho = 1280, alto = 720),
            )

        assertEquals(MotivoSoporteVideo.SOPORTADO, seleccion.motivo)
        assertEquals("c2.exynos.h264.decoder", seleccion.decoder)
        assertEquals(TipoDecoder.HARDWARE, seleccion.tipo)
    }

    @Test
    fun `el inventario se consulta una sola vez aunque lleguen varios formatos`() {
        var consultas = 0
        val inventario =
            InventarioDecodificadores(
                fuente = {
                    consultas += 1
                    listOf(h264)
                },
            )

        inventario.seleccionar(DeclaracionFormatoVideo(mime = "video/avc"))
        inventario.seleccionar(DeclaracionFormatoVideo(mime = "video/mpeg2"))

        assertEquals(1, consultas)
    }

    @Test
    fun `sin inventario disponible la consulta no falla y no soporta`() {
        val inventario = InventarioDecodificadores(fuente = { emptyList() })

        val seleccion = inventario.seleccionar(DeclaracionFormatoVideo(mime = "video/mpeg2"))

        assertEquals(MotivoSoporteVideo.MIME_NO_SOPORTADO, seleccion.motivo)
    }

    @Test
    fun `los decoders externos registrados entran despues del nativo`() {
        val externoMpeg2 =
            object : DecoderVideoExterno {
                override val nombre = "externo-mpeg2"
                override val tipo = TipoDecoder.SOFTWARE

                override fun declarar(mime: String): DecoderVideo? =
                    if (mime == "video/mpeg2") {
                        DecoderVideo(nombre, mime, tipo)
                    } else {
                        null
                    }
            }
        val inventario = InventarioDecodificadores(externos = listOf(externoMpeg2), fuente = { listOf(h264) })

        val soportado = inventario.seleccionar(DeclaracionFormatoVideo(mime = "video/mpeg2"))
        val nativo = inventario.seleccionar(DeclaracionFormatoVideo(mime = "video/avc"))

        assertEquals("externo-mpeg2", soportado.decoder)
        assertEquals("c2.exynos.h264.decoder", nativo.decoder)
    }
}
