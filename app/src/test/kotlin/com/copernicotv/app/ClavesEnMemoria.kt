package com.copernicotv.app

import com.copernicotv.app.crypto.ClavesApp
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Claves deterministas para pruebas JVM.
 *
 * En producción las claves viven en Android Keystore y no son exportables; en
 * pruebas se inyecta material fijo para poder verificar cifrado y HMAC.
 *
 * La clave HMAC solo existe si el test la crea explícitamente con
 * [crearClaveHmac]: así las pruebas de pérdida de clave (Q2) usan la misma
 * semántica que Keystore — [claveHmac] jamás genera una clave sustituta.
 */
class ClavesEnMemoria : ClavesApp {
    override val claveAes: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    private val candado = Any()
    private val hmacPorGeneracion = mutableMapOf<Int, SecretKey>()
    private val creacionesPorGeneracion = mutableMapOf<Int, Int>()

    override fun contieneClaveHmac(generacion: Int): Boolean = synchronized(candado) { hmacPorGeneracion.containsKey(generacion) }

    override fun claveHmac(generacion: Int): SecretKey? = synchronized(candado) { hmacPorGeneracion[generacion] }

    override fun crearClaveHmac(generacion: Int): SecretKey =
        synchronized(candado) {
            hmacPorGeneracion.getOrPut(generacion) {
                creacionesPorGeneracion[generacion] = (creacionesPorGeneracion[generacion] ?: 0) + 1
                SecretKeySpec(ByteArray(32) { (it * 3 + generacion).toByte() }, "HmacSHA256")
            }
        }

    /**
     * Elimina la clave HMAC de una generación para simular su pérdida.
     *
     * Solo existe en el doble de pruebas: producción nunca expone una operación
     * para borrar claves.
     */
    fun borrarClaveHmac(generacion: Int) {
        synchronized(candado) { hmacPorGeneracion.remove(generacion) }
    }

    /** Cantidad de veces que se materializó una clave para [generacion]; solo pruebas. */
    fun creacionesHmac(generacion: Int): Int = synchronized(candado) { creacionesPorGeneracion[generacion] ?: 0 }
}
