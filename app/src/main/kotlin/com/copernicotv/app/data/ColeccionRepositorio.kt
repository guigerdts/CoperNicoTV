package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
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
 * La pertenencia se guarda en `ColeccionCanal.posicion`. El esquema v1 no
 * impone unicidad global: debe seguir representando las dos posiciones y la
 * pertenencia duplicada idéntica de ADR-0004:59(m5) antes de la consolidación
 * (E1-U3). El repositorio, en cambio, garantiza que un alta nueva no cree un
 * duplicado: comprueba, inserta y calcula la posición dentro de una única
 * transacción de Room, y la sentencia de alta repite la condición. Las filas
 * duplicadas legacy que existan por una consolidación pendiente se conservan
 * intactas para que m5 pueda resolverlas.
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
         * La comprobación de pertenencia, el cálculo de la posición y el alta
         * ocurren dentro de la misma transacción de Room. Room serializa las
         * transacciones del proceso, así que dos altas concurrentes del mismo
         * Canal no pueden insertar dos filas: la segunda ve la fila confirmada
         * por la primera y devuelve [ResultadoAccion.Ok] sin escribir. La
         * sentencia de alta repite la condición (`NOT EXISTS`) como segunda
         * barrera a nivel de sentencia.
         *
         * No se agrega un índice único global porque E1-U3 exige que el
         * esquema v1 siga representando las dos posiciones y la pertenencia
         * duplicada idéntica de ADR-0004:59(m5) antes de la consolidación. Las
         * filas duplicadas que ya existan por una consolidación pendiente se
         * conservan intactas; solo se impide crear duplicados nuevos.
         */
        suspend fun agregarCanal(
            coleccionId: Long,
            canalId: Long,
        ): ResultadoAccion =
            withContext(Dispatchers.IO) {
                try {
                    db.withTransaction {
                        if (db.coleccionDao().porId(coleccionId) == null) {
                            return@withTransaction ResultadoAccion.Error(ERROR_COLECCION_NO_EXISTE)
                        }
                        if (db.canalDao().porId(canalId) == null) {
                            return@withTransaction ResultadoAccion.Error(ERROR_CANAL_NO_EXISTE)
                        }
                        if (db.coleccionCanalDao().porCanal(coleccionId, canalId).isNotEmpty()) {
                            return@withTransaction ResultadoAccion.Ok
                        }
                        val ahora = Instant.now()
                        db.coleccionCanalDao().insertarSiNoExiste(
                            coleccionId = coleccionId,
                            canalId = canalId,
                            posicion = db.coleccionCanalDao().maxPosicion(coleccionId) + 1,
                        )
                        db.coleccionDao().tocar(coleccionId, ahora)
                        ResultadoAccion.Ok
                    }
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
