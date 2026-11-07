package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.copernicotv.app.data.db.entities.CacheM3UEntity
import com.copernicotv.app.data.db.entities.CacheXmltvEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.ObservacionPendienteImportacionEntity

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
@Suppress("TooManyFunctions")
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

    /** Sobres HMAC persistidos en todo el esquema: base para distinguir inicialización de pérdida. */
    @Query("SELECT COUNT(*) FROM Canal WHERE urlStreamHmac IS NOT NULL")
    suspend fun contarSobresCanal(): Long

    @Query("SELECT COUNT(*) FROM Canal WHERE logoUrlHmac IS NOT NULL")
    suspend fun contarSobresLogo(): Long

    @Query("SELECT COUNT(*) FROM Lista WHERE ubicacionHmac IS NOT NULL")
    suspend fun contarSobresLista(): Long

    @Query("SELECT COUNT(*) FROM ObservacionPendienteImportacion WHERE urlHmac IS NOT NULL")
    suspend fun contarSobresObservacion(): Long

    @Query("SELECT COUNT(*) FROM ObservacionPendienteImportacion WHERE logoUrlHmac IS NOT NULL")
    suspend fun contarSobresObservacionLogo(): Long

    @Query("SELECT COUNT(*) FROM CacheM3U WHERE urlHmac IS NOT NULL")
    suspend fun contarSobresCacheM3u(): Long

    @Query("SELECT COUNT(*) FROM CacheXmltv WHERE urlHmac IS NOT NULL")
    suspend fun contarSobresCacheXmltv(): Long

    @Query("SELECT COUNT(*) FROM FuenteGuia WHERE urlHmac IS NOT NULL")
    suspend fun contarSobresFuenteGuia(): Long

    @Query("SELECT COALESCE(MAX(id), 0) FROM ObservacionPendienteImportacion")
    suspend fun maximoIdObservaciones(): Long

    @Query("SELECT COUNT(*) FROM ObservacionPendienteImportacion WHERE id <= :maxId AND urlHmac IS NOT NULL")
    suspend fun contarObservaciones(maxId: Long): Long

    @Query("SELECT COUNT(*) FROM ObservacionPendienteImportacion WHERE id <= :maxId AND logoUrlHmac IS NOT NULL")
    suspend fun contarObservacionesConLogo(maxId: Long): Long

    @Query(
        "SELECT * FROM ObservacionPendienteImportacion WHERE id > :ultimoId AND id <= :maxId " +
            "ORDER BY id LIMIT :limite",
    )
    suspend fun loteObservaciones(
        ultimoId: Long,
        maxId: Long,
        limite: Int,
    ): List<ObservacionPendienteImportacionEntity>

    @Query("UPDATE ObservacionPendienteImportacion SET urlHmac = :hmac WHERE id = :id")
    suspend fun actualizarUrlObservacion(
        id: Long,
        hmac: ByteArray,
    ): Int

    @Query("UPDATE ObservacionPendienteImportacion SET logoUrlHmac = :hmac WHERE id = :id")
    suspend fun actualizarLogoObservacion(
        id: Long,
        hmac: ByteArray,
    ): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM CacheM3U")
    suspend fun maximoIdCacheM3u(): Long

    @Query("SELECT COUNT(*) FROM CacheM3U WHERE id <= :maxId AND urlHmac IS NOT NULL")
    suspend fun contarCacheM3u(maxId: Long): Long

    @Query(
        "SELECT * FROM CacheM3U WHERE id > :ultimoId AND id <= :maxId " +
            "ORDER BY id LIMIT :limite",
    )
    suspend fun loteCacheM3u(
        ultimoId: Long,
        maxId: Long,
        limite: Int,
    ): List<CacheM3UEntity>

    @Query("UPDATE CacheM3U SET urlHmac = :hmac WHERE id = :id")
    suspend fun actualizarUrlCacheM3u(
        id: Long,
        hmac: ByteArray,
    ): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM CacheXmltv")
    suspend fun maximoIdCacheXmltv(): Long

    @Query("SELECT COUNT(*) FROM CacheXmltv WHERE id <= :maxId AND urlHmac IS NOT NULL")
    suspend fun contarCacheXmltv(maxId: Long): Long

    @Query(
        "SELECT * FROM CacheXmltv WHERE id > :ultimoId AND id <= :maxId " +
            "ORDER BY id LIMIT :limite",
    )
    suspend fun loteCacheXmltv(
        ultimoId: Long,
        maxId: Long,
        limite: Int,
    ): List<CacheXmltvEntity>

    @Query("UPDATE CacheXmltv SET urlHmac = :hmac WHERE id = :id")
    suspend fun actualizarUrlCacheXmltv(
        id: Long,
        hmac: ByteArray,
    ): Int

    @Query("SELECT COALESCE(MAX(id), 0) FROM FuenteGuia")
    suspend fun maximoIdFuentesGuia(): Long

    @Query("SELECT COUNT(*) FROM FuenteGuia WHERE id <= :maxId AND urlHmac IS NOT NULL")
    suspend fun contarFuentesGuia(maxId: Long): Long

    @Query(
        "SELECT * FROM FuenteGuia WHERE id > :ultimoId AND id <= :maxId " +
            "ORDER BY id LIMIT :limite",
    )
    suspend fun loteFuentesGuia(
        ultimoId: Long,
        maxId: Long,
        limite: Int,
    ): List<FuenteGuiaEntity>

    @Query("UPDATE FuenteGuia SET urlHmac = :hmac WHERE id = :id")
    suspend fun actualizarUrlFuenteGuia(
        id: Long,
        hmac: ByteArray,
    ): Int

    /** Escrituras de reingreso: restauran valor original (cifrado) y sobre juntos. */

    @Query("UPDATE Canal SET urlStreamCifrada = :cifrado, urlStreamHmac = :hmac WHERE id = :id")
    suspend fun reingresarUrlCanal(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int

    @Query("UPDATE Canal SET logoUrlCifrada = :cifrado, logoUrlHmac = :hmac WHERE id = :id")
    suspend fun reingresarLogoCanal(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int

    @Query("UPDATE Lista SET ubicacionCifrada = :cifrado, ubicacionHmac = :hmac WHERE id = :id")
    suspend fun reingresarUbicacionLista(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int

    @Query(
        "UPDATE ObservacionPendienteImportacion SET urlCifrada = :cifrado, urlHmac = :hmac WHERE id = :id",
    )
    suspend fun reingresarUrlObservacion(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int

    @Query(
        "UPDATE ObservacionPendienteImportacion SET logoUrlCifrada = :cifrado, logoUrlHmac = :hmac WHERE id = :id",
    )
    suspend fun reingresarLogoObservacion(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int

    @Query("UPDATE CacheM3U SET urlCifrada = :cifrado, urlHmac = :hmac WHERE id = :id")
    suspend fun reingresarUrlCacheM3u(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int

    @Query("UPDATE CacheXmltv SET urlCifrada = :cifrado, urlHmac = :hmac WHERE id = :id")
    suspend fun reingresarUrlCacheXmltv(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int

    @Query("UPDATE FuenteGuia SET urlCifrada = :cifrado, urlHmac = :hmac WHERE id = :id")
    suspend fun reingresarUrlFuenteGuia(
        id: Long,
        cifrado: ByteArray,
        hmac: ByteArray,
    ): Int
}
