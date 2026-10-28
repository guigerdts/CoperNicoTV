package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Upsert
import com.copernicotv.app.data.db.entities.EstadoIndiceHmacEntity
import com.copernicotv.app.data.db.entities.HmacPendienteEntity
import java.time.Instant

/**
 * Acceso a la fila singleton de `EstadoIndiceHmac` (`id = 1`).
 *
 * La tabla no admite más de una fila: la semilla de `AppModule` la crea al
 * abrir la base y [guardar] siempre escribe `id = 1`.
 */
@Dao
interface EstadoIndiceHmacDao {
    @Query("SELECT * FROM EstadoIndiceHmac WHERE id = 1")
    suspend fun actual(): EstadoIndiceHmacEntity?

    @Upsert
    suspend fun guardar(fila: EstadoIndiceHmacEntity)

    @Query("SELECT COUNT(*) FROM EstadoIndiceHmac")
    suspend fun contar(): Int
}

/**
 * Acceso a las filas no reconstruibles del índice HMAC.
 *
 * La clave única `(entidad, entidadId, columna)` la garantiza el índice de la
 * tabla; el repositorio resuelve el upsert leyendo primero para no cambiar el
 * `id` interno de una fila ya registrada.
 */
@Dao
interface HmacPendienteDao {
    @Query(
        "SELECT * FROM HmacPendiente " +
            "WHERE entidad = :entidad AND entidadId = :entidadId AND columna = :columna",
    )
    suspend fun porClave(
        entidad: String,
        entidadId: Long,
        columna: String,
    ): HmacPendienteEntity?

    @Insert
    suspend fun insertar(fila: HmacPendienteEntity): Long

    @Query(
        "UPDATE HmacPendiente SET versionOrigen = :versionOrigen, motivo = :motivo, " +
            "actualizadoEn = :actualizadoEn WHERE id = :id",
    )
    suspend fun actualizar(
        id: Long,
        versionOrigen: String,
        motivo: String,
        actualizadoEn: Instant,
    ): Int

    @Query("SELECT * FROM HmacPendiente ORDER BY entidad, entidadId, columna")
    suspend fun todas(): List<HmacPendienteEntity>

    @Query("SELECT COUNT(*) FROM HmacPendiente")
    suspend fun contar(): Long

    @Query(
        "DELETE FROM HmacPendiente " +
            "WHERE entidad = :entidad AND entidadId = :entidadId AND columna = :columna",
    )
    suspend fun eliminar(
        entidad: String,
        entidadId: Long,
        columna: String,
    ): Int

    @Query("DELETE FROM HmacPendiente")
    suspend fun eliminarTodas()
}
