package com.copernicotv.app.crypto

import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import java.util.concurrent.atomic.AtomicInteger
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

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

    private class AlmacenEnMemoria : AlmacenClaveDatos {
        var sobre: ByteArray? = null
        val guardados = AtomicInteger(0)

        override fun leer(): ByteArray? = sobre

        override fun guardar(sobre: ByteArray) {
            this.sobre = sobre.copyOf()
            guardados.incrementAndGet()
        }
    }

    @Test
    fun `la primera clave se genera una vez y se persiste envuelta`() {
        val almacen = AlmacenEnMemoria()
        val servicio = ClavesDatos(RaizConfigurable(ClavesEnMemoria().claveAes), almacen)

        val clave = servicio.clave()
        assertEquals(clave, servicio.clave(), "la segunda llamada devuelve la clave en memoria")
        assertEquals(1, almacen.guardados.get(), "el sobre se persiste una sola vez")

        val sobre = requireNotNull(almacen.sobre)
        assertEquals(CifradoAes.VERSION, sobre[0], "el sobre de la clave es un sobre 0x01 de la raíz")
        assertEquals(1 + 12 + 32 + 16, sobre.size)
    }

    @Test
    fun `el sobre persistido no contiene la clave en claro`() {
        val almacen = AlmacenEnMemoria()
        val servicio = ClavesDatos(RaizConfigurable(ClavesEnMemoria().claveAes), almacen)

        val clave = servicio.clave()
        val sobre = requireNotNull(almacen.sobre)
        assertFalse(sobre.toList().containsAll(clave.encoded.toList()))
        val sinRaiz = CifradoAes.descifrar(ClavesEnMemoria().claveAes, CifradoAes.aad("Otra", "columna"), sobre)
        assertTrue(sinRaiz is ResultadoDescifrado.Fallo, "sin el AAD correcto el sobre no revela la clave")
    }

    @Test
    fun `la desproteccion y el guardado son una unica operacion por camino`() {
        val almacen = AlmacenEnMemoria()
        val raiz = ClavesEnMemoria().claveAes
        val servicio = ClavesDatos(RaizConfigurable(raiz), almacen)

        servicio.clave()
        assertEquals(1, servicio.metricas.operacionesKeystore.get(), "generar+envolver es UNA op")
        assertTrue(servicio.metricas.msGeneracion.sum() >= 0)
        assertTrue(servicio.metricas.msProteccion.sum() >= 0)

        // Reinicio del proceso: nueva instancia del servicio, mismo almacen y raíz.
        val reinicio = ClavesDatos(RaizConfigurable(raiz), almacen)
        reinicio.clave()
        assertEquals(1, reinicio.metricas.operacionesKeystore.get(), "desproteger es UNA op")
        val sobre = requireNotNull(almacen.sobre)
        assertEquals(
            ResultadoDescifrado.Ok(servicio.clave().encoded),
            CifradoAes.descifrar(raiz, CifradoAes.aad("ClavesDatos", "envuelta"), sobre),
            "la clave desprotegida es la misma material de la sesion original",
        )
    }

    @Test
    fun `sobre corrupto se regenera sin lanzar excepcion`() {
        val almacen = AlmacenEnMemoria()
        val raiz = ClavesEnMemoria().claveAes
        val primera = ClavesDatos(RaizConfigurable(raiz), almacen)
        primera.clave()
        almacen.sobre!![almacen.sobre!!.lastIndex] = (almacen.sobre!!.last().toInt() xor 0x01).toByte()

        val segunda = ClavesDatos(RaizConfigurable(raiz), almacen)
        val nuevaClave = segunda.clave()
        assertEquals(1, segunda.metricas.operacionesKeystore.get(), "la regeneracion vuelve a envolver con la raiz")
        val sobreNuevo = requireNotNull(almacen.sobre)
        assertEquals(
            ResultadoDescifrado.Ok(nuevaClave.encoded),
            CifradoAes.descifrar(raiz, CifradoAes.aad("ClavesDatos", "envuelta"), sobreNuevo),
        )
    }

    @Test
    fun `clave raiz invalida se regenera y persiste bajo la raiz nueva`() {
        val almacen = AlmacenEnMemoria()
        // Primera sesión con una raíz; segunda con otra (raíz regenerada o
        // reemplazada en Keystore): la desprotección falla y se regenera.
        val primera = ClavesDatos(RaizConfigurable(SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")), almacen)
        primera.clave()
        val raizNueva = SecretKeySpec(ByteArray(32) { (it + 7).toByte() }, "AES")
        val segunda = ClavesDatos(RaizConfigurable(raizNueva), almacen)

        val nuevaClave = segunda.clave()
        val sobre = requireNotNull(almacen.sobre)
        assertEquals(
            ResultadoDescifrado.Ok(nuevaClave.encoded),
            CifradoAes.descifrar(raizNueva, CifradoAes.aad("ClavesDatos", "envuelta"), sobre),
        )
    }
}
