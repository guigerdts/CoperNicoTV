package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.app.data.db.entities.PerfilListaEntity

@Dao
interface HistorialDao {
    @Insert
    suspend fun insertar(fila: HistorialEntity): Long

    @Query("SELECT * FROM Historial WHERE canalId = :canalId")
    suspend fun porCanal(canalId: Long): HistorialEntity?

    @Query("SELECT COUNT(*) FROM Historial")
    suspend fun contar(): Int

    @Query("SELECT * FROM Historial ORDER BY fecha DESC, canalId DESC LIMIT :limite")
    suspend fun ultimos(limite: Int): List<HistorialEntity>
}

@Dao
interface PerfilDao {
    @Insert
    suspend fun insertar(perfil: PerfilEntity): Long

    @Query("SELECT * FROM Perfil WHERE nombreNormalizado = :normalizado")
    suspend fun porNombreNormalizado(normalizado: String): PerfilEntity?

    @Insert
    suspend fun asociarLista(fila: PerfilListaEntity)

    @Query("SELECT listaId FROM PerfilLista WHERE perfilId = :perfilId")
    suspend fun listasDePerfil(perfilId: Long): List<Long>

    @Query("SELECT COUNT(*) FROM PerfilLista")
    suspend fun contarAsociaciones(): Int

    @Query("DELETE FROM Perfil WHERE id = :id")
    suspend fun eliminar(id: Long)
}

@Dao
interface ColeccionCanalDao {
    @Insert
    suspend fun insertar(fila: ColeccionCanalEntity): Long

    @Query("SELECT * FROM ColeccionCanal WHERE coleccionId = :coleccionId ORDER BY id")
    suspend fun porColeccion(coleccionId: Long): List<ColeccionCanalEntity>

    @Query("SELECT COUNT(*) FROM ColeccionCanal")
    suspend fun contar(): Int
}

@Dao
interface ColeccionDao {
    @Insert
    suspend fun insertar(coleccion: ColeccionEntity): Long

    @Query("SELECT COUNT(*) FROM Coleccion")
    suspend fun contar(): Int
}
