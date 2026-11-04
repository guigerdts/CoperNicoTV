package com.copernicotv.app.data

/** Colección visible en la UI, sin duplicar su orden en memoria. */
data class ColeccionResumen(
    val id: Long,
    val nombre: String,
)

/**
 * Pertenencia de un Canal a una Colección.
 *
 * [id] identifica la fila de `ColeccionCanal`, no al Canal: antes de la
 * consolidación de ADR-0004:60(m5) la misma Colección puede contener dos filas
 * del mismo Canal y cada una conserva su propia posición.
 */
data class ColeccionCanalResumen(
    val id: Long,
    val canalId: Long,
    val nombre: String?,
    val posicion: Int,
)
