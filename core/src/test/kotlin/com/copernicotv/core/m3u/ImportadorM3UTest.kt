package com.copernicotv.core.m3u

import com.copernicotv.core.identity.AtributoExtra
import com.copernicotv.core.identity.CodigoErrorUrl
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class ImportadorM3UTest {
    private val clave = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "HmacSHA256")

    @Test
    fun `importa metadatos logo y atributos extra`() {
        val texto =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="uno" tvg-name="Uno HD" group-title="Noticias" tvg-logo="https://example.com/uno.png" custom="x",Canal Uno
            https://example.com/uno.m3u8
            """.trimIndent()
        val resultado = ImportadorM3U.importar(texto, clave)
        assertEquals(1, resultado.canales.size)
        val canal = resultado.canales.single()
        assertEquals("Canal Uno", canal.nombre)
        assertEquals("uno", canal.tvgId)
        assertEquals("https://example.com/uno.m3u8", canal.urlOriginal)
        assertTrue(canal.urlStreamHmac.startsWith("hmac:v1:cv1:"))
        assertNotNull(canal.logoOriginal)
        assertNotNull(canal.logoHmac)
        assertEquals(listOf(AtributoExtra("custom", "x")), canal.atributosExtra)
        assertEquals(1, canal.apariencias.size)
        assertEquals("Noticias", canal.apariencias.single().groupTitle)
        assertEquals("Uno HD", canal.apariencias.single().tvgName)
    }

    @Test
    fun `una linea excedida descarta solo esa entrada`() {
        val larga = "x".repeat(200)
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,$larga\n" +
                "https://example.com/a\n" +
                "#EXTINF:-1,Bien\n" +
                "https://example.com/b\n"
        val resultado =
            ImportadorM3U.importar(
                texto,
                clave,
                LimitesParserM3U(maxLongitudLinea = 64),
            )
        assertEquals(1, resultado.canales.size)
        assertEquals("Bien", resultado.canales.single().nombre)
        assertEquals(1, resultado.diagnosticos.count { it.codigo == CodigoM3U.LINEA_EXCEDIDA })
    }

    @Test
    fun `declaraciones del mismo canal se agrupan en una apariencia por grupo`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 group-title=\"A\",Canal\n" +
                "https://example.com/x\n" +
                "#EXTINF:-1 group-title=\"B\",Canal\n" +
                "https://example.com/x\n"
        val resultado = ImportadorM3U.importar(texto, clave)
        assertEquals(1, resultado.canales.size)
        assertEquals(2, resultado.canales.single().apariencias.size)
    }

    @Test
    fun `una url invalida se rechaza con codigo tipado sin perder el resto`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Roto\n" +
                "no-es-url\n" +
                "#EXTINF:-1,Bien\n" +
                "https://example.com/b\n"
        val resultado = ImportadorM3U.importar(texto, clave)
        assertEquals(1, resultado.canales.size)
        assertEquals(1, resultado.rechazadas.size)
        assertEquals(CodigoErrorUrl.URL_MALFORMADA, resultado.rechazadas.single().error.codigo)
    }

    @Test
    fun `un tvg id unico habilita la clave de unicidad`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"unico\",Canal\n" +
                "https://example.com/x\n"
        val canal = ImportadorM3U.importar(texto, clave).canales.single()
        assertEquals("tvg:v1:unico", canal.claveUnicidad)
        assertTrue(canal.claveDerivable)
    }

    @Test
    fun `una lista sin entradas produce un resultado vacio`() {
        val resultado = ImportadorM3U.importar("#EXTM3U\n", clave)
        assertEquals(0, resultado.canales.size)
        assertEquals(0, resultado.rechazadas.size)
        assertTrue(resultado.errores.isEmpty())
    }
}
