package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.CanalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanalDao {
    @Insert
    suspend fun insertar(canal: CanalEntity): Long

    @Query("SELECT * FROM Canal WHERE id = :id")
    suspend fun porId(id: Long): CanalEntity?

    @Query("SELECT * FROM Canal WHERE fuenteId = :fuenteId ORDER BY id")
    suspend fun porFuente(fuenteId: Long): List<CanalEntity>

    @Query("SELECT COUNT(*) FROM Canal")
    suspend fun contar(): Int

    @Query(
        """
        SELECT c.* FROM Canal c
        INNER JOIN Apariencia a ON a.canalId = c.id
        WHERE a.listaId = :listaId
        ORDER BY a.orden, a.id
        """,
    )
    fun catalogoDeLista(listaId: Long): Flow<List<CanalEntity>>
}
