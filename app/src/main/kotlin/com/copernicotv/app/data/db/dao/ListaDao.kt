package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.ListaEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

@Dao
interface ListaDao {
    @Insert
    suspend fun insertar(lista: ListaEntity): Long

    @Query("SELECT * FROM Lista WHERE id = :id")
    suspend fun porId(id: Long): ListaEntity?

    @Query("SELECT * FROM Lista WHERE fuenteId = :fuenteId ORDER BY orden, id")
    suspend fun porFuente(fuenteId: Long): List<ListaEntity>

    @Query("SELECT * FROM Lista ORDER BY orden, id")
    suspend fun listar(): List<ListaEntity>

    @Query("SELECT * FROM Lista ORDER BY orden, id")
    fun observarTodas(): Flow<List<ListaEntity>>

    @Query("SELECT * FROM Lista WHERE estado = 'activa' ORDER BY id DESC LIMIT 1")
    suspend fun activa(): ListaEntity?

    @Query("UPDATE Lista SET estado = 'inactiva'")
    suspend fun desactivarTodas(): Int

    @Query("UPDATE Lista SET estado = 'activa', actualizadoEn = :actualizadoEn WHERE id = :id")
    suspend fun activar(
        id: Long,
        actualizadoEn: Instant,
    ): Int

    @Query("UPDATE Lista SET nombre = :nombre, actualizadoEn = :actualizadoEn WHERE id = :id")
    suspend fun renombrar(
        id: Long,
        nombre: String,
        actualizadoEn: Instant,
    ): Int

    /**
     * Reemplaza la ubicación de una Lista existente sin tocar su identidad.
     *
     * Es la edición de una conexión (S5): cambia la URL, nunca la Lista, así que
     * favoritos, historial, ocultos y asociaciones siguen apuntando a los mismos
     * canales de la misma fuente.
     */
    @Query(
        """
        UPDATE Lista SET ubicacionCifrada = :ubicacionCifrada, ubicacionHmac = :ubicacionHmac,
            esquema = :esquema, actualizadoEn = :actualizadoEn WHERE id = :id
        """,
    )
    suspend fun actualizarUbicacion(
        id: Long,
        ubicacionCifrada: ByteArray,
        ubicacionHmac: ByteArray,
        esquema: String,
        actualizadoEn: Instant,
    ): Int

    @Query("DELETE FROM Lista WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT COALESCE(MAX(orden), -1) FROM Lista")
    suspend fun maxOrden(): Int

    @Query("SELECT COUNT(*) FROM Lista")
    suspend fun contar(): Int

    @Query("SELECT COUNT(*) FROM Lista WHERE fuenteId = :fuenteId")
    suspend fun contarPorFuente(fuenteId: Long): Int
}
