package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.copernicotv.app.data.db.entities.DetalleAusenciaEntity
import com.copernicotv.app.data.db.entities.EvidenciaColisionEntity
import com.copernicotv.app.data.db.entities.ObservacionPendienteImportacionEntity

/** Detalle de las filas que la Fuente dejó de declarar (E4-T1/T2). */
@Dao
interface DetalleAusenciaDao {
    @Insert
    suspend fun insertar(fila: DetalleAusenciaEntity): Long

    @Insert
    suspend fun insertarTodas(filas: List<DetalleAusenciaEntity>): List<Long>

    @Query("SELECT * FROM DetalleAusencia WHERE estadoActualizacionId = :estadoId ORDER BY id")
    suspend fun porEstado(estadoId: Long): List<DetalleAusenciaEntity>

    @Query("SELECT COUNT(*) FROM DetalleAusencia")
    suspend fun contar(): Int
}

/** Evidencia de una colisión de identidad detectada en una Actualización (E4-T1). */
@Dao
interface EvidenciaColisionDao {
    @Insert
    suspend fun insertar(fila: EvidenciaColisionEntity): Long

    @Insert
    suspend fun insertarTodas(filas: List<EvidenciaColisionEntity>): List<Long>

    @Query("SELECT * FROM EvidenciaColision WHERE estadoActualizacionId = :estadoId ORDER BY id")
    suspend fun porEstado(estadoId: Long): List<EvidenciaColisionEntity>

    @Query("SELECT COUNT(*) FROM EvidenciaColision")
    suspend fun contar(): Int
}

/**
 * Declaraciones que colisionan con un conjunto B/C o con una convergencia.
 *
 * La deduplicación es por `(fuenteId, claveDeduplicacion)`: la misma
 * declaración observada en Actualizaciones sucesivas conserva la fila y
 * actualiza `ultimaObservacion`, en lugar de multiplicarse (E4-T3).
 */
@Dao
interface ObservacionPendienteDao {
    @Insert
    suspend fun insertar(observacion: ObservacionPendienteImportacionEntity): Long

    @Update
    suspend fun actualizar(observacion: ObservacionPendienteImportacionEntity): Int

    @Query("SELECT * FROM ObservacionPendienteImportacion WHERE id = :id")
    suspend fun porId(id: Long): ObservacionPendienteImportacionEntity?

    @Query(
        """
        SELECT * FROM ObservacionPendienteImportacion
        WHERE fuenteId = :fuenteId AND claveDeduplicacion = :clave
        LIMIT 1
        """,
    )
    suspend fun porClave(
        fuenteId: Long,
        clave: String,
    ): ObservacionPendienteImportacionEntity?

    @Query("SELECT * FROM ObservacionPendienteImportacion WHERE fuenteId = :fuenteId ORDER BY primeraObservacion, id")
    suspend fun porFuente(fuenteId: Long): List<ObservacionPendienteImportacionEntity>

    @Query("DELETE FROM ObservacionPendienteImportacion WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT COUNT(*) FROM ObservacionPendienteImportacion WHERE fuenteId = :fuenteId")
    suspend fun contarPorFuente(fuenteId: Long): Int

    @Query("SELECT COUNT(*) FROM ObservacionPendienteImportacion")
    suspend fun contar(): Int
}
