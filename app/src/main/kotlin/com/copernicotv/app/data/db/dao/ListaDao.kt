package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.ListaEntity

@Dao
interface ListaDao {
    @Insert
    suspend fun insertar(lista: ListaEntity): Long

    @Query("SELECT * FROM Lista WHERE id = :id")
    suspend fun porId(id: Long): ListaEntity?

    @Query("SELECT * FROM Lista WHERE fuenteId = :fuenteId ORDER BY orden, id")
    suspend fun porFuente(fuenteId: Long): List<ListaEntity>

    @Query("SELECT COUNT(*) FROM Lista")
    suspend fun contar(): Int
}
