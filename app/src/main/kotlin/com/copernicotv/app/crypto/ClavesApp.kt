package com.copernicotv.app.crypto

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Log
import com.copernicotv.core.crypto.CifradoAes
import java.security.GeneralSecurityException
import java.security.KeyStore
import java.security.ProviderException
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

    /**
     * ¿Existe la clave HMAC de la generación indicada? Es una lectura sola:
     * nunca crea, borra ni sustituye claves (DC-04 §5).
     */
    fun contieneClaveHmac(generacion: Int): Boolean

    /**
     * Clave HMAC de la generación solicitada sin regenerar: `null` si falta.
     *
     * La ausencia nunca genera una clave sustituta: eso dejaría valores nuevos
     * indistinguibles de los viejos y violaría la prohibición de DC-04 §5.
     * Quien detecta la ausencia transiciona el índice a `recuperacion_pendiente`
     * (Q2 del propietario) y bloquea las escrituras e igualdades dependientes.
     */
    fun claveHmac(generacion: Int): SecretKey?

    /**
     * Crea la clave HMAC de una generación, o devuelve la existente
     * (idempotente). Solo es la incorporación explícita de una generación
     * nueva: la primera inicialización legítima del índice o la reconstrucción
     * acordada. Nunca se invoca en respuesta a una ausencia detectada en una
     * escritura normal (DC-04 §5, Q2).
     *
     * Dos llamadas concurrentes para la misma generación deben devolver la
     * misma clave: la implementación serializa la comprobación y la creación
     * para que ninguna observe el alias vacío entre ambas.
     */
    fun crearClaveHmac(generacion: Int): SecretKey
}

/**
 * Implementación con Android Keystore.
 *
 * Alias versionados: `copernicotv.aes.v1` y `copernicotv.hmac.vN`. Cada
 * generación de la clave HMAC vive en su alias `copernicotv.hmac.<generacion>`,
 * de modo que los sobres antiguos (`hmac:vN:...`) siguen siendo verificables
 * con la clave que los produjo y una reconstrucción nunca mezcla material.
 *
 * El IV de AES-GCM lo genera `CifradoAes` (12 bytes de `SecureRandom` por
 * valor) porque el sobre `0x01 + IV + ciphertext + tag` es contrato de `:core`
 * y debe funcionar también con una `SecretKeySpec` de JVM. Por eso la clave de
 * Keystore se crea con `setRandomizedEncryptionRequired(false)`: sin eso,
 * Keystore rechaza el IV del llamador con `InvalidAlgorithmParameterException`
 * y la importación falla. La unicidad del IV la garantiza el generador
 * aleatorio, no Keystore. Desviación ratificada por el propietario (Q1,
 * decisión vinculante): se conserva la generación del IV por [CifradoAes], la
 * clave no exportable y el formato contractual del sobre.
 *
 * La anotación del constructor primario sigue la convención oficial de Kotlin
 * (`class Foo @Inject constructor()`). ktlint 1.0.1 exige la anotación en una
 * línea propia, así que se suprime esa única regla en esta clase.
 */
@Singleton
@Suppress("ktlint:standard:annotation")
class ClavesKeystore @Inject constructor() : ClavesApp {
    override val claveAes: SecretKey by lazy { cargarAes() }

    override fun contieneClaveHmac(generacion: Int): Boolean {
        val alias = aliasHmac(generacion)
        return synchronized(candadoHmac) {
            val almacen = almacen()
            almacen.containsAlias(alias) &&
                almacen.getEntry(alias, null) is KeyStore.SecretKeyEntry
        }
    }

    override fun claveHmac(generacion: Int): SecretKey? =
        synchronized(candadoHmac) {
            val existente =
                almacen().getEntry(aliasHmac(generacion), null) as? KeyStore.SecretKeyEntry
            existente?.secretKey
        }

    override fun crearClaveHmac(generacion: Int): SecretKey =
        synchronized(candadoHmac) {
            val alias = aliasHmac(generacion)
            val existente = almacen().getEntry(alias, null) as? KeyStore.SecretKeyEntry
            existente?.secretKey ?: generadorHmac(alias)
        }

    /**
     * Devuelve la clave AES vigente y la regenera si la existente no puede
     * producir el sobre de [CifradoAes].
     *
     * Una clave generada antes de `setRandomizedEncryptionRequired(false)`
     * nace con esa protección activada y Keystore rechaza cualquier IV del
     * llamador. El parámetro se fija al generar la clave: cambiar el
     * `KeyGenParameterSpec` no migra una clave ya existente. Sin esta
     * comprobación, un dispositivo que conserva la clave de una instalación
     * anterior falla en cada importación aunque el código ya esté corregido, y
     * el mensaje de la app solo dice `importacion_fallida`.
     *
     * Borrar la clave incompatible no destruye datos: todo cifrado de la
     * aplicación pasa por [CifradoAes], que siempre aporta su propio IV, así
     * que esa clave nunca pudo cifrar nada que la app pueda necesitar. Si en el
     * futuro un llamador deja que Keystore genere el IV, esta regeneración deja
     * de ser segura y debe revisarse.
     */
    private fun cargarAes(): SecretKey {
        val almacen = almacen()
        val existente = almacen.getEntry(ALIAS_AES, null) as? KeyStore.SecretKeyEntry
        if (existente != null) {
            if (admiteSobrePropio(existente.secretKey)) return existente.secretKey
            Log.w(TAG, "Clave AES $ALIAS_AES incompatible con el IV del llamador; se regenera")
            almacen.deleteEntry(ALIAS_AES)
        }
        return generadorAes(ALIAS_AES)
    }

    /** Comprueba el contrato real de la app: cifrar con IV propio, sin guardar nada. */
    private fun admiteSobrePropio(clave: SecretKey): Boolean =
        try {
            CifradoAes.cifrar(clave, AAD_SONDA, SONDA)
            true
        } catch (_: GeneralSecurityException) {
            false
        } catch (_: ProviderException) {
            false
        }

    private fun almacen(): KeyStore = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }

    private fun aliasHmac(generacion: Int): String {
        require(generacion >= 1) { "generación HMAC inválida" }
        return ALIAS_PREFIJO_HMAC + generacion
    }

    private fun generadorAes(alias: String): SecretKey {
        val generador = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE)
        generador.init(
            KeyGenParameterSpec
                .Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                // CifradoAes es dueño del IV: 12 bytes aleatorios por valor,
                // antepuestos al sobre. Keystore solo acepta IV del llamador si
                // se desactiva esta protección (activada por defecto).
                .setRandomizedEncryptionRequired(false)
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
        const val ALIAS_PREFIJO_HMAC = "copernicotv.hmac.v"
        const val TAG = "ClavesKeystore"
        const val AAD_SONDA = "ClavesKeystore.sonda"
        val SONDA = byteArrayOf(0)

        /**
         * Serializa lectura y creación de claves HMAC en todo el proceso.
         *
         * Keystore es un almacén único del proceso y `KeyGenerator.generateKey`
         * reemplaza una entrada existente: si dos inicializaciones leen "no
         * existe" antes de que la primera genere, ambas crean material distinto
         * y la última sobrescribe el alias, dejando sobres sellados con una
         * clave que ya no está en la bóveda. El candado debe cubrir también las
         * lecturas: así nadie observa el hueco entre la comprobación y el
         * `generateKey`. Es un monitor de JVM, no reentrante entre hilos de
         * corrutinas que solo hacen llamadas bloqueantes de Keystore; no se
         * mantiene a través de ninguna suspensión.
         */
        val candadoHmac = Any()
    }
}
