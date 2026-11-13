package com.copernicotv.app.player

import android.net.Uri
import androidx.media3.common.PlaybackException
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import com.copernicotv.core.player.ProtocoloStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.IOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException

/**
 * La captura de detalle de fallo (E7-T7, Objetivo B) debe dar evidencia técnica
 * copiable —código HTTP, MIME, excepción original, clasificación— sin exponer
 * nunca credenciales ni la URL completa (S57a).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class DetalleFalloTest {
    private fun dataSpec(): DataSpec = DataSpec(Uri.parse("http://127.0.0.1:8080/f19/1.ts"))

    private fun falloHttp(codigo: Int): PlaybackException =
        PlaybackException(
            null,
            HttpDataSource.InvalidResponseCodeException(codigo, null, null, mutableMapOf(), dataSpec(), ByteArray(0)),
            PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
        )

    @Test
    fun `http 401 y 403 se clasifican como credenciales rechazadas`() {
        for (codigo in listOf(401, 403)) {
            val detalle = capturarDetalleFallo(falloHttp(codigo))
            assertEquals(codigo, detalle.estadoHttp)
            assertEquals("credenciales_rechazadas", clasificacionTecnica(detalle))
            assertEquals("InvalidResponseCodeException", detalle.claseExcepcion)
        }
    }

    @Test
    fun `http 404 se clasifica como recurso no encontrado`() {
        assertEquals("recurso_no_encontrado", clasificacionTecnica(capturarDetalleFallo(falloHttp(404))))
    }

    @Test
    fun `http 429 se clasifica como limite de solicitudes`() {
        assertEquals("limite_solicitudes", clasificacionTecnica(capturarDetalleFallo(falloHttp(429))))
    }

    @Test
    fun `http 5xx se clasifica como fallo de servidor`() {
        assertEquals("fallo_servidor", clasificacionTecnica(capturarDetalleFallo(falloHttp(503))))
    }

    @Test
    fun `el mime de la respuesta y el tamano del cuerpo se capturan`() {
        val error =
            PlaybackException(
                null,
                HttpDataSource.InvalidResponseCodeException(
                    415,
                    null,
                    null,
                    mapOf("Content-Type" to listOf("video/mp4")),
                    dataSpec(),
                    ByteArray(123),
                ),
                PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS,
            )
        val detalle = capturarDetalleFallo(error)

        assertEquals(415, detalle.estadoHttp)
        assertEquals("video/mp4", detalle.tipoMime)
        assertEquals(123L, detalle.bytesRespuesta)
        assertEquals("http_cliente", clasificacionTecnica(detalle))
    }

    @Test
    fun `mime incompatible se clasifica por content type`() {
        val error =
            PlaybackException(
                null,
                HttpDataSource.InvalidContentTypeException("text/html", dataSpec()),
                PlaybackException.ERROR_CODE_IO_UNSPECIFIED,
            )
        val detalle = capturarDetalleFallo(error)

        assertEquals("text/html", detalle.tipoMime)
        assertEquals("formato_incompatible", clasificacionTecnica(detalle))
    }

    @Test
    fun `dns conexion y tiempo agotado se clasifican por la clase de la causa`() {
        val dns = PlaybackException(null, UnknownHostException("sin-resolver"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals("dns", clasificacionTecnica(capturarDetalleFallo(dns)))
        assertEquals("UnknownHostException", capturarDetalleFallo(dns).claseExcepcion)

        val conexion = PlaybackException(null, ConnectException("sin-conexion"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED)
        assertEquals("conexion", clasificacionTecnica(capturarDetalleFallo(conexion)))

        val tiempo = PlaybackException(null, SocketTimeoutException("agotado"), PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT)
        assertEquals("tiempo_agotado", clasificacionTecnica(capturarDetalleFallo(tiempo)))
    }

    @Test
    fun `sin evidencia especifica queda otro`() {
        val error = PlaybackException(null, IOException("inespecifico"), PlaybackException.ERROR_CODE_IO_UNSPECIFIED)
        val detalle = capturarDetalleFallo(error)

        assertEquals(null as Int?, detalle.estadoHttp)
        assertEquals("otro", clasificacionTecnica(detalle))
    }

    @Test
    fun `codigo motor conserva el nombre estable de media3`() {
        assertEquals(
            PlaybackException.getErrorCodeName(PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS),
            capturarDetalleFallo(falloHttp(401)).codigoMotor,
        )
    }

    @Test
    fun `el texto copiado expone el detalle sin credenciales ni url completa`() {
        val diagnostico =
            construirDiagnostico(
                protocolo = ProtocoloStream.PROGRESIVO,
                url = "http://usuario:clave@127.0.0.1:8080/f19/1.ts?token=secreto",
                estado =
                    EstadoSesion(
                        estado = EstadoReproductor.ERROR,
                        codigoError = "http_error",
                        codigoMotor = "ERROR_CODE_IO_BAD_HTTP_STATUS",
                        clasesCausa = listOf("ExoPlaybackException", "InvalidResponseCodeException"),
                        estadoHttp = 401,
                        tipoMime = "video/mp4",
                        bytesRespuesta = 45L,
                        msHastaFallo = 1_500L,
                        intentosFallo = 1,
                        clasificacionTecnica = "credenciales_rechazadas",
                    ),
                pistas = EstadoPistas(),
                canalId = 42L,
            )

        val texto = textoDiagnostico(diagnostico)

        assertTrue(texto.contains("Clasificación: credenciales_rechazadas"))
        assertTrue(texto.contains("Identificador de canal: 42"))
        assertTrue(texto.contains("Código Media3: ERROR_CODE_IO_BAD_HTTP_STATUS"))
        assertTrue(texto.contains("Código HTTP: 401"))
        assertTrue(texto.contains("MIME: video/mp4"))
        assertTrue(texto.contains("Tamaño de respuesta: 45 bytes"))
        assertTrue(texto.contains("Excepción: InvalidResponseCodeException"))
        assertTrue(texto.contains("Intentos: 1"))
        assertTrue(texto.contains("Duración hasta el fallo: 00:01"))
        assertFalse(texto.contains("usuario"))
        assertFalse(texto.contains("clave"))
        assertFalse(texto.contains("token"))
        assertFalse(texto.contains("/f19/1.ts"))
        assertFalse(texto.contains("secreto"))
    }

    @Test
    fun `sin detalle el texto no inventa datos`() {
        val diagnostico =
            construirDiagnostico(
                protocolo = ProtocoloStream.PROGRESIVO,
                url = "http://127.0.0.1:8080/x.mp4",
                estado = EstadoSesion(estado = EstadoReproductor.ERROR, codigoError = "error_reproduccion"),
                pistas = EstadoPistas(),
            )

        val texto = textoDiagnostico(diagnostico)

        assertTrue(texto.contains("Clasificación: no disponible"))
        assertTrue(texto.contains("Código HTTP: no disponible"))
        assertTrue(texto.contains("MIME: no disponible"))
        assertTrue(texto.contains("Tamaño de respuesta: no disponible"))
        assertTrue(texto.contains("Excepción: no disponible"))
        assertTrue(texto.contains("Duración hasta el fallo: no disponible"))
    }
}
