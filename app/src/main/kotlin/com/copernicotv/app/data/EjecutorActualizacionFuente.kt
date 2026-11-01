package com.copernicotv.app.data

import com.copernicotv.app.data.db.AppDatabase
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Ejecuta la Actualización de todas las Listas de una Fuente (E4-T8).
 *
 * Es una interfaz para que la pantalla de Fuentes pueda probarse sin red ni
 * base de datos: el contrato de S7a–S7c se verifica sobre el resultado, no
 * sobre el transporte.
 */
fun interface EjecutorActualizacionFuente {
    suspend fun actualizarFuente(fuenteId: Long)
}

/** Implementación de producción: una Lista después de otra, con el pipeline de E4-T1/T2/T6. */
@Singleton
class EjecutorActualizacionFuenteRoom
    @Inject
    constructor(
        private val db: AppDatabase,
        private val actualizacion: ActualizacionRepositorio,
    ) : EjecutorActualizacionFuente {
        override suspend fun actualizarFuente(fuenteId: Long) {
            db.listaDao().porFuente(fuenteId).forEach { lista ->
                actualizacion.actualizarLista(lista.id)
            }
        }
    }
