package com.copernicotv.core.crypto

import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Pruebas de la envolvente 0x02 (E7-T7 ronda 8, S2/S4): clave de datos de
 * sesión protegida por la raíz; el formato es `0x02 + IV(12) + ciphertext +
 * tag(16)` con el mismo AAD contractual por columna.
 */
class CifradoAesDatosTest {
    private val claveRaiz = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val claveDatos = SecretKeySpec(ByteArray(32) { (31 - it).toByte() }, "AES")
    private val otraClaveDatos = SecretKeySpec(ByteArray(32) { (17 - it).toByte() }, "AES")
    private val aad = CifradoAes.aad("Canal", "url_stream_cifrada")
    private val original = "http://usuario:clave@example.com/canal?token=abc".toByteArray()

    @Test
    fun `round trip 0x02 conserva el original exacto`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        val resultado = CifradoAes.descifrarSobre(claveRaiz, claveDatos, aad, sobre)
        assertTrue(resultado is ResultadoDescifrado.Ok)
        assertContentEquals(original, (resultado as ResultadoDescifrado.Ok).bytes)
    }

    @Test
    fun `formato 0x02 con version iv y tag`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        assertEquals(CifradoAes.VERSION_DATOS, sobre[0])
        assertEquals(1 + 12 + original.size + 16, sobre.size)
    }

    @Test
    fun `0x02 usa un iv distinto por operacion`() {
        val a = CifradoAes.cifrarDatos(claveDatos, aad, original)
        val b = CifradoAes.cifrarDatos(claveDatos, aad, original)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `ciphertext 0x02 alterado no descifra`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        sobre[sobre.lastIndex] = (sobre.last().toInt() xor 0x01).toByte()
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION),
            CifradoAes.descifrarSobre(claveRaiz, claveDatos, aad, sobre),
        )
    }

    @Test
    fun `clave de datos incorrecta no descifra 0x02`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION),
            CifradoAes.descifrarSobre(claveRaiz, otraClaveDatos, aad, sobre),
        )
    }

    @Test
    fun `la raiz no descifra un sobre 0x02`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION),
            CifradoAes.descifrarSobre(claveRaiz, claveRaiz, aad, sobre),
        )
    }

    @Test
    fun `sin clave de datos un sobre 0x02 es clave invalida`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.CLAVE_INVALIDA),
            CifradoAes.descifrarSobre(claveRaiz, null, aad, sobre),
        )
    }

    @Test
    fun `registros 0x01 siguen legibles por el despacho`() {
        val sobre = CifradoAes.cifrar(claveRaiz, aad, original)
        val resultado = CifradoAes.descifrarSobre(claveRaiz, claveDatos, aad, sobre)
        assertTrue(resultado is ResultadoDescifrado.Ok)
        assertContentEquals(original, (resultado as ResultadoDescifrado.Ok).bytes)
    }

    @Test
    fun `aad de otra columna no descifra en 0x02`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION),
            CifradoAes.descifrarSobre(claveRaiz, claveDatos, CifradoAes.aad("Lista", "ubicacion_cifrada"), sobre),
        )
    }

    @Test
    fun `version desconocida sigue siendo formato invalido en el despacho`() {
        val sobre = CifradoAes.cifrarDatos(claveDatos, aad, original)
        sobre[0] = 0x09
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO),
            CifradoAes.descifrarSobre(claveRaiz, claveDatos, aad, sobre),
        )
    }
}
