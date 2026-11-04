package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Colecciones con orden propio, independiente de `Apariencia.orden` y de la
 * preferencia A/B/C de cada Lista.
 *
 * La pertenencia se guarda en `ColeccionCanal.posicion`. El repositorio no
 * impone unicidad de `(coleccionId, canalId)` ni de `(coleccionId, posicion)`:
 * el esquema v1 debe seguir permitiendo las dos posiciones de ADR-0004:60(m5)
 * antes de que E4 resuelva la consolidación. La operación de alta sí es
 * idempotente para no crear duplicados nuevos desde la UI.
 */
@Singleton
class ColeccionRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        fun colecciones(): Flow<List<ColeccionResumen>> {
            return db.coleccionDao().observarTodas().map { filas ->
                filas.map { fila -> ColeccionResumen(id = fila.id, nombre = fila.nombre) }
            }
        }

        fun canalesDe(coleccionId: Long): Flow<List<ColeccionCanalResumen>> = db.coleccionCanalDao().observarPorColeccion(coleccionId)

        suspend fun crear(nombre: String): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val limpio = nombre.trim()
                if (limpio.isEmpty()) return@withContext ResultadoAccion.Error(ERROR_NOMBRE_VACIO)
                try {
                    val ahora = Instant.now()
                    db.coleccionDao().insertar(
                        ColeccionEntity(nombre = limpio, creadoEn = ahora, actualizadoEn = ahora),
                    )
                    ResultadoAccion.Ok
                } catch (error: Exception) {
                    ResultadoAccion.Error(ERROR_CREAR_COLECCION)
                }
            }

        suspend fun renombrar(
            coleccionId: Long,
            nombre: String,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val limpio = nombre.trim()
                if (limpio.isEmpty()) return@withContext ResultadoAccion.Error(ERROR_NOMBRE_VACIO)
                val filas = db.coleccionDao().renombrar(coleccionId, limpio, Instant.now())
                if (filas == 0) {
                    ResultadoAccion.Error(ERROR_COLECCION_NO_EXISTE)
                } else {
                    ResultadoAccion.Ok
                }
            }

        suspend fun eliminar(coleccionId: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val filas = db.coleccionDao().eliminar(coleccionId)
                if (filas == 0) {
                    ResultadoAccion.Error(ERROR_COLECCION_NO_EXISTE)
                } else {
                    ResultadoAccion.Ok
                }
            }

        /**
         * Agrega un Canal al final de la Colección.
         *
         * Si el Canal ya tiene una pertenencia en esa Colección no se inserta
         * otra fila: la UI no crea duplicados nuevos. Las filas duplicadas que
         * ya existan por una consolidación pendiente se conservan intactas.
         */
        suspend fun agregarCanal(
            coleccionId: Long,
            canalId: Long,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (db.coleccionDao().porId(coleccionId) == null) {
                    return@withContext ResultadoAccion.Error(ERROR_COLECCION_NO_EXISTE)
                }
                if (db.canalDao().porId(canalId) == null) {
                    return@withContext ResultadoAccion.Error(ERROR_CANAL_NO_EXISTE)
                }
                if (db.coleccionCanalDao().porCanal(coleccionId, canalId).isNotEmpty()) {
                    return@withContext ResultadoAccion.Ok
                }
                try {
                    db.withTransaction {
                        val ahora = Instant.now()
                        db.coleccionCanalDao().insertar(
                            ColeccionCanalEntity(
                                coleccionId = coleccionId,
                                canalId = canalId,
                                posicion = db.coleccionCanalDao().maxPosicion(coleccionId) + 1,
                            ),
                        )
                        db.coleccionDao().tocar(coleccionId, ahora)
                    }
                    ResultadoAccion.Ok
                } catch (error: Exception) {
                    ResultadoAccion.Error(ERROR_AGREGAR_CANAL)
                }
            }

        suspend fun quitarFila(filaId: Long): ResultadoAccion =
            withContext(Dispatchers.IO) {
                val fila =
                    db.coleccionCanalDao().porId(filaId)
                        ?: return@withContext ResultadoAccion.Error(ERROR_FILA_NO_EXISTE)
                try {
                    db.withTransaction {
                        db.coleccionCanalDao().eliminarFila(filaId)
                        db.coleccionDao().tocar(fila.coleccionId, Instant.now())
                        reindexar(fila.coleccionId)
                    }
                    ResultadoAccion.Ok
                } catch (error: Exception) {
                    ResultadoAccion.Error(ERROR_QUITAR_CANAL)
                }
            }

        /**
         * Mueve una pertenencia una posición arriba o abajo y reindexa la
         * Colección. La fila se identifica por `ColeccionCanal.id` para no
         * confundir dos pertenencias del mismo Canal antes de m5.
         */
        suspend fun moverFila(
            filaId: Long,
            delta: Int,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                if (delta != -1 && delta != 1) {
                    return@withContext ResultadoAccion.Error(ERROR_MOVIMIENTO_INVALIDO)
                }
                val fila =
                    db.coleccionCanalDao().porId(filaId)
                        ?: return@withContext ResultadoAccion.Error(ERROR_FILA_NO_EXISTE)
                val filas = db.coleccionCanalDao().porColeccion(fila.coleccionId)
                val origen = filas.indexOfFirst { it.id == filaId }
                if (origen < 0) return@withContext ResultadoAccion.Error(ERROR_FILA_NO_EXISTE)
                val destino = origen + delta
                if (destino !in filas.indices) return@withContext ResultadoAccion.Ok
                try {
                    db.withTransaction {
                        val reordenadas = filas.toMutableList().apply { swap(origen, destino) }
                        reordenadas.forEachIndexed { posicion, pertenencia ->
                            db.coleccionCanalDao().actualizarPosicion(pertenencia.id, posicion)
                        }
                        db.coleccionDao().tocar(fila.coleccionId, Instant.now())
                    }
                    ResultadoAccion.Ok
                } catch (error: Exception) {
                    ResultadoAccion.Error(ERROR_MOVER_CANAL)
                }
            }

        private suspend fun reindexar(coleccionId: Long) {
            db.coleccionCanalDao().porColeccion(coleccionId).forEachIndexed { posicion, fila ->
                db.coleccionCanalDao().actualizarPosicion(fila.id, posicion)
            }
        }

        private fun <T> MutableList<T>.swap(
            primero: Int,
            segundo: Int,
        ) {
            val temporal = this[primero]
            this[primero] = this[segundo]
            this[segundo] = temporal
        }

        private companion object {
            const val ERROR_NOMBRE_VACIO = "nombre_vacio"
            const val ERROR_COLECCION_NO_EXISTE = "coleccion_no_existe"
            const val ERROR_CANAL_NO_EXISTE = "canal_no_existe"
            const val ERROR_FILA_NO_EXISTE = "coleccion_canal_no_existe"
            const val ERROR_CREAR_COLECCION = "crear_coleccion_fallida"
            const val ERROR_AGREGAR_CANAL = "agregar_canal_coleccion_fallido"
            const val ERROR_QUITAR_CANAL = "quitar_canal_coleccion_fallido"
            const val ERROR_MOVER_CANAL = "mover_canal_coleccion_fallido"
            const val ERROR_MOVIMIENTO_INVALIDO = "movimiento_invalido"
        }
    }
