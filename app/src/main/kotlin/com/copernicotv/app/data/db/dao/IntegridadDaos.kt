package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.copernicotv.app.data.ColeccionCanalResumen
import com.copernicotv.app.data.HistorialResumen
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.app.data.db.entities.PerfilListaEntity
import kotlinx.coroutines.flow.Flow
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

    /**
     * Acceso rápido de S11c: últimos [limite] Canales en el mismo orden que
     * `ultimos`, con el nombre del Canal y sus marcas actuales.
     *
     * El `INNER JOIN` descarta filas de Historial cuyo Canal ya no existe; la
     * FK con cascada hace que ese caso no ocurra, pero la consulta no depende
     * de ello.
     */
    @Query(
        """
        SELECT c.id AS canalId, c.nombre AS nombre,
            EXISTS(SELECT 1 FROM Favorito f WHERE f.canalId = c.id) AS esFavorito,
            EXISTS(SELECT 1 FROM OcultacionCanal oc WHERE oc.canalId = c.id) AS estaOculto
        FROM Historial h
        INNER JOIN Canal c ON c.id = h.canalId
        ORDER BY h.fecha DESC, h.canalId DESC
        LIMIT :limite
        """,
    )
    fun observarUltimos(limite: Int): Flow<List<HistorialResumen>>
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

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun asociarLista(fila: PerfilListaEntity)

    @Query("SELECT listaId FROM PerfilLista WHERE perfilId = :perfilId")
    suspend fun listasDePerfil(perfilId: Long): List<Long>

    @Query("SELECT listaId FROM PerfilLista WHERE perfilId = :perfilId ORDER BY listaId")
    fun observarListasDePerfil(perfilId: Long): Flow<List<Long>>

    @Query("DELETE FROM PerfilLista WHERE perfilId = :perfilId AND listaId = :listaId")
    suspend fun desasociarLista(
        perfilId: Long,
        listaId: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM PerfilLista")
    suspend fun contarAsociaciones(): Int

    @Query("SELECT * FROM Perfil ORDER BY nombreNormalizado, id")
    fun observarTodos(): Flow<List<PerfilEntity>>

    @Query("SELECT * FROM Perfil ORDER BY nombreNormalizado, id")
    suspend fun listar(): List<PerfilEntity>

    @Query("DELETE FROM Perfil WHERE id = :id")
    suspend fun eliminar(id: Long): Int
}

@Dao
interface ColeccionCanalDao {
    @Insert
    suspend fun insertar(fila: ColeccionCanalEntity): Long

    @Query("SELECT * FROM ColeccionCanal WHERE id = :id")
    suspend fun porId(id: Long): ColeccionCanalEntity?

    @Query("SELECT * FROM ColeccionCanal WHERE coleccionId = :coleccionId ORDER BY posicion, id")
    suspend fun porColeccion(coleccionId: Long): List<ColeccionCanalEntity>

    @Query(
        """
        SELECT cc.id AS id, cc.canalId AS canalId, c.nombre AS nombre, cc.posicion AS posicion
        FROM ColeccionCanal cc
        INNER JOIN Canal c ON c.id = cc.canalId
        WHERE cc.coleccionId = :coleccionId
        ORDER BY cc.posicion, cc.id
        """,
    )
    fun observarPorColeccion(coleccionId: Long): Flow<List<ColeccionCanalResumen>>

    @Query("SELECT * FROM ColeccionCanal WHERE coleccionId = :coleccionId AND canalId = :canalId ORDER BY posicion, id")
    suspend fun porCanal(
        coleccionId: Long,
        canalId: Long,
    ): List<ColeccionCanalEntity>

    @Query("SELECT COALESCE(MAX(posicion), -1) FROM ColeccionCanal WHERE coleccionId = :coleccionId")
    suspend fun maxPosicion(coleccionId: Long): Int

    @Query("UPDATE ColeccionCanal SET posicion = :posicion WHERE id = :id")
    suspend fun actualizarPosicion(
        id: Long,
        posicion: Int,
    ): Int

    @Query("DELETE FROM ColeccionCanal WHERE id = :id")
    suspend fun eliminarFila(id: Long): Int

    @Query("DELETE FROM ColeccionCanal WHERE coleccionId = :coleccionId AND canalId = :canalId")
    suspend fun eliminarPorCanal(
        coleccionId: Long,
        canalId: Long,
    ): Int

    @Query("SELECT COUNT(*) FROM ColeccionCanal")
    suspend fun contar(): Int
}

@Dao
interface ColeccionDao {
    @Insert
    suspend fun insertar(coleccion: ColeccionEntity): Long

    @Query("SELECT * FROM Coleccion WHERE id = :id")
    suspend fun porId(id: Long): ColeccionEntity?

    @Query("SELECT * FROM Coleccion ORDER BY id")
    suspend fun listar(): List<ColeccionEntity>

    @Query("SELECT * FROM Coleccion ORDER BY id")
    fun observarTodas(): Flow<List<ColeccionEntity>>

    @Query("UPDATE Coleccion SET nombre = :nombre, actualizadoEn = :actualizadoEn WHERE id = :id")
    suspend fun renombrar(
        id: Long,
        nombre: String,
        actualizadoEn: Instant,
    ): Int

    @Query("UPDATE Coleccion SET actualizadoEn = :actualizadoEn WHERE id = :id")
    suspend fun tocar(
        id: Long,
        actualizadoEn: Instant,
    ): Int

    @Query("DELETE FROM Coleccion WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT COUNT(*) FROM Coleccion")
    suspend fun contar(): Int
}
