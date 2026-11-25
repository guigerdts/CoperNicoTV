package com.copernicotv.core.xmltv

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * Campos extendidos de `<programme>` exigidos por el contrato EPG:
 * `sub-title`, `category`, `episode-num` y el idioma declarado.
 */
class ParserXmltvEnriquecidoTest {
    private class Colector : ConsumidorXmltv {
        val programas = mutableListOf<ProgramaXmltv>()

        override suspend fun alPrograma(programa: ProgramaXmltv) {
            programas += programa
        }
    }

    private fun parsear(texto: String): Colector =
        runBlocking {
            val colector = Colector()
            ParserXmltv().parsear(ByteArrayInputStream(texto.toByteArray(Charsets.UTF_8)), colector) { false }
            colector
        }

    private fun documento(programme: String) =
        buildString {
            append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
            append("<tv>\n")
            append("  <channel id=\"a\"><display-name>Canal A</display-name></channel>\n")
            append(programme)
            append("\n</tv>")
        }

    @Test
    fun `sub-title category episode-num e idioma se conservan`() {
        val colector =
            parsear(
                documento(
                    """
                    <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a">
                      <title lang="es">Programa</title>
                      <sub-title>Capítulo 3</sub-title>
                      <desc lang="es">Descripción</desc>
                      <category>Series</category>
                      <episode-num system="onscreen">T3 E01</episode-num>
                    </programme>
                    """.trimIndent(),
                ),
            )
        val programa = colector.programas.single()
        assertEquals("Capítulo 3", programa.subtitulo)
        assertEquals("Series", programa.categoria)
        assertEquals("T3 E01", programa.episodeNum)
        assertEquals("es", programa.idioma)
    }

    @Test
    fun `onscreen tiene prioridad sobre xmltv_ns y el idioma cae al desc`() {
        val colector =
            parsear(
                documento(
                    """
                    <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a">
                      <title>Programa</title>
                      <desc lang="en">Description</desc>
                      <episode-num system="xmltv_ns">2.0.0/1</episode-num>
                      <episode-num system="onscreen">S02E01</episode-num>
                    </programme>
                    """.trimIndent(),
                ),
            )
        val programa = colector.programas.single()
        assertEquals("S02E01", programa.episodeNum)
        assertEquals("en", programa.idioma)
    }

    @Test
    fun `sin campos extendidos quedan nulos y el programa sigue siendo valido`() {
        val colector =
            parsear(
                documento(
                    """
                    <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a">
                      <title>Programa</title>
                    </programme>
                    """.trimIndent(),
                ),
            )
        val programa = colector.programas.single()
        assertNull(programa.subtitulo)
        assertNull(programa.categoria)
        assertNull(programa.episodeNum)
        assertNull(programa.idioma)
        assertEquals("Programa", programa.titulo)
    }

    @Test
    fun `los valores en blanco no se guardan como cadena vacia`() {
        val colector =
            parsear(
                documento(
                    """
                    <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a">
                      <title>Programa</title>
                      <sub-title>   </sub-title>
                      <category></category>
                    </programme>
                    """.trimIndent(),
                ),
            )
        val programa = colector.programas.single()
        assertNull(programa.subtitulo)
        assertNull(programa.categoria)
        assertTrue(colector.programas.size == 1)
    }
}
