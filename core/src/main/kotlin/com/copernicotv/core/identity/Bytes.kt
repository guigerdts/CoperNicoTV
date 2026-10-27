package com.copernicotv.core.identity

import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import javax.crypto.Mac
import javax.crypto.SecretKey
import javax.crypto.spec.SecretKeySpec

/** Codificación UTF-8 sin BOM. */
internal fun utf8(valor: String): ByteArray = valor.toByteArray(StandardCharsets.UTF_8)

/** Entero sin signo de 32 bits en big-endian. */
internal fun uint32Be(valor: Int): ByteArray =
    byteArrayOf(
        ((valor ushr 24) and 0xFF).toByte(),
        ((valor ushr 16) and 0xFF).toByte(),
        ((valor ushr 8) and 0xFF).toByte(),
        (valor and 0xFF).toByte(),
    )

/**
 * Longitud en bytes UTF-8 de una cadena.
 *
 * El contrato mide longitudes en bytes, no en caracteres ni en code points.
 */
internal fun longitudUtf8(valor: String): Int = utf8(valor).size

/** Concatena fragmentos binarios. */
internal fun concatenar(partes: List<ByteArray>): ByteArray {
    val total = partes.sumOf { it.size }
    val salida = ByteArray(total)
    var posicion = 0
    for (parte in partes) {
        parte.copyInto(salida, posicion)
        posicion += parte.size
    }
    return salida
}

/** SHA-256 del arreglo de bytes. */
internal fun sha256(datos: ByteArray): ByteArray = MessageDigest.getInstance("SHA-256").digest(datos)

/** Hexadecimal en minúscula, longitud fija de 64 caracteres para SHA-256. */
internal fun hexMinuscula(datos: ByteArray): String {
    val digitos = "0123456789abcdef"
    val constructor = StringBuilder(datos.size * 2)
    for (byte in datos) {
        val valor = byte.toInt() and 0xFF
        constructor.append(digitos[valor ushr 4])
        constructor.append(digitos[valor and 0x0F])
    }
    return constructor.toString()
}

/** HMAC-SHA-256 con la clave indicada. */
fun hmacSha256(
    clave: ByteArray,
    entrada: ByteArray,
): ByteArray = hmacSha256(SecretKeySpec(clave, "HmacSHA256"), entrada)

/**
 * HMAC-SHA-256 con una [SecretKey] ya provista por la plataforma.
 *
 * En Android la clave HMAC vive en Keystore y no es exportable; este overload
 * permite firmar sin extraer material de clave.
 */
fun hmacSha256(
    clave: SecretKey,
    entrada: ByteArray,
): ByteArray {
    val mac = Mac.getInstance("HmacSHA256")
    mac.init(clave)
    return mac.doFinal(entrada)
}
