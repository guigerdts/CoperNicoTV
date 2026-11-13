package com.copernicotv.app.data

import com.copernicotv.core.identity.AvanceEtapaImportacion
import com.copernicotv.core.identity.EtapaImportacion
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Vigilancia de avance de importación (E7-T7, S22): ventana de silencio y
 * foto/texto acotados, con reloj inyectado para que el test sea determinista.
 */
class AvanceImportacionTest {
    @Test
    fun `sin primer avance no detiene nunca`() {
        var ahora = 0L
        val avance = AvanceImportacion(umbralSinAvanceMs = 100, reloj = { ahora })

        ahora = 60_000L
        avance.verificar()

        // Ninguna excepción: antes del primer tick no hay ventana de silencio
        // que vigilar (el arranque lento lo cubre el timeout de lectura).
    }

    @Test
    fun `pasado el umbral sin avance lanza el error tipado con el silencio observado`() {
        var ahora = 0L
        val avance = AvanceImportacion(umbralSinAvanceMs = 100, reloj = { ahora })
        avance.ticEntrada(1)

        ahora = 301L
        val detenido = runCatching { avance.verificar() }.exceptionOrNull()

        assertTrue("esperaba ImportacionAvanceDetenido", detenido is ImportacionAvanceDetenido)
        assertEquals(301L, (detenido as ImportacionAvanceDetenido).msSinAvance)
    }

    @Test
    fun `un nuevo avance reinicia la ventana de silencio`() {
        var ahora = 0L
        val avance = AvanceImportacion(umbralSinAvanceMs = 100, reloj = { ahora })
        avance.ticEntrada(1)
        ahora = 150L
        avance.ticCanal(1)
        ahora = 200L

        avance.verificar()

        // Sin excepción: el tick de canal reinició el silencio.
    }

    @Test
    fun `la foto y el texto reportan contadores sin datos de la lista`() {
        var ahora = 0L
        val avance = AvanceImportacion(reloj = { ahora })
        avance.ticEntrada(12)
        avance.ticCanal(7)
        avance.ticPreparacion(5)
        avance.bytesProveedor = { 4096L }
        ahora = 2_000L

        val foto = avance.foto()

        assertEquals(12, foto.entradas)
        assertEquals(7, foto.canales)
        assertEquals(5, foto.preparando)
        assertEquals(4096L, foto.bytes)
        assertEquals(2_000L, foto.msTranscurrido)
        val texto = avance.texto()
        assertTrue(texto.contains("entradas=12"))
        assertTrue(texto.contains("canales=7"))
        assertTrue(texto.contains("preparando=5"))
        assertTrue(texto.contains("bytes=4096"))
        assertTrue(texto.contains("t=2000ms"))
    }

    /** E7-T7 ronda 3: el tick de agrupación reinicia la ventana de silencio. */
    @Test
    fun `el tick de agrupacion reinicia la ventana de silencio`() {
        var ahora = 0L
        val avance = AvanceImportacion(umbralSinAvanceMs = 100, reloj = { ahora })
        avance.ticEntrada(146_729)
        ahora = 150L
        avance.ticAgrupacion(1_000)
        ahora = 200L

        avance.verificar()

        // Sin excepción: durante la agrupación (etapa antes muda, L13) el
        // vigilante ya no dispara `avance_detenido`.
    }

    /** E7-T7 ronda 4 (S34): los avances por etapa alimentan al vigilante. */
    @Test
    fun `ticEtapa reinicia la ventana de silencio y queda como ultima etapa`() {
        var ahora = 0L
        val avance = AvanceImportacion(umbralSinAvanceMs = 100, reloj = { ahora })
        avance.ticEntrada(146_747)
        ahora = 150L
        val evento =
            AvanceEtapaImportacion(
                etapa = EtapaImportacion.DERIVACION_CLAVES,
                elementos = 73_000,
                msEtapa = 40_000,
                msDesdeUltimoAvance = 700,
                opsCriptograficas = 0,
                canalesValidos = 0,
            )
        avance.ticEtapa(evento)
        ahora = 200L

        avance.verificar()
        assertEquals(evento, avance.ultimaEtapa())

        // Sin excepción: el avance de etapa reinicia el silencio durante el
        // tramo post-canonización (segunda ventana muda, L17).
    }

    @Test
    fun `el texto incluye los campos de la ultima etapa`() {
        var ahora = 0L
        val avance = AvanceImportacion(reloj = { ahora })
        avance.ticEtapa(
            AvanceEtapaImportacion(
                etapa = EtapaImportacion.SOBRES_HMAC,
                elementos = 1_000,
                msEtapa = 2_500,
                msDesdeUltimoAvance = 20,
                opsCriptograficas = 1_262,
                canalesValidos = 1_000,
            ),
        )
        ahora = 3_000L

        val texto = avance.texto()

        assertTrue(texto.contains("etapa=sobres_hmac"))
        assertTrue(texto.contains("etapa_elementos=1000"))
        assertTrue(texto.contains("etapa_ms=2500"))
        assertTrue(texto.contains("etapa_gap_ms=20"))
        assertTrue(texto.contains("etapa_ops=1262"))
        assertTrue(texto.contains("etapa_canales_validos=1000"))
    }

    @Test
    fun `sin etapas el texto no incluye campos de etapa`() {
        var ahora = 0L
        val avance = AvanceImportacion(reloj = { ahora })
        avance.ticEntrada(3)
        ahora = 10L

        val texto = avance.texto()

        assertTrue(texto.contains("entradas=3"))
        assertTrue(!texto.contains("etapa="))
        assertNull(avance.ultimaEtapa())
    }

    /** E7-T7 ronda 3: el contador de agrupadas queda visible en foto y texto. */
    @Test
    fun `la foto y el texto reportan las declaraciones agrupadas`() {
        var ahora = 0L
        val avance = AvanceImportacion(reloj = { ahora })
        avance.ticEntrada(5)
        avance.ticAgrupacion(5)
        ahora = 1_500L

        val foto = avance.foto()
        val texto = avance.texto()

        assertEquals(5, foto.agrupadas)
        assertTrue(texto.contains("agrupadas=5"))
        assertTrue(texto.contains("entradas=5"))
    }
}
