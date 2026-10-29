package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.app.data.db.entities.PerfilListaEntity
import java.time.Instant

@Dao
interface HistorialDao {
    @Insert
    suspend fun insertar(fila: HistorialEntity): Long

    @Query("SELECT * FROM Historial WHERE canalId = :canalId")
    suspend fun porCanal(canalId: Long): HistorialEntity?

    @Query("UPDATE Historial SET fecha = :fecha WHERE canalId = :canalId")
    suspend fun actualizarFecha(
        canalId: Long,
        fecha: Instant,
    ): Int

    /**
     * Recorta el Historial persistido a los [limite] Canales más recientes.
     *
     * El orden del subconsulta es el mismo de `ultimos` y del índice
     * `(fecha, canalId)`, para que el recorte no dependa de un orden distinto.
     */
    @Query(
        """
        DELETE FROM Historial
        WHERE id NOT IN (
            SELECT id FROM Historial ORDER BY fecha DESC, canalId DESC LIMIT :limite
        )
        """,
    )
    suspend fun recortar(limite: Int): Int

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

    @Query("SELECT * FROM Perfil WHERE id = :id")
    suspend fun porId(id: Long): PerfilEntity?

    @Query(
        "UPDATE Perfil SET nombre = :nombre, nombreNormalizado = :normalizado, " +
            "actualizadoEn = :actualizadoEn WHERE id = :id",
    )
    suspend fun actualizarNombre(
        id: Long,
        nombre: String,
        normalizado: String,
        actualizadoEn: Instant,
    ): Int

    @Query("SELECT COUNT(*) FROM Perfil")
    suspend fun contar(): Int

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
