package com.copernicotv.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.security.KeyStore
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Claves de cifrado y de HMAC de la aplicación (DC-04).
 *
 * Se exponen como [SecretKey] y nunca como bytes: las claves de Android
 * Keystore son no exportables. El código de `:core` acepta `SecretKey`
 * precisamente para no extraer material de clave.
 */
interface ClavesApp {
    val claveAes: SecretKey
    val claveHmac: SecretKey
}

/**
 * Implementación con Android Keystore.
 *
 * Alias versionados: `copernicotv.aes.v1` y `copernicotv.hmac.v1`. La rotación
 * futura crea `v2` y el sobre HMAC registra `vN`, de modo que las filas viejas
 * siguen siendo verificables con la generación que las produjo.
 */
@Singleton
class ClavesKeystore @Inject constructor() : ClavesApp {
    override val claveAes: SecretKey
        get() = clave(ALIAS_AES) { generadorAes(it) }

    override val claveHmac: SecretKey
        get() = clave(ALIAS_HMAC) { generadorHmac(it) }

    private fun clave(
        alias: String,
        generar: (String) -> SecretKey,
    ): SecretKey {
        val almacen = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        val existente = almacen.getEntry(alias, null) as? KeyStore.SecretKeyEntry
        return existente?.secretKey ?: generar(alias)
    }

    private fun generadorAes(alias: String): SecretKey {
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generador.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return generador.generateKey()
    }

    private fun generadorHmac(alias: String): SecretKey {
        val generador =
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_HMAC_SHA256, ANDROID_KEYSTORE)
        generador.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_SIGN)
                .build(),
        )
        return generador.generateKey()
    }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val ALIAS_AES = "copernicotv.aes.v1"
        const val ALIAS_HMAC = "copernicotv.hmac.v1"
    }
}
