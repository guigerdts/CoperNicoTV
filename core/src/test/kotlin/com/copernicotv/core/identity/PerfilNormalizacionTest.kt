package com.copernicotv.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotEquals

class PerfilNormalizacionTest {
    private val folderMinusculas = CaseFolder { it.lowercase() }

    @Test
    fun `aplica recorte exterior NFC y casefold`() {
        assertEquals(
            "trabajo",
            PerfilNormalizacion.normalizar("  Trabajo  ", folderMinusculas),
        )
    }

    @Test
    fun `NFC antes y despues del casefold`() {
        assertEquals(
            PerfilNormalizacion.normalizar("Jos\u00E9", folderMinusculas),
            PerfilNormalizacion.normalizar("Jose\u0301", folderMinusculas),
        )
    }

    @Test
    fun `los acentos no se eliminan`() {
        assertNotEquals(
            PerfilNormalizacion.normalizar("José", folderMinusculas),
            PerfilNormalizacion.normalizar("Jose", folderMinusculas),
        )
    }

    @Test
    fun `solo recorta White_Space no otros separadores`() {
        assertEquals(
            "\u200Ba\u200Bb\u200B",
            PerfilNormalizacion.normalizar("\u200Ba\u200Bb\u200B", folderMinusculas),
        )
    }

    @Test
    fun `rechaza surrogates no emparejados`() {
        assertFailsWith<SurrogateNoEmparejadoException> {
            PerfilNormalizacion.normalizar("\uDC00", folderMinusculas)
        }
    }

    @Test
    fun `version normativa fijada en 15_1_0`() {
        assertEquals("15.1.0", VERSION_UNICODE_NORMATIVA)
        assertEquals(UnicodePlataforma.soporta151(), PerfilNormalizacion.disponibleEnPlataforma())
    }

    @Test
    fun `la plataforma de este entorno no expone Unicode 15_1`() {
        // JDK 17 implementa Unicode 13.0. Si esta aserción falla, la plataforma
        // cambió y la conformidad debe reevaluarse antes de retirar el bloqueo.
        assertFalse(UnicodePlataforma.soporta151())
    }
}
