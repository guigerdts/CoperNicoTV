package com.copernicotv.core.m3u

import com.copernicotv.core.identity.AtributoExtra

/**
 * Códec determinista de los atributos extra de un Canal (E2-T4).
 *
 * El formato es texto plano ordenado por `(clave, valor)` y separado por
 * saltos de línea: `clave=valor`. Se escapan `\`, `=`, CR y LF para que el
 * resultado sea reversible y estable entre importaciones equivalentes.
 *
 * Una lista vacía se codifica como `null`: la columna queda ausente y la
 * ausencia de atributos no rompe la lectura.
 */
object CodecAtributosExtra {
    /** Codifica [atributos] de forma determinista, o `null` si no hay ninguno. */
    fun codificar(atributos: List<AtributoExtra>): String? {
        if (atributos.isEmpty()) return null
        return atributos
            .sortedWith(compareBy({ it.clave }, { it.valor }))
            .joinToString("\n") { escapar(it.clave) + "=" + escapar(it.valor) }
    }

    /**
     * Decodifica el texto persistido.
     *
     * Tolera texto vacío o `null` y descarta líneas sin separador: un dato
     * corrupto no impide mostrar el resto de los atributos.
     */
    fun decodificar(texto: String?): List<AtributoExtra> {
        if (texto.isNullOrEmpty()) return emptyList()
        val atributos = mutableListOf<AtributoExtra>()
        for (linea in texto.split("\n")) {
            val corte = indiceSeparador(linea)
            if (corte < 0) continue
            atributos +=
                AtributoExtra(
                    clave = desescapar(linea.substring(0, corte)),
                    valor = desescapar(linea.substring(corte + 1)),
                )
        }
        return atributos
    }

    private fun indiceSeparador(linea: String): Int {
        var escapado = false
        linea.forEachIndexed { indice, caracter ->
            when {
                escapado -> escapado = false
                caracter == '\\' -> escapado = true
                caracter == '=' -> return indice
            }
        }
        return -1
    }

    private fun escapar(valor: String): String {
        val salida = StringBuilder(valor.length)
        for (caracter in valor) {
            when (caracter) {
                '\\' -> salida.append("\\\\")
                '=' -> salida.append("\\=")
                '\n' -> salida.append("\\n")
                '\r' -> salida.append("\\r")
                else -> salida.append(caracter)
            }
        }
        return salida.toString()
    }

    private fun desescapar(valor: String): String {
        val salida = StringBuilder(valor.length)
        var indice = 0
        while (indice < valor.length) {
            val caracter = valor[indice]
            if (caracter != '\\' || indice == valor.length - 1) {
                salida.append(caracter)
                indice += 1
                continue
            }
            when (val siguiente = valor[indice + 1]) {
                'n' -> salida.append('\n')
                'r' -> salida.append('\r')
                '\\' -> salida.append('\\')
                '=' -> salida.append('=')
                else -> salida.append('\\').append(siguiente)
            }
            indice += 2
        }
        return salida.toString()
    }
}
