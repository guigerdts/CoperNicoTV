package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.GeneracionGuiaEntity
import java.time.Instant

/**
 * Generaciones de una `FuenteGuia` (E5-T2).
 *
 * Una importación crea una generación `staging`; solo la transacción de
 * publicación la marca `publicada` y mueve `FuenteGuia.generacionPublicadaId`.
 * Un fallo deja la generación anterior intacta y elimina la de staging.
 */
@Dao
interface GeneracionGuiaDao {
    @Insert
    suspend fun insertar(fila: GeneracionGuiaEntity): Long

    @Query("SELECT * FROM GeneracionGuia WHERE id = :id")
    suspend fun porId(id: Long): GeneracionGuiaEntity?

    @Query(
        "SELECT * FROM GeneracionGuia WHERE fuenteGuiaId = :fuenteGuiaId AND estado = 'publicada' " +
            "ORDER BY publicadaEn DESC, id DESC LIMIT 1",
    )
    suspend fun publicada(fuenteGuiaId: Long): GeneracionGuiaEntity?

    @Query("SELECT * FROM GeneracionGuia WHERE fuenteGuiaId = :fuenteGuiaId ORDER BY creadoEn DESC, id DESC")
    suspend fun porFuente(fuenteGuiaId: Long): List<GeneracionGuiaEntity>

    @Query("SELECT * FROM GeneracionGuia WHERE fuenteGuiaId = :fuenteGuiaId AND estado = 'staging'")
    suspend fun staging(fuenteGuiaId: Long): List<GeneracionGuiaEntity>

    @Query("UPDATE GeneracionGuia SET estado = :estado, publicadaEn = :publicadaEn WHERE id = :id")
    suspend fun actualizarEstado(
        id: Long,
        estado: String,
        publicadaEn: Instant?,
    ): Int

    @Query("DELETE FROM GeneracionGuia WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("DELETE FROM GeneracionGuia WHERE fuenteGuiaId = :fuenteGuiaId AND id != :exceptoId")
    suspend fun eliminarOtrasDeFuente(
        fuenteGuiaId: Long,
        exceptoId: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM GeneracionGuia")
    suspend fun contar(): Int
}
