package com.copernicotv.app.player

import androidx.media3.common.MimeTypes
import com.copernicotv.core.player.ProtocoloStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * E6-T1: el MIME del `MediaItem` sale de la clasificación de `:core`.
 *
 * HLS, DASH y SmoothStreaming declaran su contenedor; protocolo progresivo,
 * RTSP (selección por esquema) y RTMP (rechazado antes de la sesión) quedan
 * sin MIME explícito.
 */
class MimeDeProtocoloTest {
    @Test
    fun hlsDeclaraSuMime() {
        assertEquals(MimeTypes.APPLICATION_M3U8, mimeDeProtocolo(ProtocoloStream.HLS))
    }

    @Test
    fun dashDeclaraSuMime() {
        assertEquals(MimeTypes.APPLICATION_MPD, mimeDeProtocolo(ProtocoloStream.DASH))
    }

    @Test
    fun smoothStreamingDeclaraSuMime() {
        assertEquals(MimeTypes.APPLICATION_SS, mimeDeProtocolo(ProtocoloStream.SMOOTH_STREAMING))
    }

    @Test
    fun progresivoRtspYRtmpNoDeclaranMime() {
        assertNull(mimeDeProtocolo(ProtocoloStream.PROGRESIVO))
        assertNull(mimeDeProtocolo(ProtocoloStream.RTSP))
        assertNull(mimeDeProtocolo(ProtocoloStream.RTMP))
    }
}
