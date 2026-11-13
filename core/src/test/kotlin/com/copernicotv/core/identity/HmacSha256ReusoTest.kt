package com.copernicotv.core.identity

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals

/**
 * Regresión del reuso de `Mac` en [hmacSha256] (E7-T7, L20).
 *
 * Cada operación debe producir EXACTAMENTE el mismo HMAC que una instancia
 * fresca: el reuso por hilo solo evita `Mac.getInstance` + `init` repetidos,
 * nunca cambia el resultado. Los casos cubren reuso con la misma clave,
 * reinicialización al cambiar de clave y aislamiento entre hilos.
 */
class HmacSha256ReusoTest {
    private fun clave(relleno: Byte): SecretKeySpec = SecretKeySpec(ByteArray(32) { relleno }, "HmacSHA256")

    private fun hmacFresco(
        clave: SecretKeySpec,
        entrada: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").apply {
            init(clave)
        }.doFinal(entrada)

    @Test
    fun `hmac repetido con la misma clave coincide con instancia fresca`() {
        val clave = clave(0x11)
        val entrada = "https://host/live/user/pass/1.ts".toByteArray(Charsets.UTF_8)
        val esperado = hmacFresco(clave, entrada)
        repeat(5) { assertContentEquals(esperado, hmacSha256(clave, entrada)) }
    }

    @Test
    fun `cambio de clave reinicializa el mac`() {
        val a = clave(0x22)
        val b = clave(0x33)
        val entrada = "entrada".toByteArray()
        assertContentEquals(hmacFresco(a, entrada), hmacSha256(a, entrada))
        assertContentEquals(hmacFresco(b, entrada), hmacSha256(b, entrada))
        // Volver a la clave A tras la B: el reuso debe reinicializar de nuevo.
        assertContentEquals(hmacFresco(a, entrada), hmacSha256(a, entrada))
    }

    @Test
    fun `overload de bytearray produce el mismo hmac`() {
        val material = ByteArray(32) { 0x44 }
        val entrada = "otra".toByteArray()
        val esperado = hmacFresco(SecretKeySpec(material, "HmacSHA256"), entrada)
        repeat(3) { assertContentEquals(esperado, hmacSha256(material, entrada)) }
    }

    @Test
    fun `hilos distintos producen el mismo hmac`() {
        val clave = clave(0x55)
        val entrada = "url-canonica".toByteArray()
        val esperado = hmacFresco(clave, entrada)
        val resultados =
            runBlocking {
                // Dispatchers.Default corre en hilos de trabajo reales: aunque
                // no garantiza un hilo distinto por async, la igualdad del
                // HMAC entre ejecuciones concurrentes prueba el aislamiento
                // por hilo sin hilos manuales.
                List(2) { async(Dispatchers.Default) { hmacSha256(clave, entrada) } }.awaitAll()
            }
        for (resultado in resultados) {
            assertContentEquals(esperado, resultado)
        }
    }
}
