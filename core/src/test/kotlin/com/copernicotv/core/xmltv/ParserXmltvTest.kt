package com.copernicotv.core.xmltv

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import java.io.InputStream
import java.time.Instant
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ParserXmltvTest {
    private class Colector : ConsumidorXmltv {
        val canales = mutableListOf<CanalXmltv>()
        val programas = mutableListOf<ProgramaXmltv>()
        val errores = mutableListOf<ErrorXmltv>()

        override suspend fun alCanal(canal: CanalXmltv) {
            canales += canal
        }

        override suspend fun alPrograma(programa: ProgramaXmltv) {
            programas += programa
        }

        override suspend fun alError(error: ErrorXmltv) {
            errores += error
        }
    }

    private fun parsear(
        texto: String,
        colector: Colector = Colector(),
        cancelacion: () -> Boolean = { false },
    ): Pair<ResultadoParseoXmltv, Colector> =
        runBlocking {
            val resultado =
                ParserXmltv().parsear(
                    ByteArrayInputStream(texto.toByteArray(Charsets.UTF_8)),
                    colector,
                    cancelacion,
                )
            resultado to colector
        }

    private val valido =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <tv>
          <channel id="a"><display-name>Canal A</display-name><icon src="http://icons.example/a.png"/></channel>
          <channel id="b"><display-name>Canal B</display-name></channel>
          <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a">
            <title>Programa 1</title><desc>Descripción 1</desc>
          </programme>
          <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="a">
            <title>Programa 2</title>
          </programme>
          <programme start="20260101120000 +0000" stop="20260101123000 +0000" channel="b">
            <title>Programa 3</title>
          </programme>
        </tv>
        """.trimIndent()

    @Test
    fun `xmltv valido produce canales y programas`() {
        val (resultado, colector) = parsear(valido)
        assertNull(resultado.errorGlobal)
        assertEquals(2, resultado.canales)
        assertEquals(3, resultado.programas)
        assertEquals(0, resultado.descartados)
        assertEquals(0, resultado.erroresTotales)
        assertEquals(listOf("a", "b"), colector.canales.map { it.id })
        assertEquals("Canal A", colector.canales.first().nombres.single())
        assertEquals("http://icons.example/a.png", colector.canales.first().iconos.single())
        assertEquals("Programa 1", colector.programas.first().titulo)
        assertEquals("Descripción 1", colector.programas.first().descripcion)
        assertNull(colector.programas[1].descripcion)
    }

    @Test
    fun `programa con fecha invalida genera error tolerado y continua`() {
        val texto =
            """
            <tv>
              <channel id="a"><display-name>A</display-name></channel>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a"><title>Bueno</title></programme>
              <programme start="no-es-fecha" stop="20260101140000 +0000" channel="a"><title>Malo</title></programme>
              <programme start="20260101140000 +0000" stop="20260101150000 +0000" channel="a"><title>Otro bueno</title></programme>
            </tv>
            """.trimIndent()
        val (resultado, colector) = parsear(texto)
        assertNull(resultado.errorGlobal)
        assertEquals(2, resultado.programas)
        assertEquals(1, resultado.descartados)
        assertEquals(1, resultado.erroresTotales)
        assertEquals(ErrorElementoXmltv.FECHA_INVALIDA, resultado.errores.single().tipo)
        assertEquals(listOf("Bueno", "Otro bueno"), colector.programas.map { it.titulo })
    }

    @Test
    fun `programa incompleto se descarta y conserva lo parseado`() {
        val texto =
            """
            <tv>
              <channel id="a"><display-name>A</display-name></channel>
              <programme start="20260101120000 +0000" channel="a"><title>Sin fin</title></programme>
              <programme start="20260101130000 +0000" stop="20260101140000 +0000"><title>Sin canal</title></programme>
              <programme start="20260101140000 +0000" stop="20260101150000 +0000" channel="a"><title>Completo</title></programme>
            </tv>
            """.trimIndent()
        val (resultado, colector) = parsear(texto)
        assertNull(resultado.errorGlobal)
        assertEquals(1, resultado.programas)
        assertEquals(2, resultado.descartados)
        assertEquals(1, colector.programas.size)
        assertEquals("Completo", colector.programas.single().titulo)
        assertEquals(
            setOf(ErrorElementoXmltv.PROGRAMA_SIN_FIN, ErrorElementoXmltv.PROGRAMA_SIN_CANAL),
            resultado.errores.map { it.tipo }.toSet(),
        )
    }

    @Test
    fun `campos ausentes quedan vacios sin crash`() {
        val texto =
            """
            <tv>
              <channel id="a"><display-name>A</display-name></channel>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a"></programme>
            </tv>
            """.trimIndent()
        val (resultado, colector) = parsear(texto)
        assertEquals(1, resultado.programas)
        assertEquals("", colector.programas.single().titulo)
        assertNull(colector.programas.single().descripcion)
        assertEquals(ErrorElementoXmltv.TITULO_AUSENTE, resultado.errores.single().tipo)
    }

    @Test
    fun `fechas con offsets distintos producen el instante correcto`() {
        val texto =
            """
            <tv>
              <channel id="a"><display-name>A</display-name></channel>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a"><title>UTC</title></programme>
              <programme start="20260101120000 +0200" stop="20260101130000 +0200" channel="a"><title>Mas dos</title></programme>
              <programme start="20260101120000 -0500" stop="20260101130000 -0500" channel="a"><title>Menos cinco</title></programme>
            </tv>
            """.trimIndent()
        val (resultado, colector) = parsear(texto)
        assertEquals(3, resultado.programas)
        assertEquals(Instant.parse("2026-01-01T12:00:00Z"), colector.programas[0].inicio)
        assertEquals(Instant.parse("2026-01-01T10:00:00Z"), colector.programas[1].inicio)
        assertEquals(Instant.parse("2026-01-01T17:00:00Z"), colector.programas[2].inicio)
    }

    @Test
    fun `raiz invalida es error global sin elementos`() {
        val (resultado, colector) = parsear("<otra><channel id=\"a\"/></otra>")
        assertEquals(ErrorGlobalXmltv.RAIZ_INVALIDA, resultado.errorGlobal)
        assertEquals(0, resultado.canales)
        assertTrue(colector.canales.isEmpty())
    }

    @Test
    fun `xml malformado reporta recuperados y descartados`() {
        val texto =
            """
            <tv>
              <channel id="a"><display-name>A</display-name></channel>
              <programme start="20260101120000 +0000" stop="20260101130000 +0000" channel="a"><title>Uno</title></programme>
              <programme start="20260101130000 +0000" stop="20260101140000 +0000" channel="a">
            """.trimIndent()
        val (resultado, _) = parsear(texto)
        assertNotNull(resultado.errorGlobal)
        assertEquals(1, resultado.programas)
        assertTrue(resultado.errorGlobal in setOf(ErrorGlobalXmltv.XML_MALFORMADO, ErrorGlobalXmltv.EOF_ABRUPTO))
    }

    @Test
    fun `cancelacion cooperativa detiene el parseo`() {
        val colector = Colector()
        var vistos = 0
        val (resultado, _) =
            parsear(valido, colector) {
                vistos++
                vistos > 1
            }
        assertTrue(resultado.cancelado)
        assertNull(resultado.errorGlobal)
        assertTrue(resultado.programas < 3)
    }

    @Test
    fun `no lee el documento completo antes de entregar el primer programa`() {
        val programas =
            (1..5000).joinToString("\n") { indice ->
                "<programme start=\"20260101120000 +0000\" stop=\"20260101130000 +0000\" channel=\"a\"><title>P$indice</title></programme>"
            }
        val texto = "<tv><channel id=\"a\"><display-name>A</display-name></channel>$programas</tv>"
        val bytes = texto.toByteArray(Charsets.UTF_8)
        val contador = ContadorInputStream(ByteArrayInputStream(bytes))
        var cancelado = false
        val resultado =
            runBlocking {
                ParserXmltv().parsear(
                    entrada = contador,
                    consumidor =
                        object : ConsumidorXmltv {
                            override suspend fun alPrograma(programa: ProgramaXmltv) {
                                cancelado = true
                            }
                        },
                    cancelacion = { cancelado },
                )
            }
        assertTrue(resultado.cancelado)
        assertEquals(1, resultado.programas)
        assertTrue(contador.leidos < bytes.size, "el parser leyó todo el documento")
    }

    private class ContadorInputStream(
        private val base: InputStream,
    ) : InputStream() {
        var leidos: Long = 0
            private set

        override fun read(): Int {
            val valor = base.read()
            if (valor >= 0) leidos++
            return valor
        }

        override fun read(
            b: ByteArray,
            off: Int,
            len: Int,
        ): Int {
            val leido = base.read(b, off, len)
            if (leido > 0) leidos += leido
            return leido
        }
    }
}
