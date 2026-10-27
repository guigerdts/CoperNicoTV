package com.copernicotv.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserM3UCanarioTest {
    private val fixture =
        """
        #EXTM3U
        #EXTINF:-1 tvg-id="canal-1" tvg-name="Canal Uno HD" group-title="Noticias",Canal Uno
        http://example.com/uno.m3u8
        #EXTINF:-1 tvg-id="canal-2" tvg-name="Canal Dos" group-title="Deportes",Canal Dos, el regreso
        http://example.com/dos.m3u8
        #EXTINF:-1 group-title="Sin ID",Canal Tres
        https://example.com/tres.m3u8
        """.trimIndent()

    @Test
    fun `texto vacio devuelve error tipado`() {
        assertEquals(ErrorCanarioM3U.VACIO, ParserM3UCanario.parsear("   ").error)
    }

    @Test
    fun `sin cabecera devuelve error tipado`() {
        assertEquals(
            ErrorCanarioM3U.SIN_CABECERA,
            ParserM3UCanario.parsear("#EXTINF:-1,Canal\nhttp://example.com/a").error,
        )
    }

    @Test
    fun `bom se tolera`() {
        val resultado = ParserM3UCanario.parsear("\uFEFF#EXTM3U\n#EXTINF:-1,Canal\nhttp://example.com/a")
        assertNull(resultado.error)
        assertEquals(1, resultado.declaraciones.size)
    }

    @Test
    fun `tres entradas conservan orden y atributos`() {
        val resultado = ParserM3UCanario.parsear(fixture)
        assertNull(resultado.error)
        assertEquals(3, resultado.declaraciones.size)
        assertEquals(listOf("Canal Uno", "Canal Dos, el regreso", "Canal Tres"), resultado.declaraciones.map { it.nombre })
        assertEquals(listOf("canal-1", "canal-2", null), resultado.declaraciones.map { it.tvgId })
        assertEquals(listOf("Noticias", "Deportes", "Sin ID"), resultado.declaraciones.map { it.groupTitle })
        assertEquals(
            listOf("http://example.com/uno.m3u8", "http://example.com/dos.m3u8", "https://example.com/tres.m3u8"),
            resultado.declaraciones.map { it.urlOriginal },
        )
    }

    @Test
    fun `coma dentro de atributo no corta el nombre`() {
        val linea = "#EXTINF:-1 tvg-name=\"Uno, Dos\" group-title=\"A, B\",Canal, Tres"
        val resultado = ParserM3UCanario.parsear("#EXTM3U\n$linea\nhttp://example.com/a")
        assertEquals("Canal, Tres", resultado.declaraciones.single().nombre)
    }

    @Test
    fun `entrada sin url se rechaza`() {
        val resultado = ParserM3UCanario.parsear("#EXTM3U\n#EXTINF:-1,Sin URL")
        assertEquals(1, resultado.rechazos.size)
        assertEquals("sin_url", resultado.rechazos.single().motivo)
        assertTrue(resultado.declaraciones.isEmpty())
    }

    @Test
    fun `url sin extinf se rechaza`() {
        val resultado = ParserM3UCanario.parsear("#EXTM3U\nhttp://example.com/a")
        assertEquals("sin_extinf", resultado.rechazos.single().motivo)
    }
}
