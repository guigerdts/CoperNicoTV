package com.copernicotv.app.player

import androidx.media3.common.MimeTypes
import androidx.media3.common.PlaybackException
import com.copernicotv.core.player.DetectarContenedor
import com.copernicotv.core.player.ProtocoloStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Decisiones puras de la re-selección acotada de MediaSource (E7-T9, S4/S7).
 *
 * La candidata se limita a la ruta progresiva ante los dos códigos de la
 * evidencia S1, una sola vez por sesión; el MIME del `MediaItem` manda con la
 * observación real y el MIME de Media3 coincide con las constantes de `:core`.
 */
class DecisionesReSeleccionTest {
    @Test
    fun `la ruta progresiva con parseo no reconocido es candidata`() {
        assertTrue(
            candidatoReSeleccion(ProtocoloStream.PROGRESIVO, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, false),
        )
        assertTrue(
            candidatoReSeleccion(ProtocoloStream.PROGRESIVO, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, false),
        )
    }

    @Test
    fun `otras rutas y otros fallos no son candidatos`() {
        assertFalse(
            candidatoReSeleccion(ProtocoloStream.HLS, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, false),
        )
        assertFalse(
            candidatoReSeleccion(ProtocoloStream.RTSP, PlaybackException.ERROR_CODE_IO_UNSPECIFIED, false),
        )
        assertFalse(
            candidatoReSeleccion(ProtocoloStream.PROGRESIVO, PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS, false),
        )
        assertFalse(
            candidatoReSeleccion(ProtocoloStream.PROGRESIVO, PlaybackException.ERROR_CODE_TIMEOUT, false),
        )
    }

    @Test
    fun `la re-seleccion es una sola por sesion`() {
        assertFalse(
            candidatoReSeleccion(ProtocoloStream.PROGRESIVO, PlaybackException.ERROR_CODE_PARSING_CONTAINER_UNSUPPORTED, true),
        )
    }

    @Test
    fun `el MIME observado manda sobre la clasificacion del protocolo`() {
        assertEquals("application/vnd.apple.mpegurl", mimeDeMediaItem(ProtocoloStream.PROGRESIVO, "application/vnd.apple.mpegurl"))
        assertNull(mimeDeMediaItem(ProtocoloStream.PROGRESIVO, null))
        assertEquals(MimeTypes.APPLICATION_M3U8, mimeDeMediaItem(ProtocoloStream.HLS, null))
        assertEquals(MimeTypes.APPLICATION_MPD, mimeDeMediaItem(ProtocoloStream.DASH, null))
    }

    @Test
    fun `las constantes MIME de Media3 coinciden con los literales de core`() {
        assertEquals(MimeTypes.APPLICATION_M3U8, DetectarContenedor.MIME_HLS)
        assertEquals(MimeTypes.APPLICATION_MPD, DetectarContenedor.MIME_DASH)
    }
}
