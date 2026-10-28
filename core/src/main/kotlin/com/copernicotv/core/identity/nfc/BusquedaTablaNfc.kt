package com.copernicotv.core.identity.nfc

/**
 * Búsquedas binarias sobre las tablas NFC generadas.
 *
 * Las tablas están ordenadas por su primera columna, de modo que cada consulta
 * es `O(log n)` sin asignar memoria. Se exponen índices, no valores, para que
 * [com.copernicotv.core.identity.Nfc151] lea las columnas que necesita sin
 * construir objetos intermedios.
 */
internal object BusquedaTablaNfc {
    /** CCC de [punto], o 0 si el code point no tiene clase distinta de 0. */
    fun ccc(punto: Int): Int {
        val indice = indiceDeClave(TablaCcc.pares, punto, 2)
        return if (indice < 0) 0 else TablaCcc.pares[indice * 2 + 1]
    }

    /** Índice de [clave] en una tabla de filas de [ancho] enteros, o -1. */
    fun indiceDeClave(
        entradas: IntArray,
        clave: Int,
        ancho: Int,
    ): Int {
        var bajo = 0
        var alto = entradas.size / ancho - 1
        while (bajo <= alto) {
            val medio = (bajo + alto) ushr 1
            val valor = entradas[medio * ancho]
            when {
                valor < clave -> bajo = medio + 1
                valor > clave -> alto = medio - 1
                else -> return medio
            }
        }
        return -1
    }

    /** Índice del par ([primero], [segundo]) en la tabla de composición, o -1. */
    fun indiceDeComposicion(
        entradas: IntArray,
        primero: Int,
        segundo: Int,
    ): Int {
        var bajo = 0
        var alto = entradas.size / 3 - 1
        while (bajo <= alto) {
            val medio = (bajo + alto) ushr 1
            val base = medio * 3
            val actualPrimero = entradas[base]
            val actualSegundo = entradas[base + 1]
            val comparacion =
                when {
                    actualPrimero != primero -> actualPrimero.compareTo(primero)
                    else -> actualSegundo.compareTo(segundo)
                }
            when {
                comparacion < 0 -> bajo = medio + 1
                comparacion > 0 -> alto = medio - 1
                else -> return medio
            }
        }
        return -1
    }
}
