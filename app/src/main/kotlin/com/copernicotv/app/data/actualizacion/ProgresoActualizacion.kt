package com.copernicotv.app.data.actualizacion

/**
 * Informe de avance de una ejecución de Actualización (H-04, S13).
 *
 * La ejecución de una Fuente con decenas de Listas puede tardar minutos. Sin
 * un informe explícito, la única señal que recibe el usuario es el giro
 * indefinido de la pantalla y WorkManager no puede publicar `setProgress`.
 *
 * [informar] recibe el avance en Listas, no en Canales: una Lista es la unidad
 * de trabajo que el coordinador termina de forma indivisible, así que su
 * progreso no miente. La ejecución llama a [informar] antes de empezar, con
 * `hechos = 0`, para que la UI sepa el total desde el primer instante.
 */
fun interface ProgresoActualizacion {
    /** `hechos` de `total` Listas ya terminadas. */
    suspend fun informar(
        hechos: Int,
        total: Int,
    )

    companion object {
        /** Ejecución sin observador: pruebas y llamadas que no publican avance. */
        val NULO: ProgresoActualizacion = ProgresoActualizacion { _, _ -> }
    }
}
