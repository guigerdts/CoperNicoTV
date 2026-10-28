package com.copernicotv.core.m3u

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserM3UTest {
    private fun parsear(
        texto: String,
        limites: LimitesParserM3U = LimitesParserM3U(),
    ): ResultadoParserM3U = ParserM3U.parsear(texto, limites)

    private fun ResultadoParserM3U.unica(): EntradaM3U {
        assertEquals(1, entradas.size, "se esperaba una única entrada")
        return entradas.single()
    }

    @Test
    fun `m3u minimo sin atributos`() {
        val resultado = parsear("#EXTM3U\n#EXTINF:-1,Canal Uno\nhttp://example.com/uno\n")
        val entrada = resultado.unica()
        assertEquals("Canal Uno", entrada.nombre)
        assertEquals("http://example.com/uno", entrada.url)
        assertEquals("-1", entrada.duracion)
        assertNull(entrada.tvgId)
        assertNull(entrada.groupTitle)
        assertEquals(1L, entrada.ordinal)
        assertEquals(2L, entrada.linea)
        assertTrue(resultado.diagnosticos.isEmpty())
    }

    @Test
    fun `atributos extendidos y atributos extra`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"id-1\" tvg-name=\"Nombre TV\" tvg-logo=\"http://logo/1.png\" " +
                "group-title=\"Grupo\" radio=\"no\" quality=\"hd\",Canal\n" +
                "http://example.com/uno\n"
        val entrada = parsear(texto).unica()
        assertEquals("id-1", entrada.tvgId)
        assertEquals("Nombre TV", entrada.tvgName)
        assertEquals("http://logo/1.png", entrada.logo)
        assertEquals("Grupo", entrada.groupTitle)
        assertEquals(listOf("radio", "quality"), entrada.atributosExtra.map { it.clave })
        assertEquals(listOf("no", "hd"), entrada.atributosExtra.map { it.valor })
        assertEquals(6, entrada.atributos.size)
    }

    @Test
    fun `extgrp fija el grupo cuando no hay group-title`() {
        val texto = "#EXTM3U\n#EXTINF:-1,Canal\n#EXTGRP:Noticias\nhttp://example.com/uno\n"
        assertEquals("Noticias", parsear(texto).unica().groupTitle)
    }

    @Test
    fun `group-title tiene prioridad sobre extgrp`() {
        val texto =
            "#EXTM3U\n#EXTINF:-1 group-title=\"Atributo\",Canal\n#EXTGRP:Directiva\nhttp://example.com/uno\n"
        assertEquals("Atributo", parsear(texto).unica().groupTitle)
    }

    @Test
    fun `comillas simples y dobles`() {
        val texto =
            "#EXTM3U\n#EXTINF:-1 tvg-id='simple' tvg-name=\"doble\",Canal\nhttp://example.com/uno\n"
        val entrada = parsear(texto).unica()
        assertEquals("simple", entrada.tvgId)
        assertEquals("doble", entrada.tvgName)
    }

    @Test
    fun `coma dentro de comillas no separa el nombre`() {
        val texto = "#EXTM3U\n#EXTINF:-1 tvg-name=\"Uno, Dos\",Canal\nhttp://example.com/uno\n"
        val entrada = parsear(texto).unica()
        assertEquals("Uno, Dos", entrada.tvgName)
        assertEquals("Canal", entrada.nombre)
    }

    @Test
    fun `coma fuera de comillas separa el nombre`() {
        val texto = "#EXTM3U\n#EXTINF:-1 tvg-name=Uno,Canal, el regreso\nhttp://example.com/uno\n"
        val entrada = parsear(texto).unica()
        assertEquals("Uno", entrada.tvgName)
        assertEquals("Canal, el regreso", entrada.nombre)
    }

    @Test
    fun `bom utf8 se descarta`() {
        val resultado = parsear("\uFEFF#EXTM3U\n#EXTINF:-1,Canal\nhttp://example.com/uno\n")
        assertEquals("Canal", resultado.unica().nombre)
        assertTrue(resultado.errores.isEmpty())
    }

    @Test
    fun `utf16 con bom se decodifica`() {
        val bytes =
            byteArrayOf(0xFF.toByte(), 0xFE.toByte()) +
                "#EXTM3U\r\n#EXTINF:-1,Canal\r\nhttp://example.com/uno\r\n".toByteArray(Charsets.UTF_16LE)
        val resultado = ParserM3U.parsear(bytes.inputStream())
        assertEquals("Canal", resultado.unica().nombre)
    }

    @Test
    fun `crlf lf y cr mezclados`() {
        val texto = "#EXTM3U\r\n#EXTINF:-1,Uno\nhttp://example.com/uno\r#EXTINF:-1,Dos\r\nhttp://example.com/dos\n"
        val resultado = parsear(texto)
        assertEquals(listOf("Uno", "Dos"), resultado.entradas.map { it.nombre })
    }

    @Test
    fun `lineas basura se informan como no fatales`() {
        val texto = "#EXTM3U\nesto no es m3u\n#EXTINF:-1,Canal\nhttp://example.com/uno\notra basura\n"
        val resultado = parsear(texto)
        assertEquals("Canal", resultado.unica().nombre)
        val diagnostico = resultado.diagnostico(CodigoM3U.LINEA_DESCONOCIDA)
        assertEquals(2L, diagnostico?.conteoTotal)
        assertEquals(SeveridadM3U.INFORMATIVO, diagnostico?.severidad)
    }

    @Test
    fun `extinf sin url produce error y la lista continua`() {
        val texto =
            "#EXTM3U\n#EXTINF:-1,Huerfano\n#EXTINF:-1,Valido\nhttp://example.com/valido\n"
        val resultado = parsear(texto)
        assertEquals(listOf("Valido"), resultado.entradas.map { it.nombre })
        val diagnostico = resultado.diagnostico(CodigoM3U.EXTINF_SIN_URL)
        assertEquals(1L, diagnostico?.conteoTotal)
        assertEquals(UbicacionM3U(2, 1), diagnostico?.ubicaciones?.single())
    }

    @Test
    fun `extinf sin url al final del documento`() {
        val resultado = parsear("#EXTM3U\n#EXTINF:-1,Ultimo\n")
        assertTrue(resultado.entradas.isEmpty())
        assertEquals(1L, resultado.diagnostico(CodigoM3U.EXTINF_SIN_URL)?.conteoTotal)
    }

    @Test
    fun `url huerfana no crea entrada`() {
        val resultado = parsear("#EXTM3U\nhttp://example.com/huerfana\n")
        assertTrue(resultado.entradas.isEmpty())
        assertEquals(1L, resultado.diagnostico(CodigoM3U.LINEA_DESCONOCIDA)?.conteoTotal)
    }

    @Test
    fun `linea excedida descarta la entrada y resincroniza en el siguiente extinf`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1,Canal uno\n" +
                "http://example.com/uno\n" +
                "#EXTINF:-1,Canal dos con nombre largo\n" +
                "http://example.com/dos\n" +
                "#EXTINF:-1,Canal tres\n" +
                "http://example.com/tres\n"
        val resultado = parsear(texto, LimitesParserM3U(maxLongitudLinea = 30))
        assertEquals(listOf("Canal uno", "Canal tres"), resultado.entradas.map { it.nombre })
        val diagnostico = resultado.diagnostico(CodigoM3U.LINEA_EXCEDIDA)
        assertEquals(1L, diagnostico?.conteoTotal)
        assertEquals(UbicacionM3U(4, null), diagnostico?.ubicaciones?.single())
    }

    @Test
    fun `linea excedida no asocia la url huerfana a la entrada descartada`() {
        val texto = "#EXTM3U\n#EXTINF:-1,Canal con nombre largo\nhttp://a\n"
        val resultado = parsear(texto, LimitesParserM3U(maxLongitudLinea = 20))
        assertTrue(resultado.entradas.isEmpty())
        assertEquals(1L, resultado.diagnostico(CodigoM3U.LINEA_EXCEDIDA)?.conteoTotal)
        assertNull(resultado.diagnostico(CodigoM3U.LINEA_DESCONOCIDA))
    }

    @Test
    fun `linea de exactamente el maximo se acepta`() {
        val linea = "#EXTINF:-1,Canal uno"
        val aceptado = parsear("#EXTM3U\n$linea\nhttp://a\n", LimitesParserM3U(maxLongitudLinea = linea.length))
        assertEquals("Canal uno", aceptado.unica().nombre)
        val excedido =
            parsear("#EXTM3U\n$linea\nhttp://a\n", LimitesParserM3U(maxLongitudLinea = linea.length - 1))
        assertTrue(excedido.entradas.isEmpty())
        assertEquals(1L, excedido.diagnostico(CodigoM3U.LINEA_EXCEDIDA)?.conteoTotal)
    }

    @Test
    fun `desborde de atributos conserva los primeros y agrega informativo`() {
        val atributos = (1..70).joinToString(" ") { "a$it=\"v$it\"" }
        val texto = "#EXTM3U\n#EXTINF:-1 $atributos,Canal\nhttp://example.com/uno\n"
        val resultado = parsear(texto)
        val entrada = resultado.unica()
        assertEquals(64, entrada.atributos.size)
        assertEquals("a1", entrada.atributos.first().clave)
        assertEquals("a64", entrada.atributos.last().clave)
        assertEquals(64, entrada.atributosExtra.size)
        val diagnostico = resultado.diagnostico(CodigoM3U.ATRIBUTOS_DESBORDADOS)
        assertEquals(1L, diagnostico?.conteoTotal)
        assertEquals(SeveridadM3U.INFORMATIVO, diagnostico?.severidad)
        assertEquals(UbicacionM3U(2, 1), diagnostico?.ubicaciones?.single())
    }

    @Test
    fun `desborde de atributos con limite configurable`() {
        val texto =
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"uno\" tvg-name=\"dos\" group-title=\"tres\",Canal\n" +
                "http://example.com/uno\n"
        val resultado = parsear(texto, LimitesParserM3U(maxAtributosPorEntrada = 2))
        val entrada = resultado.unica()
        assertEquals(2, entrada.atributos.size)
        assertEquals("uno", entrada.tvgId)
        assertEquals("dos", entrada.tvgName)
        assertNull(entrada.groupTitle)
        assertEquals(1L, resultado.diagnostico(CodigoM3U.ATRIBUTOS_DESBORDADOS)?.conteoTotal)
    }

    @Test
    fun `claves repetidas cuentan para el limite y la primera fija el valor`() {
        val texto =
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"uno\" tvg-id=\"dos\",Canal\nhttp://example.com/uno\n"
        val entrada = parsear(texto).unica()
        assertEquals("uno", entrada.tvgId)
        assertEquals(2, entrada.atributos.size)
        assertEquals(listOf("uno", "dos"), entrada.atributos.map { it.valor })
        val limitado = parsear(texto, LimitesParserM3U(maxAtributosPorEntrada = 1))
        assertEquals("uno", limitado.unica().tvgId)
        assertEquals(1, limitado.entradas.single().atributos.size)
        assertEquals(1L, limitado.diagnostico(CodigoM3U.ATRIBUTOS_DESBORDADOS)?.conteoTotal)
    }

    @Test
    fun `errores agregados por codigo con conteo total y muestra acotada`() {
        val texto =
            buildString {
                appendLine("#EXTM3U")
                repeat(100) { appendLine("#EXTINF:-1,Canal $it") }
            }
        val resultado = parsear(texto)
        assertTrue(resultado.entradas.isEmpty())
        val diagnostico = resultado.diagnostico(CodigoM3U.EXTINF_SIN_URL)
        assertEquals(100L, diagnostico?.conteoTotal)
        assertEquals(5, diagnostico?.ubicaciones?.size)
        assertEquals(UbicacionM3U(2, 1), diagnostico?.ubicaciones?.first())
    }

    @Test
    fun `no deduplica declaraciones identicas`() {
        val texto =
            "#EXTM3U\n#EXTINF:-1,Canal\nhttp://example.com/uno\n#EXTINF:-1,Canal\nhttp://example.com/uno\n"
        val resultado = parsear(texto)
        assertEquals(2, resultado.entradas.size)
        assertEquals(listOf(1L, 2L), resultado.entradas.map { it.ordinal })
    }

    @Test
    fun `atributo ausente es null y atributo vacio es cadena vacia`() {
        val texto =
            "#EXTM3U\n" +
                "#EXTINF:-1 tvg-id=\"\" tvg-name=\"\",Vacio\n" +
                "http://example.com/uno\n" +
                "#EXTINF:-1,Ausente\n" +
                "http://example.com/dos\n" +
                "#EXTINF:-1,\n" +
                "http://example.com/tres\n" +
                "#EXTINF:-1\n" +
                "http://example.com/cuatro\n"
        val resultado = parsear(texto)
        assertEquals(4, resultado.entradas.size)
        assertEquals("", resultado.entradas[0].tvgId)
        assertEquals("", resultado.entradas[0].tvgName)
        assertNull(resultado.entradas[1].tvgId)
        assertEquals("", resultado.entradas[2].nombre)
        assertNull(resultado.entradas[3].nombre)
    }

    @Test
    fun `la url se conserva exacta con parametros de consulta`() {
        val url = "http://example.com/live/index.m3u8?usuario=ana&clave=s3cr3t&z=1"
        assertEquals(url, parsear("#EXTM3U\n#EXTINF:-1,Canal\n$url\n").unica().url)
    }

    @Test
    fun `sin cabecera informa error y continua`() {
        val resultado = parsear("#EXTINF:-1,Canal\nhttp://example.com/uno\n")
        assertEquals("Canal", resultado.unica().nombre)
        assertEquals(1L, resultado.diagnostico(CodigoM3U.SIN_CABECERA)?.conteoTotal)
    }

    @Test
    fun `token sin igual se tolera`() {
        val texto =
            "#EXTM3U\n#EXTINF:-1 tvg-id=\"uno\" basura tvg-name=\"dos\",Canal\nhttp://example.com/uno\n"
        val entrada = parsear(texto).unica()
        assertEquals("uno", entrada.tvgId)
        assertEquals("dos", entrada.tvgName)
        assertEquals(2, entrada.atributos.size)
    }

    @Test
    fun `entrega streaming sin acumular entradas`() {
        val recibidas = ArrayList<EntradaM3U>()
        val texto =
            buildString {
                appendLine("#EXTM3U")
                repeat(3) {
                    appendLine("#EXTINF:-1,Canal $it")
                    appendLine("http://example.com/$it")
                }
            }
        val resultado = ParserM3U.parsear(texto, LimitesParserM3U(), recibidas::add)
        assertTrue(resultado.entradas.isEmpty())
        assertEquals(listOf("Canal 0", "Canal 1", "Canal 2"), recibidas.map { it.nombre })
    }

    @Test
    fun `fixture m3u`() {
        val resultado = recurso("/m3u/lista-simple.m3u")
        assertEquals(2, resultado.entradas.size)
        assertEquals("Noticias 24", resultado.entradas[0].nombre)
        assertEquals("dep-1", resultado.entradas[1].tvgId)
        assertEquals("Deportes", resultado.entradas[1].groupTitle)
        assertEquals("http://example.com/logo.png", resultado.entradas[1].logo)
    }

    @Test
    fun `fixture m3u8`() {
        val resultado = recurso("/m3u/lista-hd.m3u8")
        assertEquals(2, resultado.entradas.size)
        assertEquals("cine-1", resultado.entradas[0].tvgId)
        assertEquals("Cine Uno, HD", resultado.entradas[0].nombre)
        assertEquals("https://example.com/cine1.m3u8?token=abc", resultado.entradas[0].url)
        assertEquals("Series, Todo", resultado.entradas[1].tvgName)
        assertEquals("Series", resultado.entradas[1].groupTitle)
    }

    @Test
    fun `diez mil entradas se recuperan completas`() {
        val texto =
            buildString {
                appendLine("#EXTM3U")
                repeat(10_000) { indice ->
                    appendLine("#EXTINF:-1 tvg-id=\"id-$indice\",Canal $indice")
                    appendLine("http://example.com/$indice")
                }
            }
        val resultado = parsear(texto)
        assertEquals(10_000, resultado.entradas.size)
        assertEquals("id-9999", resultado.entradas.last().tvgId)
        assertTrue(resultado.diagnosticos.isEmpty())
    }

    private fun recurso(nombre: String): ResultadoParserM3U {
        val flujo =
            ParserM3UTest::class.java.getResourceAsStream(nombre)
                ?: error("recurso ausente: $nombre")
        return flujo.use { ParserM3U.parsear(it) }
    }
}
