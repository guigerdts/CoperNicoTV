package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.AparienciaEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface AparienciaDao {
    @Insert
    suspend fun insertar(apariencia: AparienciaEntity): Long

    @Query("SELECT * FROM Apariencia WHERE listaId = :listaId ORDER BY orden, id")
    suspend fun porLista(listaId: Long): List<AparienciaEntity>

    @Query("SELECT COUNT(*) FROM Apariencia WHERE listaId = :listaId")
    suspend fun contar(listaId: Long): Int

    @Query(
        """
        SELECT c.nombre FROM Apariencia a
        INNER JOIN Canal c ON c.id = a.canalId
        WHERE a.listaId = :listaId
        ORDER BY a.orden, a.id
        """,
    )
    fun nombresDeLista(listaId: Long): Flow<List<String?>>
}
