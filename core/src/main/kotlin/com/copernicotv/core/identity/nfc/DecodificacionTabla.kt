package com.copernicotv.core.identity.nfc

/**
 * Decodifica una tabla empaquetada en hexadecimal de ancho fijo.
 *
 * Las tablas generadas por `tools/unicode/generar_nfc.py` guardan cada valor en
 * [anchoCampo] dígitos hexadecimales y cada entrada en [anchoEntrada]. El
 * resultado aplana las entradas: una entrada de dos valores ocupa dos posiciones
 * consecutivas, de modo que las búsquedas usan un paso fijo.
 *
 * La decodificación ocurre una sola vez por tabla, de forma perezosa.
 *
 * @throws IllegalArgumentException si los anchos son incompatibles con [datos].
 */
internal fun decodificarTabla(
    datos: String,
    anchoEntrada: Int,
    anchoCampo: Int,
): IntArray {
    require(anchoCampo > 0) { "ancho de campo debe ser positivo" }
    require(anchoEntrada > 0 && anchoEntrada % anchoCampo == 0) {
        "ancho de entrada $anchoEntrada no es múltiplo de $anchoCampo"
    }
    require(datos.length % anchoEntrada == 0) { "datos con longitud no múltiplo de $anchoEntrada" }
    val cantidad = datos.length / anchoCampo
    val salida = IntArray(cantidad)
    var indice = 0
    while (indice < cantidad) {
        salida[indice] = datos.substring(indice * anchoCampo, (indice + 1) * anchoCampo).toInt(16)
        indice++
    }
    return salida
}
