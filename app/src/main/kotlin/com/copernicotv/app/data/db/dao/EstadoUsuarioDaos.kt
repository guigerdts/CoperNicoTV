package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.app.data.db.entities.OcultacionGrupoEntity

/**
 * Estado de usuario por Canal: Favorito y OcultacionCanal.
 *
 * La unicidad la sostiene el índice único de la entidad; `IGNORE` convierte
 * una segunda alta en una operación idempotente sin duplicar la fila.
 */
@Dao
interface FavoritoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(fila: FavoritoEntity): Long

    @Query("DELETE FROM Favorito WHERE canalId = :canalId")
    suspend fun eliminarPorCanal(canalId: Long): Int

    @Query("SELECT * FROM Favorito WHERE canalId = :canalId")
    suspend fun porCanal(canalId: Long): FavoritoEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM Favorito WHERE canalId = :canalId)")
    suspend fun existe(canalId: Long): Int

    @Query("SELECT canalId FROM Favorito ORDER BY creadoEn DESC, canalId DESC")
    suspend fun canalesFavoritos(): List<Long>

    @Query("SELECT COUNT(*) FROM Favorito")
    suspend fun contar(): Int
}

/** Ocultación de un Canal concreto; no modifica su pertenencia a Listas. */
@Dao
interface OcultacionCanalDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(fila: OcultacionCanalEntity): Long

    @Query("DELETE FROM OcultacionCanal WHERE canalId = :canalId")
    suspend fun eliminarPorCanal(canalId: Long): Int

    @Query("SELECT * FROM OcultacionCanal WHERE canalId = :canalId")
    suspend fun porCanal(canalId: Long): OcultacionCanalEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM OcultacionCanal WHERE canalId = :canalId)")
    suspend fun existe(canalId: Long): Int

    @Query("SELECT canalId FROM OcultacionCanal ORDER BY creadoEn DESC, canalId DESC")
    suspend fun canalesOcultos(): List<Long>

    @Query("SELECT COUNT(*) FROM OcultacionCanal")
    suspend fun contar(): Int
}

/** Ocultación de un Grupo concreto; no afecta a Grupos homónimos de otras Listas. */
@Dao
interface OcultacionGrupoDao {
    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun insertar(fila: OcultacionGrupoEntity): Long

    @Query("DELETE FROM OcultacionGrupo WHERE grupoId = :grupoId")
    suspend fun eliminarPorGrupo(grupoId: Long): Int

    @Query("SELECT * FROM OcultacionGrupo WHERE grupoId = :grupoId")
    suspend fun porGrupo(grupoId: Long): OcultacionGrupoEntity?

    @Query("SELECT EXISTS(SELECT 1 FROM OcultacionGrupo WHERE grupoId = :grupoId)")
    suspend fun existe(grupoId: Long): Int

    @Query("SELECT grupoId FROM OcultacionGrupo ORDER BY creadoEn DESC, grupoId DESC")
    suspend fun gruposOcultos(): List<Long>

    @Query("SELECT COUNT(*) FROM OcultacionGrupo")
    suspend fun contar(): Int
}
