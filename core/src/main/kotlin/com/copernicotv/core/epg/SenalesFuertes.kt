package com.copernicotv.core.epg

/**
 * Señales fuertes persistidas de una Asociación (DC-06).
 *
 * `nombres` contiene los `display-name` normalizados con `normalizar_epg`;
 * `iconos` contiene el hexadecimal minúscula de `H_icono`. No se guarda ninguna
 * URL completa ni el icono canónico.
 */
data class SenalesFuertes(
    val nombres: List<String> = emptyList(),
    val iconos: List<String> = emptyList(),
) {
    fun aJson(): String = SenalesFuertesJson.serializar(this)

    companion object {
        fun desdeJson(texto: String?): SenalesFuertes = texto?.let { SenalesFuertesJson.parsear(it) } ?: SenalesFuertes()
    }
}

/**
 * Serialización JSON mínima y determinista de [SenalesFuertes].
 *
 * Se implementa sin dependencias para que `:core` siga siendo JVM puro. El
 * formato es el contrato persistido en `Asociacion.senales_fuertes`; no se
 * aceptan campos adicionales.
 */
object SenalesFuertesJson {
    fun serializar(senales: SenalesFuertes): String =
        buildString {
            append('{')
            append("\"nombres\":")
            append(arreglo(senales.nombres))
            append(',')
            append("\"iconos\":")
            append(arreglo(senales.iconos))
            append('}')
        }

    fun parsear(texto: String): SenalesFuertes? {
        val nombres = leerArreglo(texto, "nombres") ?: return null
        val iconos = leerArreglo(texto, "iconos") ?: return null
        return SenalesFuertes(nombres, iconos)
    }

    private fun arreglo(valores: List<String>): String = valores.joinToString(prefix = "[", postfix = "]", separator = ",") { cadena(it) }

    private fun cadena(valor: String): String =
        buildString {
            append('"')
            for (caracter in valor) {
                when (caracter) {
                    '"' -> append("\\\"")
                    '\\' -> append("\\\\")
                    '\b' -> append("\\b")
                    '\u000C' -> append("\\f")
                    '\n' -> append("\\n")
                    '\r' -> append("\\r")
                    '\t' -> append("\\t")
                    else ->
                        if (caracter < ' ') {
                            append("\\u")
                            append(caracter.code.toString(16).padStart(4, '0'))
                        } else {
                            append(caracter)
                        }
                }
            }
            append('"')
        }

    @Suppress("CyclomaticComplexMethod", "ReturnCount")
    private fun leerArreglo(
        texto: String,
        clave: String,
    ): List<String>? {
        val marca = "\"$clave\""
        val desde = texto.indexOf(marca)
        if (desde < 0) return null
        var indice = desde + marca.length
        while (indice < texto.length && texto[indice].isWhitespace()) indice++
        if (indice >= texto.length || texto[indice] != ':') return null
        indice++
        while (indice < texto.length && texto[indice].isWhitespace()) indice++
        if (indice >= texto.length || texto[indice] != '[') return null
        indice++
        val valores = mutableListOf<String>()
        while (true) {
            while (indice < texto.length && texto[indice].isWhitespace()) indice++
            if (indice >= texto.length) return null
            if (texto[indice] == ']') return valores
            if (texto[indice] != '"') return null
            val lectura = leerCadena(texto, indice) ?: return null
            valores += lectura.first
            indice = lectura.second
            while (indice < texto.length && texto[indice].isWhitespace()) indice++
            if (indice >= texto.length) return null
            when (texto[indice]) {
                ',' -> indice++
                ']' -> return valores
                else -> return null
            }
        }
    }

    @Suppress("CyclomaticComplexMethod", "NestedBlockDepth")
    private fun leerCadena(
        texto: String,
        inicio: Int,
    ): Pair<String, Int>? {
        val constructor = StringBuilder()
        var indice = inicio + 1
        while (indice < texto.length) {
            val caracter = texto[indice]
            when (caracter) {
                '"' -> return constructor.toString() to (indice + 1)
                '\\' -> {
                    if (indice + 1 >= texto.length) return null
                    when (texto[indice + 1]) {
                        '"' -> constructor.append('"')
                        '\\' -> constructor.append('\\')
                        '/' -> constructor.append('/')
                        'b' -> constructor.append('\b')
                        'f' -> constructor.append('\u000C')
                        'n' -> constructor.append('\n')
                        'r' -> constructor.append('\r')
                        't' -> constructor.append('\t')
                        'u' -> {
                            if (indice + 5 >= texto.length) return null
                            val codigo = texto.substring(indice + 2, indice + 6).toIntOrNull(16) ?: return null
                            constructor.append(codigo.toChar())
                            indice += 4
                        }
                        else -> return null
                    }
                    indice += 2
                }
                else -> {
                    constructor.append(caracter)
                    indice++
                }
            }
        }
        return null
    }
}
