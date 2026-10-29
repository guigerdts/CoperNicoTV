package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.HistorialEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

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

        companion object {
            const val LIMITE: Int = 10
        }
    }
