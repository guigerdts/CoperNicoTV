package com.copernicotv.core.player

import com.copernicotv.core.identity.ClaseUrl
import kotlin.test.Test
import kotlin.test.assertEquals

/** Clasificación del contenedor declarado por `Canal.url_stream` (§5, S16). */
class ClasificadorStreamTest {
    @Test
    fun `una extension m3u8 se clasifica como HLS`() {
        assertEquals(
            ProtocoloStream.HLS,
            ClasificadorStream.clasificar("https://example.invalid/directo/canal.m3u8", ClaseUrl.HTTP_HTTPS),
        )
    }

    @Test
    fun `una extension mpd se clasifica como DASH`() {
        assertEquals(
            ProtocoloStream.DASH,
            ClasificadorStream.clasificar("https://example.invalid/vod/pelicula.MPD", ClaseUrl.HTTP_HTTPS),
        )
    }

    @Test
    fun `un manifiesto SmoothStreaming se clasifica por su sufijo ism manifest`() {
        assertEquals(
            ProtocoloStream.SMOOTH_STREAMING,
            ClasificadorStream.clasificar("https://example.invalid/ss/canal.ism/Manifest", ClaseUrl.HTTP_HTTPS),
        )
        assertEquals(
            ProtocoloStream.SMOOTH_STREAMING,
            ClasificadorStream.clasificar("https://example.invalid/ss/canal.isml/manifest", ClaseUrl.HTTP_HTTPS),
        )
    }

    @Test
    fun `un MP4 progresivo no se confunde con un manifiesto`() {
        assertEquals(
            ProtocoloStream.PROGRESIVO,
            ClasificadorStream.clasificar("https://example.invalid/vod/pelicula.mp4", ClaseUrl.HTTP_HTTPS),
        )
    }

    @Test
    fun `la query y el fragmento no cambian la clasificacion`() {
        assertEquals(
            ProtocoloStream.HLS,
            ClasificadorStream.clasificar(
                "https://example.invalid/canal.m3u8?token=abc#t=10",
                ClaseUrl.HTTP_HTTPS,
            ),
        )
    }

    @Test
    fun `RTSP y RTMP se clasifican por su clase y no por la extension`() {
        assertEquals(
            ProtocoloStream.RTSP,
            ClasificadorStream.clasificar("rtsp://example.invalid:554/canal.m3u8", ClaseUrl.RTSP),
        )
        assertEquals(
            ProtocoloStream.RTMP,
            ClasificadorStream.clasificar("rtmp://example.invalid/live/canal", ClaseUrl.RTMP),
        )
    }
}
