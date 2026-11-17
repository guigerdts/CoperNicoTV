package com.copernicotv.core.player

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

/**
 * Pruebas del clasificador de condición audiovisual (E7-T10, S3).
 *
 * El clasificador es puro: solo combina señales que Media3 entrega
 * realmente (estado de reproducción, renderizadores activos, primer
 * frame de video). No infiere métricas que el motor no provee.
 */
class CondicionReproduccionTest {
    private fun senal(
        fase: FaseBase,
        audioActivo: Boolean = false,
        videoActivo: Boolean = false,
        primerFrameVideoVisto: Boolean = false,
        falloVisto: FalloVisto = FalloVisto.NINGUNO,
    ) = SenalAudiovisual(
        fase = fase,
        audioActivo = audioActivo,
        videoActivo = videoActivo,
        primerFrameVideoVisto = primerFrameVideoVisto,
        falloVisto = falloVisto,
    )

    @Test
    fun `sin datos aun publica ESPERANDO_DATOS`() {
        assertEquals(
            CondicionReproduccion.ESPERANDO_DATOS,
            clasificarCondicion(senal(FaseBase.ESPERANDO)),
        )
    }

    @Test
    fun `buffering publica BUFFERING aunque los renderizadores esten activos`() {
        assertEquals(
            CondicionReproduccion.BUFFERING,
            clasificarCondicion(
                senal(
                    FaseBase.BUFFERING,
                    audioActivo = true,
                    videoActivo = true,
                    primerFrameVideoVisto = true,
                ),
            ),
        )
    }

    @Test
    fun `pausa y termino y reintento publican DETENIDA`() {
        assertEquals(CondicionReproduccion.DETENIDA, clasificarCondicion(senal(FaseBase.DETENIDA)))
    }

    @Test
    fun `error con decoder clasifica ERROR_DECODIFICACION`() {
        assertEquals(
            CondicionReproduccion.ERROR_DECODIFICACION,
            clasificarCondicion(senal(FaseBase.ERROR, falloVisto = FalloVisto.DECODIFICACION)),
        )
    }

    @Test
    fun `error sin decoder clasifica ERROR_TRANSPORTE`() {
        assertEquals(
            CondicionReproduccion.ERROR_TRANSPORTE,
            clasificarCondicion(senal(FaseBase.ERROR, falloVisto = FalloVisto.TRANSPORTE)),
        )
        assertEquals(
            CondicionReproduccion.ERROR_TRANSPORTE,
            clasificarCondicion(senal(FaseBase.ERROR)),
        )
    }

    @Test
    fun `audio y video activos con primer frame publican READY_AUDIO_VIDEO`() {
        assertEquals(
            CondicionReproduccion.READY_AUDIO_VIDEO,
            clasificarCondicion(
                senal(
                    FaseBase.REPRODUCIENDO,
                    audioActivo = true,
                    videoActivo = true,
                    primerFrameVideoVisto = true,
                ),
            ),
        )
    }

    @Test
    fun `audio y video activos sin primer frame publican READY_SIN_PRIMER_FRAME`() {
        assertEquals(
            CondicionReproduccion.READY_SIN_PRIMER_FRAME,
            clasificarCondicion(
                senal(
                    FaseBase.REPRODUCIENDO,
                    audioActivo = true,
                    videoActivo = true,
                    primerFrameVideoVisto = false,
                ),
            ),
        )
    }

    @Test
    fun `audio activo sin video y sin primer frame publica READY_SOLO_AUDIO`() {
        assertEquals(
            CondicionReproduccion.READY_SOLO_AUDIO,
            clasificarCondicion(
                senal(FaseBase.REPRODUCIENDO, audioActivo = true, videoActivo = false),
            ),
        )
    }

    @Test
    fun `audio activo con video perdido tras el primer frame publica PERDIDA_SUPERFICIE`() {
        assertEquals(
            CondicionReproduccion.PERDIDA_SUPERFICIE,
            clasificarCondicion(
                senal(
                    FaseBase.REPRODUCIENDO,
                    audioActivo = true,
                    videoActivo = false,
                    primerFrameVideoVisto = true,
                ),
            ),
        )
    }

    @Test
    fun `sin renderizadores activos publica READY_SIN_PISTAS`() {
        assertEquals(
            CondicionReproduccion.READY_SIN_PISTAS,
            clasificarCondicion(senal(FaseBase.REPRODUCIENDO)),
        )
        assertEquals(
            CondicionReproduccion.READY_SIN_PISTAS,
            clasificarCondicion(
                senal(FaseBase.REPRODUCIENDO, videoActivo = true, primerFrameVideoVisto = true),
            ),
        )
    }
}
