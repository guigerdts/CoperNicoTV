package com.copernicotv.core.crypto

import java.security.GeneralSecurityException
import java.security.InvalidKeyException
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Motivo tipado de fallo de descifrado (DC-04). */
enum class MotivoCifrado {
    FORMATO_INVALIDO,
    CLAVE_INVALIDA,
    FALLO_AUTENTICACION,
}

/** Resultado de descifrar un valor AES-256-GCM. */
sealed interface ResultadoDescifrado {
    data class Ok(val bytes: ByteArray) : ResultadoDescifrado

    data class Fallo(val motivo: MotivoCifrado) : ResultadoDescifrado
}

/**
 * Cifrado de columnas URL con AES-256-GCM (DC-04).
 *
 * Formato exacto: `0x01` (versión de formato) + IV aleatorio de 12 bytes +
 * ciphertext + tag GCM de 16 bytes. El AAD es el nombre de tabla y columna
 * concatenados, de modo que un valor cifrado no puede moverse entre columnas.
 *
 * Recibe una [SecretKey] y no bytes: en Android la clave vive en Keystore y es
 * no exportable, así que nunca se extrae material de clave. En pruebas JVM se
 * inyecta una `SecretKeySpec` de 32 bytes.
 *
 * Nota de honestidad criptográfica: GCM no distingue un AAD incorrecto de un
 * ciphertext alterado; ambos producen el mismo fallo de autenticación. Por eso
 * [MotivoCifrado.FALLO_AUTENTICACION] no se traduce a `aad_incorrecto` ni a
 * `ciphertext_corrupto` sin evidencia adicional del llamador.
 */
object CifradoAes {
    const val VERSION: Byte = 0x01
    const val LONGITUD_IV = 12
    const val LONGITUD_TAG_BITS = 128
    private const val LONGITUD_TAG_BYTES = 16
    private const val MINIMO_SOBRE = 1 + LONGITUD_IV + LONGITUD_TAG_BYTES

    private val aleatorio = SecureRandom()

    /** Cifra [original] con [clave] y el AAD indicado. */
    fun cifrar(
        clave: SecretKey,
        aad: String,
        original: ByteArray,
    ): ByteArray {
        val iv = ByteArray(LONGITUD_IV).also(aleatorio::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, clave, GCMParameterSpec(LONGITUD_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return byteArrayOf(VERSION) + iv + cipher.doFinal(original)
    }

    /** Descifra un sobre producido por [cifrar]. */
    fun descifrar(
        clave: SecretKey,
        aad: String,
        sobre: ByteArray,
    ): ResultadoDescifrado {
        if (sobre.size < MINIMO_SOBRE || sobre[0] != VERSION) {
            return ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO)
        }
        val iv = sobre.copyOfRange(1, 1 + LONGITUD_IV)
        val cifrado = sobre.copyOfRange(1 + LONGITUD_IV, sobre.size)
        return try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, clave, GCMParameterSpec(LONGITUD_TAG_BITS, iv))
            cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
            ResultadoDescifrado.Ok(cipher.doFinal(cifrado))
        } catch (_: InvalidKeyException) {
            ResultadoDescifrado.Fallo(MotivoCifrado.CLAVE_INVALIDA)
        } catch (_: GeneralSecurityException) {
            ResultadoDescifrado.Fallo(MotivoCifrado.FALLO_AUTENTICACION)
        }
    }

    /** AAD canónico de una columna cifrada: `tabla.columna`. */
    fun aad(
        tabla: String,
        columna: String,
    ): String = "$tabla.$columna"
}
