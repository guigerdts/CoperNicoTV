package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.copernicotv.app.data.db.entities.AsociacionEntity

/**
 * Asociación con el Canal y la generación publicada que la respalda.
 *
 * La UI necesita el nombre del Canal y la generación para consultar programas;
 * una Asociación sin generación publicada no puede mostrar guía.
 */
data class CanalConGuia(
    val canalId: Long,
    val nombre: String?,
    val channelIdTexto: String,
    val fuenteGuiaId: Long,
    val generacionId: Long,
    val estado: String,
)

/**
 * Asociaciones EPG de un Canal.
 *
 * La tabla existía desde el esquema v1 pero no tenía DAO porque la EPG todavía
 * no escribe Asociaciones. La fusión de Canales (ADR-0004:60) necesita moverlas
 * y resolver el conflicto de identificadores distintos para la misma guía.
 */
@Dao
interface AsociacionDao {
    @Insert
    suspend fun insertar(fila: AsociacionEntity): Long

    @Query("SELECT * FROM Asociacion WHERE canalId = :canalId ORDER BY fuenteGuiaId, channelIdTexto, id")
    suspend fun porCanal(canalId: Long): List<AsociacionEntity>

    /**
     * Reasigna una Asociación a otro Canal.
     *
     * Se aplica fila por fila: el índice único global
     * `(fuenteGuiaId, channelIdTexto)` obliga a resolver primero los pares que
     * ya existen en el Canal destino.
     */
    @Query("UPDATE Asociacion SET canalId = :canalId WHERE id = :id")
    suspend fun actualizarCanal(
        id: Long,
        canalId: Long,
    ): Int

    @Query("DELETE FROM Asociacion WHERE canalId = :canalId AND fuenteGuiaId = :fuenteGuiaId")
    suspend fun eliminarPorCanalYFuente(
        canalId: Long,
        fuenteGuiaId: Long,
    ): Int

    @Query("DELETE FROM Asociacion WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT * FROM Asociacion WHERE id = :id")
    suspend fun porId(id: Long): AsociacionEntity?

    @Query("SELECT * FROM Asociacion WHERE fuenteGuiaId = :fuenteGuiaId ORDER BY channelIdTexto, id")
    suspend fun porFuente(fuenteGuiaId: Long): List<AsociacionEntity>

    @Query(
        "SELECT * FROM Asociacion WHERE fuenteGuiaId = :fuenteGuiaId AND channelIdTexto = :channelId LIMIT 1",
    )
    suspend fun porFuenteYCanal(
        fuenteGuiaId: Long,
        channelId: String,
    ): AsociacionEntity?

    @Update
    suspend fun actualizar(fila: AsociacionEntity): Int

    @Query("DELETE FROM Asociacion WHERE fuenteGuiaId = :fuenteGuiaId")
    suspend fun eliminarPorFuente(fuenteGuiaId: Long): Int

    @Query("SELECT COUNT(*) FROM Asociacion WHERE fuenteGuiaId = :fuenteGuiaId AND estado = :estado")
    suspend fun contarPorEstado(
        fuenteGuiaId: Long,
        estado: String,
    ): Int

    @Query(
        """
        SELECT a.canalId AS canalId, c.nombre AS nombre, a.channelIdTexto AS channelIdTexto,
            a.fuenteGuiaId AS fuenteGuiaId, f.generacionPublicadaId AS generacionId, a.estado AS estado
        FROM Asociacion a
        INNER JOIN Canal c ON c.id = a.canalId
        INNER JOIN FuenteGuia f ON f.id = a.fuenteGuiaId
        WHERE f.generacionPublicadaId IS NOT NULL
          AND (:fuenteGuiaId IS NULL OR a.fuenteGuiaId = :fuenteGuiaId)
        ORDER BY c.nombre, c.id, a.fuenteGuiaId
        """,
    )
    suspend fun conCanalYGuia(fuenteGuiaId: Long?): List<CanalConGuia>

    @Query("SELECT COUNT(*) FROM Asociacion")
    suspend fun contar(): Int
}
