package com.copernicotv.core.m3u

import com.copernicotv.core.identity.AtributoExtra
import com.copernicotv.core.identity.CodigoErrorUrl
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
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

    /** E7-T7: la sobrecarga por parseo previo produce el mismo resultado que el texto. */
    @Test
    fun `la sobrecarga con parseo previo equivale al camino por texto`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\" group-title=\"G\",Canal A\n" +
                "https://example.com/a.ts\n" +
                "#EXTINF:-1,Canal B\n" +
                "https://example.com/b.m3u8\n"
        val porTexto = ImportadorM3U.importar(texto, clave)
        val porParseo = ImportadorM3U.importar(ParserM3U.parsear(texto), clave)

        assertEquals(porTexto.canales.size, porParseo.canales.size)
        assertEquals(
            porTexto.canales.map { Triple(it.nombre, it.urlOriginal, it.urlStreamHmac) },
            porParseo.canales.map { Triple(it.nombre, it.urlOriginal, it.urlStreamHmac) },
        )
        assertEquals(porTexto.diagnosticos, porParseo.diagnosticos)
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

    @Test
    fun `puede sellar con una generacion hmac distinta de la inicial`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-logo=\"https://example.com/l.png\",Canal\n" +
                "https://example.com/x\n"
        val canal = ImportadorM3U.importar(texto, clave, generacionHmac = 7).canales.single()
        assertTrue(canal.urlStreamHmac.startsWith("hmac:v7:cv1:"))
        assertNotNull(canal.logoHmac)
        assertTrue(canal.logoHmac.startsWith("hmac:v7:cv1:"))
    }

    /** E7-T7: lectura por pasos equivale al camino por texto, diagnósticos incluidos. */
    @Test
    fun `leerDeclaraciones e importarDeclaraciones equivalen al camino por texto`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"a\" group-title=\"G\",Canal A\n" +
                "https://example.com/a.ts\n" +
                "#EXTINF:-1,Canal B\n" +
                "https://example.com/b.m3u8\n" +
                "#EXTINF:-1,Rechazada\n" +
                "no-es-una-url\n"
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        val porPasos =
            ImportadorM3U
                .importarDeclaraciones(lectura.declaraciones, clave)
                .copy(diagnosticos = lectura.diagnosticos)
        val porTexto = ImportadorM3U.importar(texto, clave)

        assertEquals(porTexto, porPasos)
    }

    @Test
    fun `el avance reporta entradas emitidas y canales procesados`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal A\nhttps://example.com/a\n" +
                "#EXTINF:-1,Canal B\nhttps://example.com/b\n" +
                "#EXTINF:-1,Canal C\nhttps://example.com/c\n"
        val avancesEntradas = mutableListOf<Int>()
        val lectura =
            ImportadorM3U.leerDeclaraciones(texto.byteInputStream()) { total ->
                avancesEntradas += total
            }
        assertEquals(listOf(1, 2, 3), avancesEntradas)
        assertEquals(3, lectura.entradas)

        val avancesCanales = mutableListOf<Int>()
        ImportadorM3U.importarDeclaraciones(lectura.declaraciones, clave) { total ->
            avancesCanales += total
        }
        assertEquals(listOf(1, 2, 3), avancesCanales)
    }

    @Test
    fun `el avance puede abortar la lectura de forma cooperativa`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal A\nhttps://example.com/a\n" +
                "#EXTINF:-1,Canal B\nhttps://example.com/b\n"
        val error =
            assertFailsWith<CancelacionPrueba> {
                ImportadorM3U.leerDeclaraciones(texto.byteInputStream()) { total ->
                    if (total >= 1) throw CancelacionPrueba()
                }
            }
        assertEquals("cancelada", error.message)
    }

    @Test
    fun `el avance puede abortar el procesamiento de forma cooperativa`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal A\nhttps://example.com/a\n" +
                "#EXTINF:-1,Canal B\nhttps://example.com/b\n"
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        val error =
            assertFailsWith<CancelacionPrueba> {
                ImportadorM3U.importarDeclaraciones(lectura.declaraciones, clave) { total ->
                    if (total >= 1) throw CancelacionPrueba()
                }
            }
        assertEquals("cancelada", error.message)
    }

    @Test
    fun `una lista extensa procesa completa con avance final`() {
        val texto =
            buildString {
                append("#EXTM3U\n")
                repeat(2000) { indice ->
                    append("#EXTINF:-1 tvg-id=\"c$indice\" group-title=\"G\",Canal $indice\n")
                    append("https://example.com/$indice.ts\n")
                }
            }
        val lectura = ImportadorM3U.leerDeclaraciones(texto.byteInputStream())
        assertEquals(2000, lectura.entradas)
        var ultimoAvance = 0
        val resultado =
            ImportadorM3U.importarDeclaraciones(lectura.declaraciones, clave) { total ->
                ultimoAvance = total
            }
        assertEquals(2000, ultimoAvance)
        assertEquals(2000, resultado.canales.size)
    }

    /** Excepción de prueba para la cancelación cooperativa por avance. */
    private class CancelacionPrueba : RuntimeException("cancelada")
}
