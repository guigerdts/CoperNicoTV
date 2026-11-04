package com.copernicotv.core.crypto

import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

class CifradoAesTest {
    private val clave = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")
    private val otraClave = SecretKeySpec(ByteArray(32) { (it + 1).toByte() }, "AES")
    private val aad = CifradoAes.aad("Canal", "url_stream_cifrada")
    private val original = "http://usuario:clave@example.com/canal?token=abc".toByteArray()

    @Test
    fun `round trip conserva el original exacto`() {
        val sobre = CifradoAes.cifrar(clave, aad, original)
        val resultado = CifradoAes.descifrar(clave, aad, sobre)
        assertTrue(resultado is ResultadoDescifrado.Ok)
        assertContentEquals(original, (resultado as ResultadoDescifrado.Ok).bytes)
    }

    @Test
    fun `formato version iv y tag`() {
        val sobre = CifradoAes.cifrar(clave, aad, original)
        assertEquals(CifradoAes.VERSION, sobre[0])
        assertEquals(1 + 12 + original.size + 16, sobre.size)
    }

    @Test
    fun `cada cifrado usa un iv distinto`() {
        val a = CifradoAes.cifrar(clave, aad, original)
        val b = CifradoAes.cifrar(clave, aad, original)
        assertNotEquals(a.toList(), b.toList())
    }

    @Test
    fun `aad de otra columna no descifra`() {
        val sobre = CifradoAes.cifrar(clave, aad, original)
        val resultado = CifradoAes.descifrar(clave, CifradoAes.aad("Lista", "ubicacion_cifrada"), sobre)
        assertEquals(ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION), resultado)
    }

    @Test
    fun `ciphertext alterado no descifra`() {
        val sobre = CifradoAes.cifrar(clave, aad, original)
        sobre[sobre.lastIndex] = (sobre.last().toInt() xor 0x01).toByte()
        val resultado = CifradoAes.descifrar(clave, aad, sobre)
        assertEquals(ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION), resultado)
    }

    @Test
    fun `clave distinta no descifra`() {
        val sobre = CifradoAes.cifrar(clave, aad, original)
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION),
            CifradoAes.descifrar(otraClave, aad, sobre),
        )
    }

    @Test
    fun `version desconocida es formato invalido`() {
        val sobre = CifradoAes.cifrar(clave, aad, original)
        sobre[0] = 0x02
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO),
            CifradoAes.descifrar(clave, aad, sobre),
        )
    }

    @Test
    fun `sobre corto es formato invalido`() {
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO),
            CifradoAes.descifrar(clave, aad, byteArrayOf(0x01, 0x00)),
        )
    }

    @Test
    fun `clave de longitud invalida se rechaza`() {
        assertEquals(
            ResultadoDescifrado.Fallo(MotivoCifrado.CLAVE_INVALIDA),
            CifradoAes.descifrar(SecretKeySpec(ByteArray(8), "AES"), aad, byteArrayOf(CifradoAes.VERSION) + ByteArray(39)),
        )
    }

    @Test
    fun `aad se compone de tabla y columna`() {
        assertEquals("Canal.url_stream_cifrada", aad)
    }
}
