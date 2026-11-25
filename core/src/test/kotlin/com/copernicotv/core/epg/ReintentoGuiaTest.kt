package com.copernicotv.core.epg

import java.time.Duration
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReintentoGuiaTest {
    private val ahora: Instant = Instant.parse("2026-11-25T12:00:00Z")
    private val politica = PoliticaReintentoGuia.PREDETERMINADA

    @Test
    fun `sin fallos previos se reintenta siempre`() {
        assertTrue(politica.puedeReintentar(0, null, ahora))
    }

    @Test
    fun `un fallo sin fecha registrada no bloquea el reintento`() {
        assertTrue(politica.puedeReintentar(1, null, ahora))
    }

    @Test
    fun `el primer reintento espera quince minutos`() {
        val ultimo = ahora.minus(Duration.ofMinutes(14))
        assertFalse(politica.puedeReintentar(1, ultimo, ahora))
        assertTrue(politica.puedeReintentar(1, ahora.minus(Duration.ofMinutes(15)), ahora))
    }

    @Test
    fun `las esperas crecen con cada fallo`() {
        assertFalse(politica.puedeReintentar(2, ahora.minus(Duration.ofMinutes(59)), ahora))
        assertTrue(politica.puedeReintentar(2, ahora.minus(Duration.ofHours(1)), ahora))
        assertFalse(politica.puedeReintentar(3, ahora.minus(Duration.ofHours(5)), ahora))
        assertTrue(politica.puedeReintentar(3, ahora.minus(Duration.ofHours(6)), ahora))
    }

    @Test
    fun `agotados los intentos automaticos el reintento es manual`() {
        assertFalse(politica.puedeReintentar(4, ahora.minus(Duration.ofDays(30)), ahora))
        assertFalse(politica.puedeReintentar(9, null, ahora))
    }

    @Test
    fun `la espera publicada coincide con la consumida`() {
        assertEquals(Duration.ofMinutes(15), politica.esperaTras(0))
        assertEquals(Duration.ofHours(24), politica.esperaTras(3))
        assertNull(politica.esperaTras(4))
    }

    @Test
    fun `una politica propia respeta su tabla de esperas`() {
        val corta = PoliticaReintentoGuia(listOf(1L, 2L))
        assertEquals(2, corta.maxIntentos)
        assertTrue(corta.puedeReintentar(1, ahora.minus(Duration.ofMinutes(1)), ahora))
        assertFalse(corta.puedeReintentar(2, ahora, ahora))
    }
}
