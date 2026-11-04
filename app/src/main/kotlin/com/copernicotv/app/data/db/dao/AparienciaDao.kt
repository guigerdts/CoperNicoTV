package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.copernicotv.app.data.db.entities.AparienciaClavePendienteEntity
import com.copernicotv.app.data.db.entities.AparienciaEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface AparienciaDao {
    @Insert
    suspend fun insertar(apariencia: AparienciaEntity): Long

    @Insert
    suspend fun insertarTodas(apariencias: List<AparienciaEntity>): List<Long>

    @Query("SELECT * FROM Apariencia WHERE listaId = :listaId ORDER BY orden, id")
    suspend fun porLista(listaId: Long): List<AparienciaEntity>

    @Query("SELECT COUNT(*) FROM Apariencia WHERE listaId = :listaId")
    suspend fun contar(listaId: Long): Int

    @Query("SELECT COALESCE(MAX(orden), -1) FROM Apariencia WHERE listaId = :listaId")
    suspend fun maxOrden(listaId: Long): Int

    @Query("UPDATE Apariencia SET orden = :orden, actualizadoEn = :actualizadoEn WHERE id = :id")
    suspend fun actualizarOrden(
        id: Long,
        orden: Int,
        actualizadoEn: Instant,
    ): Int

    @Query(
        """
        SELECT c.nombre FROM Apariencia a
        INNER JOIN Canal c ON c.id = a.canalId
        WHERE a.listaId = :listaId
        ORDER BY a.orden, a.id
        """,
    )
    fun nombresDeLista(listaId: Long): Flow<List<String?>>

    /**
     * Catálogo de la Lista activa más reciente.
     *
     * Resuelve la Lista en SQL, no en memoria: tras recrear el proceso no hay
     * ningún `listaId` que recordar y el catálogo debe cargarse igual.
     */
    @Query(
        """
        SELECT c.nombre FROM Apariencia a
        INNER JOIN Canal c ON c.id = a.canalId
        WHERE a.listaId = (
            SELECT id FROM Lista WHERE estado = 'activa' ORDER BY id DESC LIMIT 1
        )
        ORDER BY a.orden, a.id
        """,
    )
    fun nombresDeListaActiva(): Flow<List<String?>>

    @Query("SELECT * FROM Apariencia WHERE id = :id")
    suspend fun porId(id: Long): AparienciaEntity?

    /** Todas las Apariencias, en orden estable por `id`; entrada de la migración DC-05. */
    @Query("SELECT * FROM Apariencia ORDER BY id")
    suspend fun todas(): List<AparienciaEntity>

    /** Apariencia de la tupla única `(lista_id, canal_id, clave_presentacion)`. */
    @Query(
        """
        SELECT * FROM Apariencia
        WHERE listaId = :listaId AND canalId = :canalId AND clavePresentacion = :clavePresentacion
        LIMIT 1
        """,
    )
    suspend fun porTupla(
        listaId: Long,
        canalId: Long,
        clavePresentacion: String,
    ): AparienciaEntity?

    @Query("UPDATE Apariencia SET clavePresentacion = :clave, actualizadoEn = :actualizadoEn WHERE id = :id")
    suspend fun actualizarClavePresentacion(
        id: Long,
        clave: String,
        actualizadoEn: Instant,
    ): Int

    @Query("UPDATE Apariencia SET repeticiones = :repeticiones, actualizadoEn = :actualizadoEn WHERE id = :id")
    suspend fun actualizarRepeticiones(
        id: Long,
        repeticiones: Int,
        actualizadoEn: Instant,
    ): Int

    /** Registro de migración de una Apariencia, resuelto o no. */
    @Query("SELECT * FROM AparienciaClavePendiente WHERE aparienciaId = :aparienciaId")
    suspend fun pendienteDe(aparienciaId: Long): AparienciaClavePendienteEntity?

    /** Todas las filas de migración, en orden estable. */
    @Query("SELECT * FROM AparienciaClavePendiente ORDER BY aparienciaId")
    suspend fun pendientes(): List<AparienciaClavePendienteEntity>

    /** Inserta el registro de migración; una fila ya registrada no se duplica ni se reescribe. */
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertarPendiente(pendiente: AparienciaClavePendienteEntity): Long

    /**
     * Filas pendientes sin resolver de la tupla `(lista_id, canal_id, clave_presentacion_v2)`.
     *
     * Mientras el resultado sea mayor que cero, el escritor rechaza el upsert de
     * esa tupla con `apariencia_migracion_pendiente` (DC-05:635).
     */
    @Query(
        """
        SELECT COUNT(*) FROM Apariencia a
        INNER JOIN AparienciaClavePendiente p ON p.aparienciaId = a.id
        WHERE a.listaId = :listaId
          AND a.canalId = :canalId
          AND p.clavePresentacionV2 = :clavePresentacion
          AND p.resueltaEn IS NULL
        """,
    )
    suspend fun pendientesSinResolverDeTupla(
        listaId: Long,
        canalId: Long,
        clavePresentacion: String,
    ): Int
}
