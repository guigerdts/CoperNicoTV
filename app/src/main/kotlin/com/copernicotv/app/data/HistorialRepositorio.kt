package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.HistorialEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Entrada del acceso rápido de Historial.
 *
 * `canalId` es la identidad lógica del Canal; `nombre` es nulo cuando el Canal
 * no tiene nombre, y la UI lo presenta como «Sin nombre» (S14). Las marcas se
 * resuelven en la misma consulta para no abrir el acceso rápido a un Canal con
 * estado desactualizado.
 */
data class HistorialResumen(
    val canalId: Long,
    val nombre: String?,
    val esFavorito: Boolean,
    val estaOculto: Boolean,
)

/**
 * Historial de reproducción con una sola fila por Canal.
 *
 * `registrar` conserva la fecha de última reproducción y recorta a [LIMITE]
 * Canales dentro de la misma transacción: si el recorte falla, la inserción o
 * actualización también se revierte. El límite visible y persistido es fijo.
 */
@Singleton
class HistorialRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        suspend fun registrar(
            canalId: Long,
            fecha: Instant = Instant.now(),
        ) {
            withContext(Dispatchers.IO) {
                db.withTransaction {
                    val actual = db.historialDao().porCanal(canalId)
                    if (actual == null) {
                        db.historialDao().insertar(HistorialEntity(canalId = canalId, fecha = fecha))
                    } else if (fecha.isAfter(actual.fecha)) {
                        db.historialDao().actualizarFecha(canalId, fecha)
                    }
                    db.historialDao().recortar(LIMITE)
                }
            }
        }

        suspend fun porCanal(canalId: Long): HistorialEntity? =
            withContext(Dispatchers.IO) {
                db.historialDao().porCanal(canalId)
            }

        /** Los [LIMITE] Canales más recientes, en el orden del índice `(fecha, canalId)`. */
        suspend fun ultimos(): List<HistorialEntity> =
            withContext(Dispatchers.IO) {
                db.historialDao().ultimos(LIMITE)
            }

        /**
         * Acceso rápido observable de S11c: los [LIMITE] Canales más recientes
         * con su nombre y sus marcas actuales, en el orden `fecha DESC,
         * canalId DESC`. Room invalida el flujo cuando cambian `Historial`,
         * `Canal`, `Favorito` u `OcultacionCanal`.
         */
        fun observarUltimos(): Flow<List<HistorialResumen>> = db.historialDao().observarUltimos(LIMITE)

        companion object {
            const val LIMITE: Int = 10
        }
    }
