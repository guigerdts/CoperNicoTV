package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.FuenteEntity
import java.time.Instant

@Dao
interface FuenteDao {
    @Insert
    suspend fun insertar(fuente: FuenteEntity): Long

    @Query("SELECT * FROM Fuente WHERE id = :id")
    suspend fun porId(id: Long): FuenteEntity?

    @Query("SELECT * FROM Fuente WHERE nombre = :nombre LIMIT 1")
    suspend fun porNombre(nombre: String): FuenteEntity?

    @Query("SELECT * FROM Fuente ORDER BY nombre")
    suspend fun todas(): List<FuenteEntity>

    @Query("SELECT COUNT(*) FROM Fuente")
    suspend fun contar(): Int

    /**
     * Renombra una Fuente al editar su conexión.
     *
     * El nombre de la Fuente es el que agrupa los Canales; cambiarlo no toca el
     * catálogo ni la identidad de los Canales, que no dependen de él.
     */
    @Query("UPDATE Fuente SET nombre = :nombre, actualizadoEn = :ahora WHERE id = :id")
    suspend fun renombrar(
        id: Long,
        nombre: String,
        ahora: Instant,
    ): Int

    @Query(
        """
        UPDATE Fuente SET intervaloMinutos = :intervaloMinutos, usarIntervaloGlobal = :usarGlobal,
            estado = :estado, actualizadoEn = :ahora WHERE id = :id
        """,
    )
    suspend fun actualizarProgramacion(
        id: Long,
        intervaloMinutos: Long?,
        usarGlobal: Boolean,
        estado: String,
        ahora: Instant,
    ): Int

    @Query("DELETE FROM Fuente WHERE id = :id")
    suspend fun eliminar(id: Long)
}
