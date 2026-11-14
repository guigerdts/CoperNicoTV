package com.copernicotv.app.crypto

import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.ClaseUrl
import com.copernicotv.core.identity.ResultadoSobre
import com.copernicotv.core.identity.SobreHmac
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Claves operativas de sesión (E7-T7 ronda 6, S44/S45): derivación correcta,
 * reuso real por raíz (1 derivación aunque se pida N veces, incluida
 * concurrencia), rotación por generación e integridad del formato de sobre.
 */
class ClavesOperativasTest {
    @Test
    fun `deriva la operativa hmac de la raiz con el dominio dedicado`() {
        val raiz = raiz(1)

        val operativa = ClavesOperativas().hmacOperativa(raiz)

        val esperada =
            Mac.getInstance("HmacSHA256").run {
                init(raiz)
                doFinal("copernicotv/hmac/operativa/v1".toByteArray(Charsets.UTF_8))
            }
        assertArrayEquals(esperada, requireNotNull(operativa.encoded))
        // La operativa no es la raíz: material distinto, misma algorithmia.
        assertFalse(operativa == raiz)
        assertEquals("HmacSHA256", operativa.algorithm)
    }

    @Test
    fun `reutiliza la derivacion por raiz aunque se pida muchas veces`() {
        val operativas = ClavesOperativas()
        val raiz = raiz(1)

        val primera = operativas.hmacOperativa(raiz)
        val repetidas = List(50) { operativas.hmacOperativa(raiz) }

        repetidas.forEachIndexed { indice, clave ->
            assertSame("instancia $indice distinta", primera, clave)
        }
    }

    @Test
    fun `reutiliza la derivacion por material igual de SecretKeySpec`() {
        val operativas = ClavesOperativas()

        val primera = operativas.hmacOperativa(raiz(3))
        val conIgualMaterial = operativas.hmacOperativa(raiz(3))

        assertSame(primera, conIgualMaterial)
    }

    @Test
    fun `rotacion de generacion produce operativas distintas`() {
        val operativas = ClavesOperativas()

        val deGeneracionUno = operativas.hmacOperativa(raiz(1))
        val deGeneracionDos = operativas.hmacOperativa(raiz(2))

        assertNotEquals(deGeneracionUno.encoded, deGeneracionDos.encoded)
        val mensaje = "https://ejemplo.com/canal.ts".toByteArray()
        val macUno = mac(deGeneracionUno, mensaje)
        val macDos = mac(deGeneracionDos, mensaje)
        assertFalse(macUno.contentEquals(macDos))
    }

    @Test
    fun `la derivacion concurrente por raiz produce una sola operativa`() =
        runBlocking {
            val operativas = ClavesOperativas()
            val raiz = raiz(7)

            val resultados = (1..16).map { async { operativas.hmacOperativa(raiz) } }.awaitAll()

            resultados.forEach { clave -> assertSame(resultados.first(), clave) }
        }

    @Test
    fun `el sobre sellado con la operativa mantiene el formato versionado`() {
        val operativas = ClavesOperativas()
        val operativa = operativas.hmacOperativa(raiz(1))

        val sobre =
            HmacUrl.calcularSobre(
                clave = operativa,
                generacion = 1,
                campo = CampoUrl.CANAL_URL_STREAM,
                clase = ClaseUrl.HTTP_HTTPS,
                urlCanonica = "https://ejemplo.com/canal.ts",
            )

        assertTrue("prefijo inesperado: $sobre", sobre.startsWith("hmac:v1:cv"))
        assertTrue(SobreHmac.parsear(sobre) is ResultadoSobre.Versionado)
    }

    private fun raiz(generacion: Int): SecretKeySpec = SecretKeySpec(ByteArray(32) { (it * 5 + generacion).toByte() }, "HmacSHA256")

    private fun mac(
        clave: SecretKey,
        mensaje: ByteArray,
    ): ByteArray =
        Mac.getInstance("HmacSHA256").run {
            init(clave)
            doFinal(mensaje)
        }
}
