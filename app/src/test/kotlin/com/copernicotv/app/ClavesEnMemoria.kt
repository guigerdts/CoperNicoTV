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

    private val hmacPorGeneracion = mutableMapOf<Int, SecretKey>()

    override fun contieneClaveHmac(generacion: Int): Boolean = hmacPorGeneracion.containsKey(generacion)

    override fun claveHmac(generacion: Int): SecretKey? = hmacPorGeneracion[generacion]

    override fun crearClaveHmac(generacion: Int): SecretKey =
        hmacPorGeneracion.getOrPut(generacion) {
            SecretKeySpec(ByteArray(32) { (it * 3 + generacion).toByte() }, "HmacSHA256")
        }
}
