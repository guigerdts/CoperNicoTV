package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.FuenteEntity

@Dao
interface FuenteDao {
    @Insert
    suspend fun insertar(fuente: FuenteEntity): Long

    @Query("SELECT * FROM Fuente WHERE id = :id")
    suspend fun porId(id: Long): FuenteEntity?

    @Query("SELECT * FROM Fuente ORDER BY nombre")
    suspend fun todas(): List<FuenteEntity>

    @Query("SELECT COUNT(*) FROM Fuente")
    suspend fun contar(): Int

    @Query("DELETE FROM Fuente WHERE id = :id")
    suspend fun eliminar(id: Long)
}
