package com.copernicotv.app.data

import org.junit.Assert.assertEquals
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
}
