package com.copernicotv.app.player

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * H-01: el Historial se escribe una sola vez por sesión, en el primer
 * REPRODUCIENDO real.
 *
 * La regla vivía implícita en la pantalla y por eso nunca se cumplía: el
 * repositorio de Historial existía pero nadie lo llamaba. Estas pruebas fijan
 * el contrato que el ViewModel aplica desde su recolector de estado.
 */
class RegistroHistorialTest {
    @Test
    fun `no registra mientras el canal solo carga o bufferea`() {
        val registro = RegistroHistorial()

        assertFalse(registro.alReproducir(EstadoReproductor.CARGANDO))
        assertFalse(registro.alReproducir(EstadoReproductor.BUFFERING))
        assertFalse(registro.alReproducir(EstadoReproductor.REINTENTANDO))
        assertFalse(registro.alReproducir(EstadoReproductor.ESPERANDO_CONEXION))
        assertFalse(registro.alReproducir(EstadoReproductor.ERROR))
    }

    @Test
    fun `registra en el primer reproduciendo real`() {
        val registro = RegistroHistorial()

        assertFalse(registro.alReproducir(EstadoReproductor.CARGANDO))
        assertTrue(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
    }

    @Test
    fun `no duplica si el estado se vuelve a publicar`() {
        val registro = RegistroHistorial()

        assertTrue(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
        assertFalse(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
        assertFalse(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
    }

    @Test
    fun `pausar y reanudar no crea otra entrada`() {
        val registro = RegistroHistorial()

        assertTrue(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
        assertFalse(registro.alReproducir(EstadoReproductor.PAUSADO))
        assertFalse(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
    }

    @Test
    fun `un canal que nunca reproduce no gana entrada`() {
        val registro = RegistroHistorial()

        assertFalse(registro.alReproducir(EstadoReproductor.CARGANDO))
        assertFalse(registro.alReproducir(EstadoReproductor.ERROR))
    }

    @Test
    fun `una sesion reconstruida vuelve a ganarse la entrada`() {
        val registro = RegistroHistorial()

        assertTrue(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
        assertFalse(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))

        registro.reiniciar()

        assertFalse(registro.alReproducir(EstadoReproductor.BUFFERING))
        assertTrue(registro.alReproducir(EstadoReproductor.REPRODUCIENDO))
    }

    @Test
    fun `el zapeo a otro canal registra su propia entrada`() {
        val primera = RegistroHistorial()
        val segunda = RegistroHistorial()

        assertTrue(primera.alReproducir(EstadoReproductor.REPRODUCIENDO))
        assertTrue(segunda.alReproducir(EstadoReproductor.REPRODUCIENDO))
    }
}
