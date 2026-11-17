package com.copernicotv.app.player

import androidx.media3.common.PlaybackException
import com.copernicotv.core.player.ClaseFallo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test

/**
 * Clasificación y tipado de fallos de la política de recuperación (E6-T4,
 * ampliada en E7-T10 S4).
 *
 * La máquina pura de reintentos —backoff, presupuesto y reposición por
 * estabilidad— vive en `:core` y se prueba en `RecuperacionTest`; aquí se
 * cubre lo que queda en `:app`: clasificar el error de Media3, tiparlo sin
 * exponer la URL, y los valores de configuración que la política publica.
 */
class PoliticaReintentosTest {
    @Test
    fun `un fallo con HTTP 4xx no se reintenta`() {
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 404))
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 403))
        assertEquals(ClaseFallo.DEFINITIVO, clasificarFallo(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, 429))
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
    fun `la politica publica la configuracion de S4`() {
        val politica = PoliticaReintentos()

        assertEquals(6, politica.maximo)
        assertEquals(1_000L, politica.configuracion.esperaBaseMs)
        assertEquals(30_000L, politica.configuracion.esperaMaximaMs)
        assertEquals(10_000L, politica.configuracion.periodoEstabilidadMs)
    }

    @Test
    fun `el reintento se diagnostica como sesion inactiva y no se autooculta`() {
        assertEquals("IDLE", nombreEstadoMotor(EstadoReproductor.REINTENTANDO))
        assertEquals("IDLE", nombreEstadoMotor(EstadoReproductor.ESPERANDO_CONEXION))
        assertFalse(debeOcultarse(EstadoReproductor.REINTENTANDO))
        assertFalse(debeOcultarse(EstadoReproductor.ESPERANDO_CONEXION))
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

    @Test
    fun `un error de decodificador no se reintenta (matriz O, E7-T9)`() {
        assertEquals(
            ClaseFallo.DEFINITIVO,
            clasificarFallo(PlaybackException.ERROR_CODE_DECODING_FAILED, null),
        )
        assertEquals(
            ClaseFallo.DEFINITIVO,
            clasificarFallo(PlaybackException.ERROR_CODE_DECODING_FORMAT_EXCEEDS_CAPABILITIES, null),
        )
        // El tipado del motor agrupa lo no enumerado; el código Media3 queda en
        // la evidencia técnica.
        assertEquals("error_reproduccion", codigoDeError(PlaybackException.ERROR_CODE_DECODING_FAILED, null))
    }
}
