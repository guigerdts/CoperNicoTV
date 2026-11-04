package com.copernicotv.core.identity

import com.copernicotv.core.identity.casefold.TablaCaseFold

/**
 * Case folding completo de Unicode 15.1.0, sin locale (estados C y F).
 *
 * Es la implementación conforme a [VERSION_UNICODE_NORMATIVA] que el contrato
 * de identidad exige inyectar en [PerfilNormalizacion]. No delega en
 * `String.lowercase`, `Character.toLowerCase` ni en la versión Unicode del
 * dispositivo: la tabla se generó desde `CaseFolding.txt` 15.1.0 y viaja con el
 * binario, de modo que el resultado es idéntico en JVM, Android viejo y Android
 * nuevo.
 *
 * Los estados `S` (simple) y `T` (turco) se excluyen a propósito: `S` es una
 * variante de un carácter que ya tiene mapeo completo, y `T` introduciría
 * comportamiento dependiente de locale. Por eso `fold("I")` es `"i"` y no `"ı"`.
 *
 * Los surrogates no emparejados se propagan sin cambios; [PerfilNormalizacion]
 * los rechaza antes y después del plegado.
 */
object CaseFolderCompleto : CaseFolder {
    /** Versión Unicode de la tabla. */
    const val VERSION_UNICODE: String = "15.1.0"

    /** Origen oficial de la tabla. */
    const val FUENTE: String = "https://www.unicode.org/Public/15.1.0/ucd/CaseFolding.txt"

    /** SHA-256 del `CaseFolding.txt` 15.1.0 desde el que se generó la tabla. */
    const val SHA256_FUENTE: String = "4e55acfdc32825a22e87670e9056a3bf94ad7c5400065778e9e10f8314372bcf"

    /** Cantidad de code points con mapeo C o F. */
    const val ENTRADAS: Int = 1530

    override fun fold(valor: String): String {
        val salida = StringBuilder(valor.length)
        var indice = 0
        while (indice < valor.length) {
            val punto = valor.codePointAt(indice)
            val mapeo = TablaCaseFold.entradas[punto]
            if (mapeo == null) {
                salida.appendCodePoint(punto)
            } else {
                salida.append(mapeo)
            }
            indice += Character.charCount(punto)
        }
        return salida.toString()
    }
}
