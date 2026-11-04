package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.GrupoEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface GrupoDao {
    @Insert
    suspend fun insertar(grupo: GrupoEntity): Long

    @Insert
    suspend fun insertarTodos(grupos: List<GrupoEntity>): List<Long>

    /** Grupos visibles de la Lista activa, ordenados por nombre. */
    @Query(
        """
        SELECT g.* FROM Grupo g
        WHERE g.listaId = (
            SELECT id FROM Lista WHERE estado = 'activa' ORDER BY id DESC LIMIT 1
        )
          AND NOT EXISTS(
              SELECT 1 FROM OcultacionGrupo og WHERE og.grupoId = g.id
          )
        ORDER BY g.nombre, g.id
        """,
    )
    fun gruposDeListaActiva(): Flow<List<GrupoEntity>>

    @Query("SELECT * FROM Grupo WHERE listaId = :listaId AND nombre = :nombre")
    suspend fun porNombre(
        listaId: Long,
        nombre: String,
    ): GrupoEntity?

    @Query("SELECT COUNT(*) FROM Grupo WHERE listaId = :listaId")
    suspend fun contar(listaId: Long): Int

    @Query("DELETE FROM Grupo WHERE id = :id")
    suspend fun eliminar(id: Long)
}
