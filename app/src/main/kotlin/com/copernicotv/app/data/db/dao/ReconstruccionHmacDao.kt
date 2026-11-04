package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ListaEntity

/**
 * Consultas de la pasada de reconstrucción del índice HMAC (E2-T6).
 *
 * Se mantienen separadas de `CanalDao` y `ListaDao` para que la lectura por
 * lotes y las escrituras de columnas HMAC tengan un único dueño. Las escrituras
 * no tocan `actualizadoEn`: el HMAC es dato derivado y la reconstrucción no es
 * un cambio de contenido del Canal ni de la Lista.
 *
 * La pasada fija un límite superior de `id` al empezar y lo usa en conteos y
 * lotes: las filas insertadas durante la reconstrucción quedan fuera de esa
 * pasada y no alteran sus conteos.
 */
@Dao
interface ReconstruccionHmacDao {
    @Query("SELECT COALESCE(MAX(id), 0) FROM Canal")
    suspend fun maximoIdCanal(): Long

    @Query("SELECT COALESCE(MAX(id), 0) FROM Lista")
    suspend fun maximoIdLista(): Long

    @Query("SELECT COUNT(*) FROM Canal WHERE id <= :maxId")
    suspend fun contarCanales(maxId: Long): Long

    @Query("SELECT COUNT(*) FROM Canal WHERE id <= :maxId AND logoUrlHmac IS NOT NULL")
    suspend fun contarCanalesConLogo(maxId: Long): Long

    @Query(
        "SELECT * FROM Canal WHERE id > :ultimoId AND id <= :maxId " +
            "ORDER BY id LIMIT :limite",
    )
    suspend fun loteCanales(
        ultimoId: Long,
        maxId: Long,
        limite: Int,
    ): List<CanalEntity>

    @Query("UPDATE Canal SET urlStreamHmac = :hmac WHERE id = :id")
    suspend fun actualizarUrlStreamHmac(
        id: Long,
        hmac: ByteArray,
    ): Int

    @Query("UPDATE Canal SET logoUrlHmac = :hmac WHERE id = :id")
    suspend fun actualizarLogoHmac(
        id: Long,
        hmac: ByteArray,
    ): Int

    @Query("SELECT COUNT(*) FROM Lista WHERE id <= :maxId")
    suspend fun contarListas(maxId: Long): Long

    @Query(
        "SELECT * FROM Lista WHERE id > :ultimoId AND id <= :maxId " +
            "ORDER BY id LIMIT :limite",
    )
    suspend fun loteListas(
        ultimoId: Long,
        maxId: Long,
        limite: Int,
    ): List<ListaEntity>

    @Query("UPDATE Lista SET ubicacionHmac = :hmac WHERE id = :id")
    suspend fun actualizarUbicacionHmac(
        id: Long,
        hmac: ByteArray,
    ): Int
}
