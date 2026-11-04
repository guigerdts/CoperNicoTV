package com.copernicotv.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ClaveCanalTest {
    private val url = "http://example.com/canal"

    @Test
    fun `espacios exteriores y caja variable producen la misma clave`() {
        val a = ClaveCanal.canonica("  Canal  Uno  ", url)
        val b = ClaveCanal.canonica("canal  uno", url)
        assertEquals(a, b)
    }

    @Test
    fun `acentos no se eliminan`() {
        assertNotEquals(ClaveCanal.canonica("José", url), ClaveCanal.canonica("Jose", url))
    }

    @Test
    fun `NFC unifica composiciones equivalentes`() {
        assertEquals(ClaveCanal.canonica("Jos\u00E9", url), ClaveCanal.canonica("Jose\u0301", url))
    }

    @Test
    fun `sin nombre no produce clave canonica`() {
        assertNull(ClaveCanal.canonica(null, url))
        assertNull(ClaveCanal.canonica("", url))
        assertNull(ClaveCanal.canonica("   \u00A0", url))
    }

    @Test
    fun `clave canonica tiene prefijo y 64 hex minusculas`() {
        val clave = ClaveCanal.canonica("Canal", url)
        assertTrue(clave != null)
        assertTrue(clave.startsWith("canon:v2:"))
        val digest = clave.removePrefix("canon:v2:")
        assertEquals(64, digest.length)
        assertTrue(digest.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `tvg id valido y unico es la clave de unicidad`() {
        val identidad = ClaveCanal.derivar("Canal", url, "canal.es", tvgIdUnicoEnFuente = true)
        assertEquals("tvg:v1:canal.es", identidad.claveUnicidad)
        assertEquals("canon:v2:", identidad.claveCanonica?.take(9))
        assertEquals(true, identidad.claveDerivable)
        assertEquals("canal.es", identidad.tvgIdPersistido)
    }

    @Test
    fun `tvg id duplicado no habilita clave y se deriva de la canonica`() {
        val identidad = ClaveCanal.derivar("Canal", url, "canal.es", tvgIdUnicoEnFuente = false)
        assertEquals(identidad.claveCanonica, identidad.claveUnicidad)
        assertEquals("canal.es", identidad.tvgIdPersistido)
        assertEquals(true, identidad.claveDerivable)
    }

    @Test
    fun `tvg id invalido no se persiste`() {
        val identidad = ClaveCanal.derivar("Canal", url, "   ", tvgIdUnicoEnFuente = true)
        assertNull(identidad.tvgIdPersistido)
        assertEquals(identidad.claveCanonica, identidad.claveUnicidad)
    }

    @Test
    fun `sin nombre y sin tvg no es derivable`() {
        val identidad = ClaveCanal.derivar(null, url, null, tvgIdUnicoEnFuente = false)
        assertNull(identidad.claveCanonica)
        assertNull(identidad.claveUnicidad)
        assertEquals(false, identidad.claveDerivable)
    }

    @Test
    fun `sin nombre con tvg valido deriva por tvg`() {
        val identidad = ClaveCanal.derivar(null, url, "canal.es", tvgIdUnicoEnFuente = true)
        assertNull(identidad.claveCanonica)
        assertEquals("tvg:v1:canal.es", identidad.claveUnicidad)
        assertEquals(true, identidad.claveDerivable)
    }

    @Test
    fun `estados B y C no exponen clave de unicidad`() {
        for (estado in listOf(EstadoCanal.B, EstadoCanal.C)) {
            val identidad = ClaveCanal.derivar("Canal", url, "canal.es", tvgIdUnicoEnFuente = true, estado = estado)
            assertNull(identidad.claveUnicidad)
            assertEquals("canon:v2:", identidad.claveCanonica?.take(9))
        }
    }

    @Test
    fun `rechaza surrogates no emparejados`() {
        assertFailsWith<SurrogateNoEmparejadoException> {
            ClaveCanal.canonica("Canal\uD800", url)
        }
    }
}
