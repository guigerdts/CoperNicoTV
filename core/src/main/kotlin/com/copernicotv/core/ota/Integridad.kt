package com.copernicotv.core.ota

import java.io.InputStream
import java.security.MessageDigest

/** SHA-256 y comparación de huellas hexadecimales. */
object Integridad {
    private const val TAMANO_BUFFER = 64 * 1024

    /** Huella SHA-256 en hexadecimal minúscula de [datos]. */
    fun sha256Hex(datos: ByteArray): String = hexadecimal(digest().digest(datos))

    /**
     * Huella SHA-256 en hexadecimal minúscula de [entrada], leída por bloques.
     *
     * El APK se verifica sin cargarlo completo en memoria: puede pesar decenas
     * de MiB y el dispositivo donde corre la app no siempre tiene holgura.
     */
    fun sha256Hex(entrada: InputStream): String {
        val digest = digest()
        val buffer = ByteArray(TAMANO_BUFFER)
        while (true) {
            val leidos = entrada.read(buffer)
            if (leidos < 0) break
            if (leidos > 0) digest.update(buffer, 0, leidos)
        }
        return hexadecimal(digest.digest())
    }

    /**
     * Compara dos huellas hexadecimales ignorando mayúsculas y sin cortar en el
     * primer byte distinto. Ambas deben tener la misma longitud.
     */
    fun iguales(
        a: String,
        b: String,
    ): Boolean {
        if (a.length != b.length) return false
        var diferencia = 0
        for (indice in a.indices) {
            val izquierda = a[indice].lowercaseChar().code
            val derecha = b[indice].lowercaseChar().code
            diferencia = diferencia or (izquierda xor derecha)
        }
        return diferencia == 0
    }

    private fun digest(): MessageDigest = MessageDigest.getInstance("SHA-256")

    private fun hexadecimal(bytes: ByteArray): String {
        val salida = StringBuilder(bytes.size * 2)
        for (byte in bytes) {
            val valor = byte.toInt() and 0xFF
            salida.append(HEX[valor ushr 4])
            salida.append(HEX[valor and 0x0F])
        }
        return salida.toString()
    }

    private const val HEX = "0123456789abcdef"
}
