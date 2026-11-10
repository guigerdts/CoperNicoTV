package com.copernicotv.app.player

import com.copernicotv.core.player.ProtocoloStream
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El diagnóstico de E6-T5 debe bastar para explicar una reproducción sin
 * exponer credenciales (S22, S57a).
 */
class DiagnosticoReproductorTest {
    @Test
    fun `la url saneada conserva solo esquema host y puerto`() {
        assertEquals(
            "https://example.com:8443",
            sanearUrl("https://usuario:clave@example.com:8443/ruta/canal.m3u8?token=secreto#fragmento"),
        )
    }

    @Test
    fun `la url saneada no filtra userinfo ruta query ni fragmento`() {
        val texto =
            sanearUrl("http://usuario:clave@127.0.0.1:8080/f19/f19_pistas.mp4?token=secreto#frag")

        assertEquals("http://127.0.0.1:8080", texto)
        assertFalse(texto.contains("usuario"))
        assertFalse(texto.contains("clave"))
        assertFalse(texto.contains("f19_pistas"))
        assertFalse(texto.contains("token"))
        assertFalse(texto.contains("secreto"))
        assertFalse(texto.contains("#"))
        assertFalse(texto.contains("?"))
    }

    @Test
    fun `una url ausente invalida o de esquema desconocido se oculta`() {
        assertEquals(URL_OCULTA, sanearUrl(null))
        assertEquals(URL_OCULTA, sanearUrl("   "))
        assertEquals(URL_OCULTA, sanearUrl("sin-esquema/ruta"))
        assertEquals(URL_OCULTA, sanearUrl("content://com.example/documento"))
        assertEquals(URL_OCULTA, sanearUrl("http://usuario:clave@/ruta"))
        assertEquals(URL_OCULTA, sanearUrl("http://ejemplo:puerto/ruta"))
    }

    @Test
    fun `la url saneada conserva ipv6 y rtsp`() {
        assertEquals(
            "rtsp://[2001:db8::1]:554",
            sanearUrl("rtsp://usuario:clave@[2001:db8::1]:554/directo"),
        )
    }

    @Test
    fun `el texto incluye protocolo pistas buffer y error`() {
        val diagnostico =
            construirDiagnostico(
                protocolo = ProtocoloStream.HLS,
                url = "http://usuario:clave@127.0.0.1:8080/f19/f19_pistas.mp4?token=secreto",
                estado =
                    EstadoSesion(
                        estado = EstadoReproductor.REPRODUCIENDO,
                        posicionMs = 3_000L,
                        duracionMs = 10_000L,
                        bufferMs = 6_000L,
                    ),
                pistas =
                    EstadoPistas(
                        audio =
                            listOf(
                                pistaAudio("spa", activa = true),
                                pistaAudio("eng", activa = false),
                            ),
                        subtitulos =
                            listOf(
                                pistaSubtitulos("spa", activa = false),
                                pistaSubtitulos("eng", activa = true, forzada = true),
                            ),
                        subtitulosDesactivados = false,
                    ),
            )

        val texto = textoDiagnostico(diagnostico)

        assertTrue(texto.contains("Protocolo: HLS"))
        assertTrue(texto.contains("URL: http://127.0.0.1:8080"))
        assertTrue(texto.contains("Fase: READY"))
        assertTrue(texto.contains("Búfer: 00:06"))
        assertTrue(texto.contains("Audio disponible: Español, Inglés"))
        assertTrue(texto.contains("Audio seleccionado: Español"))
        assertTrue(texto.contains("Subtítulos disponibles: Español, Inglés"))
        assertTrue(texto.contains("Subtítulos seleccionados: Inglés"))
        assertTrue(texto.contains("Último error: ninguno"))
        assertFalse(texto.contains("usuario"))
        assertFalse(texto.contains("clave"))
        assertFalse(texto.contains("token"))
        assertFalse(texto.contains("f19_pistas"))
    }

