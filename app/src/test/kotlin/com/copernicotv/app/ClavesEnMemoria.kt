package com.copernicotv.app

import com.copernicotv.app.crypto.ClavesApp
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/**
 * Claves deterministas para pruebas JVM.
 *
 * En producción las claves viven en Android Keystore y no son exportables; en
 * pruebas se inyecta material fijo para poder verificar cifrado y HMAC.
 */
class ClavesEnMemoria : ClavesApp {
    override val claveAes: SecretKey = SecretKeySpec(ByteArray(32) { it.toByte() }, "AES")

    override val claveHmac: SecretKey = SecretKeySpec(ByteArray(32) { (it * 3).toByte() }, "HmacSHA256")
}
