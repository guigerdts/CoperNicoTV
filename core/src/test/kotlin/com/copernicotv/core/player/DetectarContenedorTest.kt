package com.copernicotv.core.player

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

/**
 * Matriz de detección de contenedor por evidencia combinada (E7-T9, S3).
 *
 * Los bytes iniciales son la evidencia primaria: un servidor puede declarar
 * cualquier MIME (u omitirlo) y la extensión de la URL no garantiza el
 * contenido. El MIME solo decide cuando los bytes no alcanzan para
 * clasificar. Una respuesta HTML nunca se confunde con un stream (S3).
 */
class DetectarContenedorTest {
    private fun texto(contenido: String): ByteArray = contenido.toByteArray(Charsets.UTF_8)

    @Test
    fun `una lista HLS se reconoce por su marca inicial aunque el MIME diga otra cosa`() {
        val bytes = texto("#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=1280000\nmaster.m3u8")
        assertEquals(FormatoObservado.HLS, DetectarContenedor.observar("application/octet-stream", bytes))
    }

    @Test
    fun `una lista HLS con BOM o espacios previos se reconoce`() {
        val bytes = texto("\uFEFF  #EXTM3U\n")
        assertEquals(FormatoObservado.HLS, DetectarContenedor.observar(null, bytes))
    }

    @Test
    fun `un manifiesto MPD se reconoce como DASH`() {
        assertEquals(
            FormatoObservado.DASH,
            DetectarContenedor.observar("application/octet-stream", texto("<MPD xmlns=\"urn:mpeg:dash:schema:mpd:2011\"><Period/>")),
        )
        assertEquals(
            FormatoObservado.DASH,
            DetectarContenedor.observar(null, texto("<?xml version=\"1.0\"?>\n<MPD profiles=\"urn:mpeg:dash:profile:isoff-live:2011\">")),
        )
    }

    @Test
    fun `una respuesta HTML se clasifica como HTML y no como stream`() {
        assertEquals(
            FormatoObservado.HTML,
            DetectarContenedor.observar("text/html; charset=utf-8", texto("<!DOCTYPE html><html><body>403</body></html>")),
        )
        assertEquals(FormatoObservado.HTML, DetectarContenedor.observar(null, texto("<html><head>")))
    }

    @Test
    fun `un paquete MPEG-TS se reconoce por el byte de sincronia y su periodicidad`() {
        val bytes = ByteArray(400) { 0x00 }
        bytes[0] = 0x47
        bytes[188] = 0x47
        bytes[376] = 0x47
        assertEquals(FormatoObservado.TS, DetectarContenedor.observar("application/octet-stream", bytes))
    }

    @Test
    fun `un unico 0x47 sin periodicidad no es evidencia suficiente de TS`() {
        val bytes = ByteArray(400) { 0x33 }
        bytes[0] = 0x47
        assertEquals(FormatoObservado.DESCONOCIDO, DetectarContenedor.observar("application/octet-stream", bytes))
    }

    @Test
    fun `un archivo MP4 se reconoce por la marca ftyp`() {
        val bytes = ByteArray(32) { 0x00 }
        bytes[4] = 'f'.code.toByte()
        bytes[5] = 't'.code.toByte()
        bytes[6] = 'y'.code.toByte()
        bytes[7] = 'p'.code.toByte()
        assertEquals(FormatoObservado.MP4, DetectarContenedor.observar(null, bytes))
    }

    @Test
    fun `un stream MP3 se reconoce por ID3 o por el patron de sincronia`() {
        assertEquals(FormatoObservado.MP3, DetectarContenedor.observar(null, texto("ID3\u0004\u0000\u0000\u0000\u0000\u0000\u0000")))
        val adtsMp3 = byteArrayOf(0xFF.toByte(), 0xFB.toByte(), 0x90.toByte())
        assertEquals(FormatoObservado.MP3, DetectarContenedor.observar(null, adtsMp3))
    }

    @Test
    fun `un stream AAC ADTS se distingue de MP3 por la capa reservada`() {
        val adtsAac = byteArrayOf(0xFF.toByte(), 0xF1.toByte(), 0x50.toByte())
        assertEquals(FormatoObservado.AAC, DetectarContenedor.observar(null, adtsAac))
    }

    @Test
    fun `cuando los bytes no alcanzan el MIME decide la clasificacion`() {
        assertEquals(FormatoObservado.HLS, DetectarContenedor.observar("application/vnd.apple.mpegurl", ByteArray(3)))
        assertEquals(FormatoObservado.DASH, DetectarContenedor.observar("application/dash+xml", ByteArray(2)))
        assertEquals(FormatoObservado.TS, DetectarContenedor.observar("video/mp2t", ByteArray(1)))
        assertEquals(FormatoObservado.MP4, DetectarContenedor.observar("video/mp4", ByteArray(1)))
        assertEquals(FormatoObservado.MP3, DetectarContenedor.observar("audio/mpeg", ByteArray(1)))
        assertEquals(FormatoObservado.AAC, DetectarContenedor.observar("audio/aac", ByteArray(1)))
        assertEquals(FormatoObservado.HTML, DetectarContenedor.observar("text/html", ByteArray(1)))
    }

    @Test
    fun `bytes desconocidos sin MIME util quedan como desconocido`() {
        assertEquals(FormatoObservado.DESCONOCIDO, DetectarContenedor.observar(null, texto("XYZXYZXYZ")))
        assertEquals(FormatoObservado.DESCONOCIDO, DetectarContenedor.observar(null, ByteArray(0)))
        assertEquals(FormatoObservado.DESCONOCIDO, DetectarContenedor.observar(null, byteArrayOf(0x1A.toByte(), 0x45)))
    }

    @Test
    fun `los bytes ganan al MIME cuando el servidor miente`() {
        val bytes = texto("#EXTM3U\n")
        assertEquals(FormatoObservado.HLS, DetectarContenedor.observar("text/html", bytes))
    }

    @Test
    fun `solo HLS y DASH son alternativas plausibles de re-seleccion`() {
        assertEquals("application/x-mpegURL", DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.HLS))
        assertEquals("application/dash+xml", DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.DASH))
        assertNull(DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.TS))
        assertNull(DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.MP4))
        assertNull(DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.MP3))
        assertNull(DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.AAC))
        assertNull(DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.HTML))
        assertNull(DetectarContenedor.mimeTypeDeReSeleccion(FormatoObservado.DESCONOCIDO))
    }
}
