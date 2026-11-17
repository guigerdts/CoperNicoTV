package com.copernicotv.app.crypto

import com.copernicotv.app.AlmacenClaveDatosEnMemoria
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Pruebas del ciclo de vida de la clave de datos de sesión (E7-T7 ronda 8,
 * S2/S3/S4): generación CSPRNG, protección por la raíz, persistencia solo
 * envuelta, recuperación tras reinicio y regeneración ante sobre corrupto.
 */
class ClavesDatosTest {
    private class RaizConfigurable(
        override val claveAes: SecretKey,
    ) : ClavesApp {
        override fun contieneClaveHmac(generacion: Int): Boolean = false

        override fun claveHmac(generacion: Int): SecretKey? = null

        override fun crearClaveHmac(generacion: Int): SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "HmacSHA256")
    }

    @Test
    fun `la primera clave se genera una vez y se persiste envuelta`() {
        val almacen = AlmacenClaveDatosEnMemoria()
        val servicio = ClavesDatos(RaizConfigurable(ClavesEnMemoria().claveAes), almacen)

        val clave = servicio.clave()
        assertEquals("la segunda llamada devuelve la clave en memoria", clave, servicio.clave())
        assertEquals("el sobre se persiste una sola vez", 1, almacen.guardados.get())

        val sobre = requireNotNull(almacen.sobre)
        assertEquals("el sobre de la clave es un sobre 0x01 de la raíz", CifradoAes.VERSION, sobre[0])
        assertEquals(1 + 12 + 32 + 16, sobre.size)
    }

    @Test
    fun `el sobre persistido no contiene la clave en claro`() {
        val almacen = AlmacenClaveDatosEnMemoria()
        val servicio = ClavesDatos(RaizConfigurable(ClavesEnMemoria().claveAes), almacen)

        val clave = servicio.clave()
        val sobre = requireNotNull(almacen.sobre)
        assertFalse(sobre.toList().containsAll(clave.encoded.toList()))
        val sinRaiz = CifradoAes.descifrar(ClavesEnMemoria().claveAes, CifradoAes.aad("Otra", "columna"), sobre)
        assertTrue("sin el AAD correcto el sobre no revela la clave", sinRaiz is ResultadoDescifrado.Fallo)
    }

    @Test
    fun `la desproteccion y el guardado son una unica operacion por camino`() {
        val almacen = AlmacenClaveDatosEnMemoria()
        val raiz = ClavesEnMemoria().claveAes
        val servicio = ClavesDatos(RaizConfigurable(raiz), almacen)

        servicio.clave()
        assertEquals("generar+envolver es UNA op", 1, servicio.metricas.operacionesKeystore.get())
        assertTrue(servicio.metricas.msGeneracion() >= 0)
        assertTrue(servicio.metricas.msProteccion() >= 0)

        // Reinicio del proceso: nueva instancia del servicio, mismo almacen y raíz.
        val reinicio = ClavesDatos(RaizConfigurable(raiz), almacen)
        reinicio.clave()
        assertEquals("desproteger es UNA op", 1, reinicio.metricas.operacionesKeystore.get())
        val sobre = requireNotNull(almacen.sobre)
        val desprotegida =
            requireNotNull(CifradoAes.descifrar(raiz, CifradoAes.aad("ClavesDatos", "envuelta"), sobre) as? ResultadoDescifrado.Ok) {
                "esperaba Ok en la desproteccion"
            }
        assertArrayEquals(
            "la clave desprotegida es la misma material de la sesion original",
            servicio.clave().encoded,
            desprotegida.bytes,
        )
    }

    @Test
    fun `sobre corrupto se regenera sin lanzar excepcion`() {
        val almacen = AlmacenClaveDatosEnMemoria()
        val raiz = ClavesEnMemoria().claveAes
        val primera = ClavesDatos(RaizConfigurable(raiz), almacen)
        primera.clave()
        val envueltaCorrupta = requireNotNull(almacen.sobre)
        envueltaCorrupta[envueltaCorrupta.lastIndex] = (envueltaCorrupta.last().toInt() xor 0x01).toByte()

        val segunda = ClavesDatos(RaizConfigurable(raiz), almacen)
        val nuevaClave = segunda.clave()
        assertEquals(
            "la regeneracion gasta 2 ops de raiz: la desproteccion fallida y envolver la clave nueva",
            2,
            segunda.metricas.operacionesKeystore.get(),
        )
        val sobreNuevo = requireNotNull(almacen.sobre)
        val reenvuelta =
            requireNotNull(CifradoAes.descifrar(raiz, CifradoAes.aad("ClavesDatos", "envuelta"), sobreNuevo) as? ResultadoDescifrado.Ok) {
                "esperaba Ok en la reenvuelta"
            }
        assertArrayEquals(nuevaClave.encoded, reenvuelta.bytes)
    }

    @Test
    fun `clave raiz invalida se regenera y persiste bajo la raiz nueva`() {
        val almacen = AlmacenClaveDatosEnMemoria()
        // Primera sesión con una raíz; segunda con otra (raíz regenerada o
        // reemplazada en Keystore): la desprotección falla y se regenera.
        val primera = ClavesDatos(RaizConfigurable(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")), almacen)
        primera.clave()
        val raizNueva = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
        val segunda = ClavesDatos(RaizConfigurable(raizNueva), almacen)

        val nuevaClave = segunda.clave()
        val sobre = requireNotNull(almacen.sobre)
        val reenvuelta =
            requireNotNull(CifradoAes.descifrar(raizNueva, CifradoAes.aad("ClavesDatos", "envuelta"), sobre) as? ResultadoDescifrado.Ok) {
                "esperaba Ok en la reenvuelta"
            }
        assertArrayEquals(nuevaClave.encoded, reenvuelta.bytes)
    }

    @Test
    fun `la conversion de nanosegundos a milisegundos es la del informe`() {
        // Ronda 9 (S1): la unidad exacta del acceso en ms, probada con valores
        // deterministas; 999_999 ns todavía no es un milisegundo completo.
        assertEquals(5L, nanosAMs(5_000_000L))
        assertEquals(11L, nanosAMs(11_263_039L))
        assertEquals(0L, nanosAMs(999_999L))
    }

    @Test
    fun `las metricas del ciclo de la clave se reportan en escala de ms`() {
        // Ronda 9 (S1): el bug físico reportó tiempo_proteccion_clave_ms=
        // 11263039 (nanosegundos etiquetados como ms, ≈3,1 horas imposibles).
        // Un ciclo real de generar+envolver tarda mucho menos de un minuto:
        // si la unidad se rompe de nuevo, este techo lo detecta.
        val almacen = AlmacenClaveDatosEnMemoria()
        val servicio = ClavesDatos(RaizConfigurable(ClavesEnMemoria().claveAes), almacen)
        servicio.clave()

        assertEquals(
            "el acceso en ms es la conversion del acumulador en ns",
            servicio.metricas.nanosGeneracion.sum() / NANOS_POR_MS_CLAVE,
            servicio.metricas.msGeneracion(),
        )
        assertEquals(
            servicio.metricas.nanosProteccion.sum() / NANOS_POR_MS_CLAVE,
            servicio.metricas.msProteccion(),
        )
        assertTrue("proteccion en ms, no en ns", servicio.metricas.msProteccion() < 60_000)
    }
}
