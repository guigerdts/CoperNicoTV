package com.copernicotv.app.player

import androidx.media3.common.PlaybackException
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * E6-T4: política de recuperación de S20.
 *
 * Las pruebas no esperan: [MaquinaReintentos] es pura y las esperas se leen de
 * la política. La clasificación usa los códigos de Media3 sin instanciar el
 * motor.
 */
class PoliticaReintentosTest {
    @Test
    fun `el primer reintento espera un segundo`() {
        val maquina = MaquinaReintentos()

        assertEquals(DecisionReintento.Reintentar(1, 1_000L), maquina.alFallar(ClaseFallo.TRANSITORIO))
    }

    @Test
    fun `el segundo reintento espera dos segundos`() {
        val maquina = MaquinaReintentos()
        maquina.alFallar(ClaseFallo.TRANSITORIO)
        maquina.alEjecutar()

        assertEquals(DecisionReintento.Reintentar(2, 2_000L), maquina.alFallar(ClaseFallo.TRANSITORIO))
    }

    @Test
    fun `el tercer reintento espera cuatro segundos`() {
        val maquina = MaquinaReintentos()
        repeat(2) {
            maquina.alFallar(ClaseFallo.TRANSITORIO)
            maquina.alEjecutar()
        }

        assertEquals(DecisionReintento.Reintentar(3, 4_000L), maquina.alFallar(ClaseFallo.TRANSITORIO))
    }

    @Test
    fun `tras el tercer reintento el siguiente fallo es definitivo`() {
        val maquina = MaquinaReintentos()
        repeat(3) {
            maquina.alFallar(ClaseFallo.TRANSITORIO)
            maquina.alEjecutar()
        }

        assertEquals(3, maquina.realizados)
        assertEquals(DecisionReintento.Definitivo, maquina.alFallar(ClaseFallo.TRANSITORIO))
    }

    @Test
    fun `un fallo con HTTP 4xx no se reintenta`() {
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 429))
        assertEquals(DecisionReintento.Definitivo, MaquinaReintentos().alFallar(ClaseFallo.DEFINITIVO))
    }

    @Test
    fun `los errores transitorios de red y servidor se reintentan`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
            PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
            PlaybackException.ERROR_CODE_TIMEOUT,
        ).forEach { codigo ->
            assertEquals("El código $codigo debe ser transitorio", ClaseFallo.TRANSITORIO, clasificarFallo(codigo, null))
        }
        assertEquals(ClaseFallo.TRANSITORIO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 500))
        assertEquals(ClaseFallo.TRANSITORIO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 503))
    }

    @Test
    fun `los errores sin evidencia de transitoriedad son definitivos`() {
        listOf(
            PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            PlaybackException.ERROR_CODE_IO_INVALID_HTTP_CONTENT_TYPE,
            PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND,
            PlaybackException.ERROR_CODE_IO_NO_PERMISSION,
            PlaybackException.ERROR_CODE_IO_CLEARTEXT_NOT_PERMITTED,
            PlaybackException.ERROR_CODE_IO_READ_POSITION_OUT_OF_RANGE,
            PlaybackException.ERROR_CODE_UNSPECIFIED,
        ).forEach { codigo ->
            assertEquals("El código $codigo debe ser definitivo", ClaseFallo.DEFINITIVO, clasificarFallo(codigo, null))
        }
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null))
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 302))
    }

    @Test
    fun `un fallo duplicado no programa otro reintento`() {
        val maquina = MaquinaReintentos()
        maquina.alFallar(ClaseFallo.TRANSITORIO)

        assertEquals(DecisionReintento.Ignorar, maquina.alFallar(ClaseFallo.TRANSITORIO))
        assertEquals(0, maquina.realizados)

        maquina.alEjecutar()
        assertEquals(DecisionReintento.Reintentar(2, 2_000L), maquina.alFallar(ClaseFallo.TRANSITORIO))
    }

    @Test
    fun `un fallo definitivo durante la espera descarta el reintento pendiente`() {
        val maquina = MaquinaReintentos()
        maquina.alFallar(ClaseFallo.TRANSITORIO)

        assertEquals(DecisionReintento.Definitivo, maquina.alFallar(ClaseFallo.DEFINITIVO))
        assertEquals(0, maquina.realizados)
    }

    @Test
    fun `la politica publica las tres esperas de S20`() {
        val politica = PoliticaReintentos()

        assertEquals(3, politica.maximo)
        assertEquals(listOf(1_000L, 2_000L, 4_000L), politica.esperasMs)
    }

    @Test
    fun `el reintento se diagnostica como sesion inactiva y no se autooculta`() {
        assertEquals("IDLE", nombreEstadoMotor(EstadoReproductor.REINTENTANDO))
        assertFalse(debeOcultarse(EstadoReproductor.REINTENTANDO))
    }

    @Test
    fun `el codigo de error tipa los HTTP y los timeouts sin exponer la URL`() {
        assertEquals(
            "http_cliente",
            codigoDeError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404),
        )
        assertEquals(
            "http_servidor",
            codigoDeError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 500),
        )
        assertEquals(
            "http_error",
            codigoDeError(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, null),
        )
        assertEquals("tiempo_agotado", codigoDeError(PlaybackException.ERROR_CODE_TIMEOUT, null))
        assertEquals(
            "sin_conexion",
            codigoDeError(PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED, null),
        )
        assertEquals(
            "fuente_no_encontrada",
            codigoDeError(PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND, null),
        )
    }
}
