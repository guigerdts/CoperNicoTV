package com.copernicotv.app.data

import java.time.Instant

/**
 * Disponibilidad de reproducción de una Fuente (S7c/S7d).
 *
 * Se deriva del catálogo persistido y nunca del resultado de una Actualización:
 * un intento fallido no la modifica porque no borra Canales.
 */
enum class DisponibilidadFuente {
    DISPONIBLE,
    SIN_CONTENIDO,
}

/** Último intento de Actualización completado de una Fuente. */
data class ResultadoActualizacionFuente(
    val exito: Boolean,
    val causa: String? = null,
    val fin: Instant,
)

/**
 * Los tres indicadores de S7 sin reutilizar uno por otro (E4-T8).
 *
 * - [cargando]: hay una Actualización en curso para la Fuente.
 * - [actualizacion]: último intento completado, con éxito o con causa de fallo.
 * - [disponibilidad]: catálogo disponible para reproducir.
 */
data class IndicadoresFuente(
    val fuenteId: Long,
    val nombre: String,
    val cargando: Boolean = false,
    val actualizacion: ResultadoActualizacionFuente? = null,
    val disponibilidad: DisponibilidadFuente,
)
