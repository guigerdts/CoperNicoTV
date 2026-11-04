package com.copernicotv.core.m3u

import com.copernicotv.core.identity.DeclaracionCanal

/** Error global del parser canario. No es el parser tolerante de E2. */
enum class ErrorCanarioM3U(val codigo: String) {
    VACIO("m3u_vacio"),
    SIN_CABECERA("m3u_sin_cabecera"),
}

/** Declaración M3U descartada por no tener URL. */
data class RechazoCanarioM3U(
    val ordinal: Int,
    val motivo: String,
)

/** Resultado del parser canario. */
data class ResultadoCanarioM3U(
    val declaraciones: List<DeclaracionCanal>,
    val rechazos: List<RechazoCanarioM3U>,
    val error: ErrorCanarioM3U? = null,
)

/**
 * Parser M3U mínimo para el canario de E1-C.
 *
 * Alcance deliberadamente acotado: reconoce `#EXTM3U`, `#EXTINF` con los
 * atributos `tvg-id`, `tvg-name` y `group-title`, y la línea de URL siguiente.
 * No implementa el parser tolerante completo de E2 (BOM, codificaciones
 * alternativas, `EXTGRP`, atributos arbitrarios, límites de streaming). Se usa
 * solo para que el canario de importación → catálogo → D-pad sea significativo.
 */
object ParserM3UCanario {
    private val ATRIBUTO = Regex("([A-Za-z0-9-]+)=\"([^\"]*)\"")

    fun parsear(texto: String): ResultadoCanarioM3U {
        if (texto.isBlank()) {
            return ResultadoCanarioM3U(emptyList(), emptyList(), ErrorCanarioM3U.VACIO)
        }
        val lineas = texto.removePrefix("\uFEFF").lineSequence().map { it.trim() }.toList()
        if (lineas.firstOrNull()?.startsWith("#EXTM3U") != true) {
            return ResultadoCanarioM3U(emptyList(), emptyList(), ErrorCanarioM3U.SIN_CABECERA)
        }
        val declaraciones = mutableListOf<DeclaracionCanal>()
        val rechazos = mutableListOf<RechazoCanarioM3U>()
        var pendiente: DeclaracionCanal? = null
        var ordinal = 0
        for (linea in lineas.drop(1)) {
            when {
                linea.isEmpty() -> Unit
                linea.startsWith("#EXTINF") -> pendiente = extinf(linea)
                linea.startsWith("#") -> Unit
                else -> {
                    ordinal += 1
                    val actual = pendiente
                    pendiente = null
                    if (actual == null) {
                        rechazos += RechazoCanarioM3U(ordinal, "sin_extinf")
                    } else {
                        declaraciones += actual.copy(urlOriginal = linea)
                    }
                }
            }
        }
        if (pendiente != null) {
            rechazos += RechazoCanarioM3U(ordinal + 1, "sin_url")
        }
        return ResultadoCanarioM3U(declaraciones, rechazos)
    }

    private fun extinf(linea: String): DeclaracionCanal {
        val atributos = ATRIBUTO.findAll(linea).associate { it.groupValues[1] to it.groupValues[2] }
        return DeclaracionCanal(
            nombre = nombreVisible(linea),
            urlOriginal = null,
            tvgId = atributos["tvg-id"],
            groupTitle = atributos["group-title"],
            tvgName = atributos["tvg-name"],
            logoOriginal = atributos["tvg-logo"],
        )
    }

    /** El nombre visible es el texto tras la primera coma fuera de comillas. */
    private fun nombreVisible(linea: String): String? {
        var enComillas = false
        for ((indice, caracter) in linea.withIndex()) {
            when {
                caracter == '"' -> enComillas = !enComillas
                caracter == ',' && !enComillas -> {
                    val nombre = linea.substring(indice + 1).trim()
                    return nombre.ifEmpty { null }
                }
            }
        }
        return null
    }
}
