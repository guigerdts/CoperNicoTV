package com.copernicotv.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserM3UGuiaTest {
    @Test
    fun `url-tvg con varias urls se captura en orden y sin duplicados`() {
        val texto =
            """
            #EXTM3U url-tvg="https://guia.ejemplo.tv/a.xml,https://guia.ejemplo.tv/b.xml"
            #EXTINF:-1 tvg-id="uno",Canal uno
            https://medio.ejemplo.tv/uno.m3u8
            """.trimIndent()
        val resultado = ParserM3U.parsear(texto)
        assertEquals(
            listOf("https://guia.ejemplo.tv/a.xml", "https://guia.ejemplo.tv/b.xml"),
            resultado.urlsGuia,
        )
        assertEquals("https://guia.ejemplo.tv/a.xml", resultado.urlGuia)
    }

    @Test
    fun `x-tvg-url con comillas simples y clave en mayusculas se reconoce`() {
        val texto =
            """
            #EXTM3U X-TVG-URL='https://guia.ejemplo.tv/c.xml'
            #EXTINF:-1,Canal
            https://medio.ejemplo.tv/c.m3u8
            """.trimIndent()
        assertEquals(listOf("https://guia.ejemplo.tv/c.xml"), ParserM3U.parsear(texto).urlsGuia)
    }

    @Test
    fun `tvg-url sin comillas se reconoce`() {
        val texto =
            """
            #EXTM3U tvg-url=https://guia.ejemplo.tv/d.xml
            #EXTINF:-1,Canal
            https://medio.ejemplo.tv/d.m3u8
            """.trimIndent()
        assertEquals(listOf("https://guia.ejemplo.tv/d.xml"), ParserM3U.parsear(texto).urlsGuia)
    }

    @Test
    fun `una cabecera sin guia no inventa una url ni genera error`() {
        val texto =
            """
            #EXTM3U
            #EXTINF:-1,Canal
            https://medio.ejemplo.tv/e.m3u8
            """.trimIndent()
        val resultado = ParserM3U.parsear(texto)
        assertTrue(resultado.urlsGuia.isEmpty())
        assertNull(resultado.urlGuia)
        assertTrue(resultado.errores.isEmpty())
    }

    @Test
    fun `una lista sin cabecera no captura guia`() {
        val texto =
            """
            #EXTINF:-1,Canal
            https://medio.ejemplo.tv/f.m3u8
            """.trimIndent()
        val resultado = ParserM3U.parsear(texto)
        assertTrue(resultado.urlsGuia.isEmpty())
        assertTrue(resultado.errores.any { it.codigo == CodigoM3U.SIN_CABECERA })
    }

    @Test
    fun `las urls de guia no se confunden con atributos de entrada`() {
        val texto =
            """
            #EXTM3U url-tvg="https://guia.ejemplo.tv/g.xml"
            #EXTINF:-1 tvg-id="x" tvg-name="Canal X",Canal X
            https://medio.ejemplo.tv/g.m3u8
            """.trimIndent()
        val resultado = ParserM3U.parsear(texto)
        assertEquals(1, resultado.entradas.size)
        assertEquals("x", resultado.entradas.single().tvgId)
        assertEquals(listOf("https://guia.ejemplo.tv/g.xml"), resultado.urlsGuia)
    }
}
