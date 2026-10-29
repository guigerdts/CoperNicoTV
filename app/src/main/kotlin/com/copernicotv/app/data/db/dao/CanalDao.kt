package com.copernicotv.app.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.CanalBusqueda
import com.copernicotv.app.data.db.entities.CanalEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CanalDao {
    @Insert
    suspend fun insertar(canal: CanalEntity): Long

    @Insert
    suspend fun insertarTodos(canales: List<CanalEntity>): List<Long>

    @Query("SELECT * FROM Canal WHERE id = :id")
    suspend fun porId(id: Long): CanalEntity?

    @Query("SELECT * FROM Canal WHERE fuenteId = :fuenteId ORDER BY id")
    suspend fun porFuente(fuenteId: Long): List<CanalEntity>

    @Query("SELECT COUNT(*) FROM Canal")
    suspend fun contar(): Int

    @Query("SELECT COUNT(*) FROM Canal WHERE fuenteId = :fuenteId")
    suspend fun contarPorFuente(fuenteId: Long): Int

    @Query(
        """
        SELECT c.* FROM Canal c
        INNER JOIN Apariencia a ON a.canalId = c.id
        WHERE a.listaId = :listaId
        ORDER BY a.orden, a.id
        """,
    )
    fun catalogoDeLista(listaId: Long): Flow<List<CanalEntity>>

    /**
     * Búsqueda paginada de la Lista activa.
     *
     * `GROUP BY c.id` garantiza una sola fila por Canal aunque tenga varias
     * Apariencias en la misma Lista. El orden usa la primera Apariencia visible
     * para que dos consultas consecutivas devuelvan la misma página.
     */
    @Query(
        """
        SELECT c.id AS id, c.nombre AS nombre,
            EXISTS(SELECT 1 FROM Favorito f WHERE f.canalId = c.id) AS esFavorito
        FROM Canal c
        INNER JOIN Apariencia a ON a.canalId = c.id
        WHERE a.listaId = (
            SELECT id FROM Lista WHERE estado = 'activa' ORDER BY id DESC LIMIT 1
        )
          AND (:texto = '' OR c.nombre LIKE :patron ESCAPE '!')
          AND (:grupoId IS NULL OR a.grupoId = :grupoId)
          AND (:soloFavoritos = 0 OR EXISTS(
              SELECT 1 FROM Favorito f2 WHERE f2.canalId = c.id
          ))
          AND NOT EXISTS(
              SELECT 1 FROM OcultacionCanal oc WHERE oc.canalId = c.id
          )
          AND (
              a.grupoId IS NULL OR NOT EXISTS(
                  SELECT 1 FROM OcultacionGrupo og WHERE og.grupoId = a.grupoId
              )
          )
        GROUP BY c.id, c.nombre
        ORDER BY MIN(a.orden), MIN(a.id)
        """,
    )
    fun buscarEnListaActiva(
        texto: String,
        patron: String,
        grupoId: Long?,
        soloFavoritos: Boolean,
    ): PagingSource<Int, CanalBusqueda>
}
