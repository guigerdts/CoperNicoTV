package com.copernicotv.core.xmltv

import kotlinx.coroutines.runBlocking
import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class FixtureF24Test {
    private fun parsear(bytes: ByteArray): ResultadoParseoXmltv =
        runBlocking {
            ParserXmltv().parsear(ByteArrayInputStream(bytes))
        }

    @Test
    fun `F24a conserva el XMLTV valido de 100 canales y 10000 programas`() {
        val bytes = FixtureP1Xmltv.bytes()
        assertEquals(FixtureP1Xmltv.SHA256, FixtureP1Xmltv.sha256(bytes))

        val resultado = parsear(bytes)
        assertNull(resultado.errorGlobal)
        assertEquals(FixtureP1Xmltv.CANALES, resultado.canales)
        assertEquals(FixtureP1Xmltv.PROGRAMAS, resultado.programas)
        assertEquals(0, resultado.descartados)
    }

    @Test
    fun `F24b es el XMLTV malformado tolerado`() {
        val bytes = FixtureP1Xmltv.bytes(FixtureP1Xmltv.RUTA_F24B)
        assertEquals(FixtureP1Xmltv.SHA256_F24B, FixtureP1Xmltv.sha256(bytes))

        val resultado = parsear(bytes)
        assertNotNull(resultado.errorGlobal)
        assertTrue(
            resultado.errorGlobal in setOf(ErrorGlobalXmltv.XML_MALFORMADO, ErrorGlobalXmltv.EOF_ABRUPTO),
        )
        assertEquals(1, resultado.programas)
    }

    @Test
    fun `F24c conserva lo parseado y descarta los programas incompletos`() {
        val bytes = FixtureP1Xmltv.bytes(FixtureP1Xmltv.RUTA_F24C)
        assertEquals(FixtureP1Xmltv.SHA256_F24C, FixtureP1Xmltv.sha256(bytes))

        val resultado = parsear(bytes)
        assertNull(resultado.errorGlobal)
        assertEquals(1, resultado.programas)
        assertEquals(2, resultado.descartados)
        assertEquals(
            setOf(ErrorElementoXmltv.PROGRAMA_SIN_FIN, ErrorElementoXmltv.PROGRAMA_SIN_CANAL),
            resultado.errores.map { it.tipo }.toSet(),
        )
    }
}
