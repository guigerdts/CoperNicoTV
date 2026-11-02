package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import java.time.Instant

/**
 * Fuentes de guía EPG.
 *
 * La tabla existía desde el esquema v1 sin DAO. Se añade el mínimo que la
 * fusión de Canales necesita para satisfacer la FK de `Asociacion`.
 */
@Dao
interface FuenteGuiaDao {
    @Insert
    suspend fun insertar(fila: FuenteGuiaEntity): Long

    @Query("SELECT * FROM FuenteGuia WHERE id = :id")
    suspend fun porId(id: Long): FuenteGuiaEntity?

    @Query("SELECT * FROM FuenteGuia ORDER BY id")
    suspend fun todas(): List<FuenteGuiaEntity>

    @Query(
        "UPDATE FuenteGuia SET generacionPublicadaId = :generacionId, actualizadoEn = :ahora WHERE id = :id",
    )
    suspend fun fijarPublicada(
        id: Long,
        generacionId: Long,
        ahora: Instant,
    ): Int

    @Query(
        "UPDATE FuenteGuia SET retencionVentanaPasadoDias = :ventanaPasadoDias, " +
            "retencionVentanaFuturoDias = :ventanaFuturoDias, retencionMaxProgramas = :maxProgramas, " +
            "retencionPresupuestoBytes = :presupuestoBytes, usarRetencionGlobal = :usarGlobal, " +
            "actualizadoEn = :ahora WHERE id = :id",
    )
    suspend fun actualizarRetencion(
        id: Long,
        ventanaPasadoDias: Long?,
        ventanaFuturoDias: Long?,
        maxProgramas: Long?,
        presupuestoBytes: Long?,
        usarGlobal: Boolean,
        ahora: Instant,
    ): Int

    @Query("DELETE FROM FuenteGuia WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT COUNT(*) FROM FuenteGuia")
    suspend fun contar(): Int
}
