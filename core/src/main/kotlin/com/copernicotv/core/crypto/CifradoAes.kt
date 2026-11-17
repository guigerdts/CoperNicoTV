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

    /**
     * Sobre de la clave de datos de sesión (E7-T7 ronda 8): mismo layout que
     * `VERSION`, distinto byte inicial. Los valores de datos se cifran con la
     * clave de datos; la raíz de Keystore solo protege esa clave.
     */
    const val VERSION_DATOS: Byte = 0x02
    const val LONGITUD_IV = 12
    const val LONGITUD_TAG_BITS = 128
    private const val LONGITUD_TAG_BYTES = 16
    private const val MINIMO_SOBRE = 1 + LONGITUD_IV + LONGITUD_TAG_BYTES

    private val aleatorio = SecureRandom()
    private val cipherPorHilo = ThreadLocal<Cipher?>()

    private fun cipherActual(): Cipher {
        var cipher = cipherPorHilo.get()
        if (cipher == null) {
            cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipherPorHilo.set(cipher)
        }
        return cipher
    }

    /** Cifra [original] con [clave] y el AAD indicado (sobre `0x01`). */
    fun cifrar(
        clave: SecretKey,
        aad: String,
        original: ByteArray,
    ): ByteArray = cifrarCon(clave, aad, original, VERSION)

    /**
     * Cifra [original] con la clave de datos de sesión (sobre `0x02`).
     *
     * [claveDatos] es una clave de software generada por CSPRNG y protegida
     * por la raíz: cifrar registros con ella no toca Android Keystore (ronda
     * 8). El IV sigue siendo nuevo por operación.
     */
    fun cifrarDatos(
        claveDatos: SecretKey,
        aad: String,
        original: ByteArray,
    ): ByteArray = cifrarCon(claveDatos, aad, original, VERSION_DATOS)

    /** Descifra un sobre producido por [cifrar] (estrictamente `0x01`). */
    fun descifrar(
        clave: SecretKey,
        aad: String,
        sobre: ByteArray,
    ): ResultadoDescifrado {
        if (sobre.size < MINIMO_SOBRE || sobre[0] != VERSION) {
            return ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO)
        }
        return descifrarCon(clave, aad, sobre)
    }

    /**
     * Descifra un sobre despachando por su byte de versión: `0x01` con
     * [claveRaiz] (registros legacy) y `0x02` con [claveDatos].
     *
     * Con [claveDatos] nula y un sobre `0x02` el fallo es
     * [MotivoCifrado.CLAVE_INVALIDA]: el formato está bien, la clave que lo
     * produce no está disponible (recuperación).
     */
    fun descifrarSobre(
        claveRaiz: SecretKey,
        claveDatos: SecretKey?,
        aad: String,
        sobre: ByteArray,
    ): ResultadoDescifrado {
        if (sobre.size < MINIMO_SOBRE) {
            return ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO)
        }
        return when (sobre[0]) {
            VERSION -> descifrarCon(claveRaiz, aad, sobre)
            VERSION_DATOS ->
                if (claveDatos == null) {
                    ResultadoDescifrado.Fallo(MotivoCifrado.CLAVE_INVALIDA)
                } else {
                    descifrarCon(claveDatos, aad, sobre)
                }
            else -> ResultadoDescifrado.Fallo(MotivoCifrado.FORMATO_INVALIDO)
        }
    }

    /** Escritura del sobre con el byte de [version] indicado. */
    private fun cifrarCon(
        clave: SecretKey,
        aad: String,
        original: ByteArray,
        version: Byte,
    ): ByteArray {
        val iv = ByteArray(LONGITUD_IV).also(aleatorio::nextBytes)
        // GCM exige un `init` por operación (IV nuevo cada vez); lo que se
        // reusa es la instancia, evitando `Cipher.getInstance` + búsqueda de
        // provider por valor (E7-T7, L20). No debilita el cifrado.
        val cipher = cipherActual()
        cipher.init(Cipher.ENCRYPT_MODE, clave, GCMParameterSpec(LONGITUD_TAG_BITS, iv))
        cipher.updateAAD(aad.toByteArray(Charsets.UTF_8))
        return byteArrayOf(version) + iv + cipher.doFinal(original)
    }

    /** Lectura de un sobre de la [clave] dada, con verificación GCM. */
    private fun descifrarCon(
        clave: SecretKey,
        aad: String,
        sobre: ByteArray,
    ): ResultadoDescifrado {
        val iv = sobre.copyOfRange(1, 1 + LONGITUD_IV)
        val cifrado = sobre.copyOfRange(1 + LONGITUD_IV, sobre.size)
        return try {
            val cipher = cipherActual()
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
