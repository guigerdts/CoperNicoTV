package com.copernicotv.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotEquals

class ClavePresentacionTest {
    @Test
    fun `vectores de referencia de DC-05`() {
        assertEquals(
            "presentacion:v2:387666b68bf3b36ec28ee09190d5496dfff50e3a1e89bd4a1a467291bafc0047",
            ClavePresentacion.clave("Grupo", "Canal"),
        )
        assertEquals(
            "presentacion:v2:a5f8b8a4b878012675b8e79f678b52c7503b4deb3d174ff9f3777042ada39a96",
            ClavePresentacion.clave(null, null),
        )
        assertEquals(
            "presentacion:v2:6f73bb8ca9ae3a643e7a2eb1b22ced9ce68430674a1839859732432b1312a913",
            ClavePresentacion.clave("", ""),
        )
        assertEquals(
            "presentacion:v2:6f73bb8ca9ae3a643e7a2eb1b22ced9ce68430674a1839859732432b1312a913",
            ClavePresentacion.clave("   ", ""),
        )
        assertEquals(
            "presentacion:v2:ad060346b6323b9d9e64dfe159bbce2a94326b9029ac309bb7a0237e9a1090dc",
            ClavePresentacion.clave("A\u001FB", "C"),
        )
        assertEquals(
            "presentacion:v2:c87766c93b23c4c82fce88e25639c0ccbbe8b9125da57f8e6926a0d9557ac0b7",
            ClavePresentacion.clave("A", "B\u001FC"),
        )
    }

    @Test
    fun `NULL no es igual a cadena vacia`() {
        assertNotEquals(ClavePresentacion.clave(null, null), ClavePresentacion.clave("", ""))
    }

    @Test
    fun `solo espacios comparte etiqueta E con cadena vacia`() {
        assertEquals(
            ClavePresentacion.clave(" \t\u00A0\u2003 ", null),
            ClavePresentacion.clave("", null),
        )
    }

    @Test
    fun `U+001F dentro de un valor no separa campos`() {
        assertNotEquals(
            ClavePresentacion.clave("A\u001FB", "C"),
            ClavePresentacion.clave("A", "B\u001FC"),
        )
    }

    @Test
    fun `NFC unifica composiciones equivalentes`() {
        assertEquals(ClavePresentacion.clave("Jos\u00E9", null), ClavePresentacion.clave("Jose\u0301", null))
    }

    @Test
    fun `conserva caja acentos y espacios interiores`() {
        assertNotEquals(ClavePresentacion.clave("Grupo", null), ClavePresentacion.clave("grupo", null))
        assertNotEquals(ClavePresentacion.clave("a  b", null), ClavePresentacion.clave("a b", null))
    }

    @Test
    fun `rechaza surrogates no emparejados`() {
        assertFailsWith<SurrogateNoEmparejadoException> {
            ClavePresentacion.clave("\uD800", null)
        }
    }
}
