package com.copernicotv.core.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class HuellaAsociacionTest {
    private val clave = "clave-de-prueba-epg".toByteArray()

    private fun calcular(
        nombres: List<String> = emptyList(),
        iconos: List<String> = emptyList(),
        claveHmac: ByteArray? = clave,
    ): ResultadoHuella = HuellaAsociacion.calcular(nombres, iconos, claveHmac)

    @Test
    fun `normaliza nfc minusculas espacios y conserva acentos`() {
        val esperado = "canal uno á"
        assertEquals(esperado, HuellaAsociacion.normalizarEpg("  CANAL\u00A0\u00A0UNO   Á  "))
        assertEquals(esperado, HuellaAsociacion.normalizarEpg("Canal\u2003Uno\u2003Á"))
        assertEquals("canal uno á", HuellaAsociacion.normalizarEpg("Canal Uno A\u0301"))
    }

    @Test
    fun `la huella no depende del orden ni de duplicados`() {
        val primera = calcular(nombres = listOf("Canal A", "Canal B", "Canal A"))
        val segunda = calcular(nombres = listOf("canal b", "  CANAL   A "))
        assertEquals(primera.huella, segunda.huella)
        assertEquals(listOf("canal a", "canal b"), primera.senales.nombres)
    }

    @Test
    fun `la huella cambia si cambia el nombre`() {
        val primera = calcular(nombres = listOf("Canal A"))
        val segunda = calcular(nombres = listOf("Canal B"))
        assertNotEquals(primera.huella, segunda.huella)
    }

    @Test
    fun `prefijo y longitud de huella v2`() {
        val resultado = calcular(nombres = listOf("Canal A"))
        val huella = resultado.huella
        assertTrue(huella != null && huella.startsWith(HuellaAsociacion.PREFIJO))
        assertEquals(64, huella.removePrefix(HuellaAsociacion.PREFIJO).length)
        assertTrue(huella.removePrefix(HuellaAsociacion.PREFIJO).all { it in "0123456789abcdef" })
    }

    @Test
    fun `iconos eliminan query y fragmento y normalizan esquema host y puerto`() {
        val primera =
            calcular(
                nombres = listOf("Canal A"),
                iconos = listOf("HTTPS://Icons.Example:443/Logo.PNG?token=abc#frag"),
            )
        val segunda =
            calcular(
                nombres = listOf("Canal A"),
                iconos = listOf("https://icons.example/Logo.PNG?otro=1"),
            )
        assertEquals(primera.huella, segunda.huella)
        assertEquals(primera.senales.iconos, segunda.senales.iconos)
    }

    @Test
    fun `el path del icono distingue la huella`() {
        val primera = calcular(nombres = listOf("Canal A"), iconos = listOf("https://icons.example/a.png"))
        val segunda = calcular(nombres = listOf("Canal A"), iconos = listOf("https://icons.example/b.png"))
        assertNotEquals(primera.huella, segunda.huella)
    }

    @Test
    fun `icono no canonicalizable se excluye y se cuenta`() {
        val resultado =
            calcular(
                nombres = listOf("Canal A"),
                iconos = listOf("no-es-url", "ftp://icons.example/a.png", "https://icons.example/a.png"),
            )
        assertEquals(2, resultado.iconosExcluidos)
        assertEquals(1, resultado.senales.iconos.size)
        assertTrue(resultado.huella != null)
    }

    @Test
    fun `con iconos y sin clave hmac no se calcula huella`() {
        val resultado =
            calcular(
                nombres = listOf("Canal A"),
                iconos = listOf("https://icons.example/a.png"),
                claveHmac = null,
            )
        assertNull(resultado.huella)
        assertTrue(resultado.requiereClaveHmac)
        assertEquals(listOf("canal a"), resultado.senales.nombres)
        assertTrue(resultado.senales.iconos.isEmpty())
    }

    @Test
    fun `sin iconos la huella se calcula solo con nombres`() {
        val resultado = calcular(nombres = listOf("Canal A"), iconos = emptyList(), claveHmac = null)
        assertTrue(resultado.huella != null)
        assertEquals(false, resultado.requiereClaveHmac)
    }

    @Test
    fun `sin nombres ni iconos no hay huella`() {
        val resultado = calcular(nombres = listOf("   "), iconos = emptyList())
        assertNull(resultado.huella)
        assertEquals(SenalesFuertes(), resultado.senales)
    }

    @Test
    fun `senales fuertes json conserva escapes y orden`() {
        val original =
            SenalesFuertes(
                nombres = listOf("canal \"uno\"", "canal\\dos", "canal\nlinea"),
                iconos = listOf("00ff", "aabb"),
            )
        val json = original.aJson()
        assertEquals(original, SenalesFuertes.desdeJson(json))
        assertTrue(json.startsWith("{\"nombres\":"))
        assertTrue(json.endsWith("}"))
    }

    @Test
    fun `json invalido devuelve senales vacias`() {
        assertEquals(SenalesFuertes(), SenalesFuertes.desdeJson("{\"nombres\":[}"))
        assertEquals(SenalesFuertes(), SenalesFuertes.desdeJson(null))
    }

    @Test
    fun `la entrada serializada separa nombres e iconos`() {
        val nombres = HuellaAsociacion.serializarEntrada(listOf("canal a"), emptyList())
        val iconos = HuellaAsociacion.serializarEntrada(emptyList(), listOf("00ff"))
        assertNotEquals(nombres.toList(), iconos.toList())
        val texto = nombres.toString(Charsets.ISO_8859_1)
        assertTrue(texto.startsWith(HuellaAsociacion.DOMINIO_HUELLA))
    }
}
