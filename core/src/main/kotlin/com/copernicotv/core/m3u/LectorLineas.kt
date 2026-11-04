package com.copernicotv.core.m3u

import java.io.BufferedInputStream
import java.io.InputStream
import java.io.InputStreamReader
import java.io.Reader
import java.nio.charset.Charset

/** Una línea física leída del documento. */
internal sealed interface LineaLeida {
    data class Ok(val texto: String) : LineaLeida

    /**
     * La línea superó el máximo configurable. [prefijo] conserva lo leído hasta
     * el límite para que el parser reconozca un `#EXTINF` truncado.
     */
    data class Excedida(val prefijo: String) : LineaLeida

    data object Fin : LineaLeida
}

/**
 * Lector de líneas físicas con cota de memoria por línea.
 *
 * Reconoce `CRLF`, `LF` y `CR` mezclados, descarta el BOM inicial y no acumula
 * más allá del máximo: al excederlo deja de almacenar y sigue consumiendo hasta
 * el fin de línea, de modo que el parser pueda resincronizarse.
 */
internal interface LectorLineas {
    fun siguiente(): LineaLeida

    companion object {
        private const val TAMANO_BLOQUE = 8192

        fun crear(
            entrada: InputStream,
            maximo: Int,
        ): LectorLineas {
            val almacenada = if (entrada is BufferedInputStream) entrada else BufferedInputStream(entrada)
            val charset = detectarCharset(almacenada)
            return if (charset == Charsets.UTF_8) {
                saltarBomUtf8(almacenada)
                LectorBytesUtf8(almacenada, maximo, TAMANO_BLOQUE)
            } else {
                LectorChars(InputStreamReader(almacenada, charset), maximo, TAMANO_BLOQUE)
            }
        }

        private fun detectarCharset(entrada: BufferedInputStream): Charset {
            entrada.mark(3)
            val primero = entrada.read()
            val segundo = entrada.read()
            entrada.reset()
            return when {
                primero == 0xFF && segundo == 0xFE -> Charsets.UTF_16LE
                primero == 0xFE && segundo == 0xFF -> Charsets.UTF_16BE
                else -> Charsets.UTF_8
            }
        }

        private fun saltarBomUtf8(entrada: BufferedInputStream) {
            entrada.mark(3)
            val bom = ByteArray(3)
            var leidos = 0
            while (leidos < bom.size) {
                val cantidad = entrada.read(bom, leidos, bom.size - leidos)
                if (cantidad <= 0) break
                leidos += cantidad
            }
            val esBom =
                leidos == bom.size &&
                    bom[0] == 0xEF.toByte() &&
                    bom[1] == 0xBB.toByte() &&
                    bom[2] == 0xBF.toByte()
            if (!esBom) entrada.reset()
        }
    }
}

/** Lector de bytes para UTF-8: el máximo se cuenta en bytes, como el contrato. */
private class LectorBytesUtf8(
    private val entrada: InputStream,
    private val maximo: Int,
    tamanoBloque: Int,
) : LectorLineas {
    private val buffer = ByteArray(tamanoBloque)
    private val acumulado = ByteArray(maximo + 1)
    private var posicion = 0
    private var limite = 0
    private var agotado = false
    private var longitud = 0
    private var excedida = false

    override fun siguiente(): LineaLeida {
        longitud = 0
        excedida = false
        var algunByte = false
        while (true) {
            if (posicion >= limite && !recargar()) {
                return if (algunByte) terminar() else LineaLeida.Fin
            }
            val byte = buffer[posicion++]
            algunByte = true
            when (byte) {
                SALTO_LINEA -> return terminar()
                RETORNO_CARRO -> {
                    consumirSaltoOpcional()
                    return terminar()
                }
                else -> acumular(byte)
            }
        }
    }

    private fun recargar(): Boolean {
        if (agotado) return false
        val leidos = entrada.read(buffer, 0, buffer.size)
        if (leidos <= 0) {
            agotado = true
            return false
        }
        posicion = 0
        limite = leidos
        return true
    }

    private fun consumirSaltoOpcional() {
        if (posicion >= limite && !recargar()) return
        if (posicion < limite && buffer[posicion] == SALTO_LINEA) posicion++
    }

    private fun acumular(byte: Byte) {
        if (excedida) return
        if (longitud < maximo) {
            acumulado[longitud++] = byte
        } else {
            excedida = true
        }
    }

    private fun terminar(): LineaLeida =
        if (excedida) {
            LineaLeida.Excedida(String(acumulado, 0, longitud, Charsets.UTF_8))
        } else {
            LineaLeida.Ok(String(acumulado, 0, longitud, Charsets.UTF_8))
        }

    private companion object {
        const val SALTO_LINEA: Byte = '\n'.code.toByte()
        const val RETORNO_CARRO: Byte = '\r'.code.toByte()
    }
}

/** Lector de caracteres para UTF-16 con BOM; el máximo se cuenta en caracteres. */
private class LectorChars(
    private val lector: Reader,
    private val maximo: Int,
    tamanoBloque: Int,
) : LectorLineas {
    private val buffer = CharArray(tamanoBloque)
    private val acumulado = StringBuilder()
    private var posicion = 0
    private var limite = 0
    private var agotado = false
    private var excedida = false
    private var inicio = true

    override fun siguiente(): LineaLeida {
        if (inicio) {
            inicio = false
            descartarBom()
        }
        acumulado.setLength(0)
        excedida = false
        var algunCaracter = false
        while (true) {
            if (posicion >= limite && !recargar()) {
                return if (algunCaracter) terminar() else LineaLeida.Fin
            }
            val caracter = buffer[posicion++]
            algunCaracter = true
            when (caracter) {
                '\n' -> return terminar()
                '\r' -> {
                    consumirSaltoOpcional()
                    return terminar()
                }
                else -> acumular(caracter)
            }
        }
    }

    private fun descartarBom() {
        if (posicion >= limite) recargar()
        if (posicion < limite && buffer[posicion] == '\uFEFF') posicion++
    }

    private fun recargar(): Boolean {
        if (agotado) return false
        val leidos = lector.read(buffer, 0, buffer.size)
        if (leidos <= 0) {
            agotado = true
            return false
        }
        posicion = 0
        limite = leidos
        return true
    }

    private fun consumirSaltoOpcional() {
        if (posicion >= limite && !recargar()) return
        if (posicion < limite && buffer[posicion] == '\n') posicion++
    }

    private fun acumular(caracter: Char) {
        if (excedida) return
        if (acumulado.length < maximo) {
            acumulado.append(caracter)
        } else {
            excedida = true
        }
    }

    private fun terminar(): LineaLeida =
        if (excedida) {
            LineaLeida.Excedida(acumulado.toString())
        } else {
            LineaLeida.Ok(acumulado.toString())
        }
}
