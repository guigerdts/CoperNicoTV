package com.copernicotv.core.ota

import java.io.ByteArrayInputStream
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** Huellas SHA-256 del APK y de su certificado (S4). */
class IntegridadTest {
    @Test
    fun `la huella conocida de abc coincide con el vector publico`() {
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            Integridad.sha256Hex("abc".toByteArray()),
        )
    }

    @Test
    fun `la huella por stream coincide con la de memoria`() {
        val datos = ByteArray(300_000) { (it % 251).toByte() }

        assertEquals(
            Integridad.sha256Hex(datos),
            ByteArrayInputStream(datos).use { Integridad.sha256Hex(it) },
        )
    }

    @Test
    fun `la comparacion ignora mayusculas`() {
        val huella = "ab".repeat(32)

        assertTrue(Integridad.iguales(huella, huella.uppercase()))
        assertTrue(Integridad.iguales(huella, huella))
    }

    @Test
    fun `la comparacion detecta una diferencia y una longitud distinta`() {
        val huella = "ab".repeat(32)

        assertFalse(Integridad.iguales(huella, "ac" + "b".repeat(62)))
        assertFalse(Integridad.iguales(huella, huella.dropLast(1)))
        assertFalse(Integridad.iguales("", huella))
    }
}
