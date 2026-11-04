package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import java.time.Instant

/** Acceso al historial de intentos de actualización de una Lista (E2-T2). */
@Dao
interface EstadoActualizacionDao {
    @Insert
    suspend fun insertar(estado: EstadoActualizacionEntity): Long

    /** Cierra un intento con su resultado contractual, o con el código de error. */
    @Query("UPDATE EstadoActualizacion SET fin = :fin, resultado = :resultado WHERE id = :id")
    suspend fun finalizar(
        id: Long,
        fin: Instant,
        resultado: String,
    ): Int

    /** Último intento registrado para una Lista, por inicio descendente. */
    @Query("SELECT * FROM EstadoActualizacion WHERE listaId = :listaId ORDER BY inicio DESC, id DESC LIMIT 1")
    suspend fun ultimoDeLista(listaId: Long): EstadoActualizacionEntity?

    @Query("SELECT * FROM EstadoActualizacion WHERE id = :id")
    suspend fun porId(id: Long): EstadoActualizacionEntity?

    @Query("SELECT * FROM EstadoActualizacion WHERE listaId = :listaId ORDER BY inicio DESC, id DESC")
    suspend fun porLista(listaId: Long): List<EstadoActualizacionEntity>

    @Query("SELECT COUNT(*) FROM EstadoActualizacion")
    suspend fun contar(): Int
}
