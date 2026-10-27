package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.GrupoEntity

@Dao
interface GrupoDao {
    @Insert
    suspend fun insertar(grupo: GrupoEntity): Long

    @Query("SELECT * FROM Grupo WHERE listaId = :listaId AND nombre = :nombre")
    suspend fun porNombre(listaId: Long, nombre: String): GrupoEntity?

    @Query("SELECT COUNT(*) FROM Grupo WHERE listaId = :listaId")
    suspend fun contar(listaId: Long): Int

    @Query("DELETE FROM Grupo WHERE id = :id")
    suspend fun eliminar(id: Long)
}
