package com.copernicotv.app.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.copernicotv.app.data.CanalBusqueda
import com.copernicotv.app.data.db.entities.CanalEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface CanalDao {
    @Insert
    suspend fun insertar(canal: CanalEntity): Long

    @Insert
    suspend fun insertarTodos(canales: List<CanalEntity>): List<Long>

    @Update
    suspend fun actualizar(canal: CanalEntity): Int

    @Query("DELETE FROM Canal WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT * FROM Canal WHERE id = :id")
    suspend fun porId(id: Long): CanalEntity?

    @Query("SELECT * FROM Canal WHERE fuenteId = :fuenteId ORDER BY id")
    suspend fun porFuente(fuenteId: Long): List<CanalEntity>

    @Query("SELECT * FROM Canal WHERE fuenteId = :fuenteId AND tvgId = :tvgId LIMIT 1")
    suspend fun porTvgId(
        fuenteId: Long,
        tvgId: String,
    ): CanalEntity?

    @Query("SELECT * FROM Canal WHERE fuenteId = :fuenteId AND claveUnicidad = :clave LIMIT 1")
    suspend fun porClaveUnicidad(
        fuenteId: Long,
        clave: String,
    ): CanalEntity?

    /**
     * Libera la clave de unicidad de todas las filas que pasan a B antes de
     * escribir la evidencia de colisión (E4-T1).
     *
     * El orden es contractual: con el índice único `(fuenteId, claveUnicidad)`,
     * escribir primero la evidencia o la nueva clave haría fallar la
     * transacción. La sentencia deja las N filas sin clave en una sola pasada.
     */
    @Query("UPDATE Canal SET estado = 'B', claveUnicidad = NULL, actualizadoEn = :actualizadoEn WHERE id IN (:ids)")
    suspend fun liberarClaves(
        ids: List<Long>,
        actualizadoEn: Instant,
    ): Int

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
     *
     * Las columnas de grupo, historial y Apariencia alimentan los modos A/B/C.
     * La clave primaria del Canal sigue siendo la identidad de la fila; el
     * historial es opcional y puede no existir.
     */
    @Query(
        """
        SELECT c.id AS id, c.nombre AS nombre,
            EXISTS(SELECT 1 FROM Favorito f WHERE f.canalId = c.id) AS esFavorito,
            EXISTS(SELECT 1 FROM OcultacionCanal oc WHERE oc.canalId = c.id) AS estaOculto,
            a.grupoId AS grupoId,
            g.nombre AS grupoNombre,
            h.fecha AS ultimaReproduccionEpochMs,
            a.id AS aparienciaId,
            a.orden AS ordenManual
        FROM Canal c
        INNER JOIN Apariencia a ON a.canalId = c.id
        LEFT JOIN Grupo g ON g.id = a.grupoId
        LEFT JOIN Historial h ON h.canalId = c.id
        WHERE a.listaId = (
            SELECT id FROM Lista WHERE estado = 'activa' ORDER BY id DESC LIMIT 1
        )
          AND (:texto = '' OR c.nombre LIKE :patron ESCAPE '!')
          AND (:grupoId IS NULL OR a.grupoId = :grupoId)
          AND (:soloFavoritos = 0 OR EXISTS(
              SELECT 1 FROM Favorito f2 WHERE f2.canalId = c.id
          ))
          AND (:incluirOcultos = 1 OR NOT EXISTS(
              SELECT 1 FROM OcultacionCanal oc2 WHERE oc2.canalId = c.id
          ))
          AND (
              :incluirOcultos = 1 OR a.grupoId IS NULL OR NOT EXISTS(
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
        incluirOcultos: Boolean,
    ): PagingSource<Int, CanalBusqueda>

    /**
     * Todos los Canales filtrados de la Lista activa, sin paginación SQL.
     *
     * La usa el `PagingSource` en memoria de los modos A/B/C, que necesita la
     * lista completa para ordenarla con la collation de `:core`. El `ORDER BY`
     * es solo un desempate estable; la ordenación real ocurre en Kotlin.
     */
    @Query(
        """
        SELECT c.id AS id, c.nombre AS nombre,
            EXISTS(SELECT 1 FROM Favorito f WHERE f.canalId = c.id) AS esFavorito,
            EXISTS(SELECT 1 FROM OcultacionCanal oc WHERE oc.canalId = c.id) AS estaOculto,
            a.grupoId AS grupoId,
            g.nombre AS grupoNombre,
            h.fecha AS ultimaReproduccionEpochMs,
            a.id AS aparienciaId,
            a.orden AS ordenManual
        FROM Canal c
        INNER JOIN Apariencia a ON a.canalId = c.id
        LEFT JOIN Grupo g ON g.id = a.grupoId
        LEFT JOIN Historial h ON h.canalId = c.id
        WHERE a.listaId = (
            SELECT id FROM Lista WHERE estado = 'activa' ORDER BY id DESC LIMIT 1
        )
          AND (:texto = '' OR c.nombre LIKE :patron ESCAPE '!')
          AND (:grupoId IS NULL OR a.grupoId = :grupoId)
          AND (:soloFavoritos = 0 OR EXISTS(
              SELECT 1 FROM Favorito f2 WHERE f2.canalId = c.id
          ))
          AND (:incluirOcultos = 1 OR NOT EXISTS(
              SELECT 1 FROM OcultacionCanal oc2 WHERE oc2.canalId = c.id
          ))
          AND (
              :incluirOcultos = 1 OR a.grupoId IS NULL OR NOT EXISTS(
                  SELECT 1 FROM OcultacionGrupo og WHERE og.grupoId = a.grupoId
              )
          )
        GROUP BY c.id, c.nombre
        ORDER BY c.id
        """,
    )
    suspend fun buscarEnListaActivaTodos(
        texto: String,
        patron: String,
        grupoId: Long?,
        soloFavoritos: Boolean,
        incluirOcultos: Boolean,
    ): List<CanalBusqueda>
}
