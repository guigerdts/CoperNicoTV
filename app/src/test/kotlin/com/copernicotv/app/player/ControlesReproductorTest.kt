package com.copernicotv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reglas puras de los controles de E6-T2, sin Android ni Media3.
 *
 * Formateo de tiempos, límites de búsqueda, ciclo del ajuste de pantalla y
 * cuándo puede auto-ocultarse la barra: la interfaz solo aplica estas reglas.
 */
class ControlesReproductorTest {
    @Test
    fun elTiempoDesconocidoSeMuestraComoGuiones() {
        assertEquals("--:--", formatearTiempo(-1L))
        assertEquals("--:--", formatearTiempo(Long.MIN_VALUE))
    }

    @Test
    fun elTiempoSeFormateaEnMinutosYSegundos() {
        assertEquals("00:00", formatearTiempo(0L))
        assertEquals("00:00", formatearTiempo(999L))
        assertEquals("00:01", formatearTiempo(1_000L))
        assertEquals("01:01", formatearTiempo(61_000L))
        assertEquals("20:00", formatearTiempo(1_200_000L))
    }

    @Test
    fun elTiempoLargoIncluyeHoras() {
        assertEquals("1:01:01", formatearTiempo(3_661_000L))
    }

    @Test
    fun laBusquedaSeLimitaAlContenidoConocido() {
        assertEquals(0L, limitarBusqueda(-500L, 20_000L))
        assertEquals(5_000L, limitarBusqueda(5_000L, 20_000L))
        assertEquals(20_000L, limitarBusqueda(25_000L, 20_000L))
    }

    @Test
    fun sinDuracionConocidaLaBusquedaNoInventaPosiciones() {
        assertEquals(0L, limitarBusqueda(5_000L, 0L))
        assertEquals(0L, limitarBusqueda(5_000L, -1L))
    }

    @Test
    fun elAjusteDePantallaCiclaSinSalirDelDominio() {
        assertEquals(ModoAspecto.RELLENAR, ModoAspecto.AJUSTAR.siguiente())
        assertEquals(ModoAspecto.ZOOM, ModoAspecto.RELLENAR.siguiente())
        assertEquals(ModoAspecto.AJUSTAR, ModoAspecto.ZOOM.siguiente())
    }

    @Test
    fun soloSeOcultanLosControlesMientrasElContenidoAvanza() {
        assertTrue(debeOcultarse(EstadoReproductor.REPRODUCIENDO))
        assertFalse(debeOcultarse(EstadoReproductor.PAUSADO))
        assertFalse(debeOcultarse(EstadoReproductor.BUFFERING))
        assertFalse(debeOcultarse(EstadoReproductor.CARGANDO))
        assertFalse(debeOcultarse(EstadoReproductor.TERMINADO))
        assertFalse(debeOcultarse(EstadoReproductor.ERROR))
    }

    @Test
    fun elDiagnosticoConservaLaTerminologiaDeE6T1() {
        assertEquals("READY", nombreEstadoMotor(EstadoReproductor.REPRODUCIENDO))
        assertEquals("READY", nombreEstadoMotor(EstadoReproductor.PAUSADO))
        assertEquals("BUFFERING", nombreEstadoMotor(EstadoReproductor.BUFFERING))
        assertEquals("ENDED", nombreEstadoMotor(EstadoReproductor.TERMINADO))
        assertEquals("IDLE", nombreEstadoMotor(EstadoReproductor.CARGANDO))
    }
}