    @Test
    fun `el texto no inventa datos cuando faltan pistas duracion y error`() {
        val diagnostico =
            construirDiagnostico(
                protocolo = ProtocoloStream.PROGRESIVO,
                url = "http://127.0.0.1:8080/f19/f19_sin_pistas.mp4",
                estado = EstadoSesion(estado = EstadoReproductor.CARGANDO),
                pistas = EstadoPistas(),
            )

        val texto = textoDiagnostico(diagnostico)

        assertTrue(texto.contains("Audio disponible: sin pistas"))
        assertTrue(texto.contains("Audio seleccionado: ninguno"))
        assertTrue(texto.contains("Subtítulos disponibles: sin pistas"))
        assertTrue(texto.contains("Subtítulos seleccionados: Desactivados"))
        assertTrue(texto.contains("Duración: --:--"))
        assertTrue(texto.contains("Último error: ninguno"))
    }

    @Test
    fun `el ultimo error se conserva aunque el motor ya haya recuperado`() {
        val diagnostico =
            construirDiagnostico(
                protocolo = ProtocoloStream.HLS,
                url = "http://127.0.0.1:8080/f19/f19_pistas.mp4",
                estado = EstadoSesion(estado = EstadoReproductor.REPRODUCIENDO),
                pistas = EstadoPistas(),
                ultimoError = "sin_conexion",
            )

        assertTrue(textoDiagnostico(diagnostico).contains("Último error: sin_conexion"))
    }

    @Test
    fun `el buffer se actualiza con el estado de la sesion`() {
        val primero =
            construirDiagnostico(
                protocolo = ProtocoloStream.PROGRESIVO,
                url = "http://127.0.0.1:8080/f19/f19_pistas.mp4",
                estado = EstadoSesion(bufferMs = 0L),
                pistas = EstadoPistas(),
            )
        val segundo =
            construirDiagnostico(
                protocolo = ProtocoloStream.PROGRESIVO,
                url = "http://127.0.0.1:8080/f19/f19_pistas.mp4",
                estado = EstadoSesion(bufferMs = 5_000L),
                pistas = EstadoPistas(),
            )

        assertTrue(textoDiagnostico(primero).contains("Búfer: 00:00"))
        assertTrue(textoDiagnostico(segundo).contains("Búfer: 00:05"))
        assertFalse(textoDiagnostico(primero) == textoDiagnostico(segundo))
    }

    @Test
    fun `el ultimo error de la sesion se muestra al faltar el contenido`() {
        val diagnostico =
            construirDiagnostico(
                protocolo = ProtocoloStream.PROGRESIVO,
                url = "http://127.0.0.1:8080/f19/ausente.mp4",
                estado =
                    EstadoSesion(
                        estado = EstadoReproductor.ERROR,
                        codigoError = "http_cliente",
                    ),
                pistas = EstadoPistas(),
            )

        val texto = textoDiagnostico(diagnostico)

        assertTrue(texto.contains("Último error: http_cliente"))
        assertFalse(texto.contains("http://127.0.0.1:8080/f19/ausente.mp4"))
    }

    private fun pistaAudio(
        idioma: String,
        activa: Boolean,
    ): PistaDisponible =
        PistaDisponible(
            grupo = 0,
            indice = if (idioma == "spa") 0 else 1,
            tipo = TipoPista.AUDIO,
            idioma = idioma,
            etiqueta = null,
            canales = 2,
            forzada = false,
            activa = activa,
            soportada = true,
        )

    private fun pistaSubtitulos(
        idioma: String,
        activa: Boolean,
        forzada: Boolean = false,
    ): PistaDisponible =
        PistaDisponible(
            grupo = 1,
            indice = if (idioma == "spa") 0 else 1,
            tipo = TipoPista.SUBTITULOS,
            idioma = idioma,
            etiqueta = null,
            canales = 0,
            forzada = forzada,
            activa = activa,
            soportada = true,
        )
}
