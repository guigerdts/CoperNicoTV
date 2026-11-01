package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.AsociacionEntity

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

    @Query("SELECT COUNT(*) FROM Asociacion")
    suspend fun contar(): Int
}
