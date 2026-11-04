package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.CacheM3UEntity
import com.copernicotv.app.data.db.entities.CacheXmltvEntity
import java.time.Instant

/**
 * Acceso a los metadatos de caché de transporte M3U (DC-09, S51d).
 *
 * La clave lógica `(fuenteId, listaId, tipo, urlHmac)` la garantiza el índice
 * único de la tabla; el repositorio lee por clave antes de escribir para no
 * cambiar el `id` interno de una fila existente. El contenido nunca vive en
 * Room: `archivoRef` apunta a un nombre opaco del almacén privado.
 */
@Dao
interface CacheM3UDao {
    @Query(
        "SELECT * FROM CacheM3U " +
            "WHERE fuenteId = :fuenteId AND listaId = :listaId AND tipo = :tipo AND urlHmac = :urlHmac",
    )
    suspend fun porClave(
        fuenteId: Long,
        listaId: Long,
        tipo: String,
        urlHmac: ByteArray,
    ): CacheM3UEntity?

    @Query("SELECT * FROM CacheM3U")
    suspend fun todas(): List<CacheM3UEntity>

    @Query("SELECT * FROM CacheM3U WHERE listaId = :listaId AND tipo = :tipo")
    suspend fun porLista(
        listaId: Long,
        tipo: String,
    ): List<CacheM3UEntity>

    @Query("SELECT * FROM CacheM3U WHERE fuenteId = :fuenteId")
    suspend fun porFuente(fuenteId: Long): List<CacheM3UEntity>

    @Insert
    suspend fun insertar(fila: CacheM3UEntity): Long

    @Query(
        "UPDATE CacheM3U SET urlCifrada = :urlCifrada, urlHmac = :urlHmac, " +
            "fetchedAt = :fetchedAt, expiresAt = :expiresAt, tamanoBytes = :tamanoBytes, " +
            "estadoFrescura = :estadoFrescura, archivoRef = :archivoRef WHERE id = :id",
    )
    suspend fun actualizar(
        id: Long,
        urlCifrada: ByteArray,
        urlHmac: ByteArray,
        fetchedAt: Instant,
        expiresAt: Instant,
        tamanoBytes: Long,
        estadoFrescura: String,
        archivoRef: String,
    ): Int

    @Query("DELETE FROM CacheM3U WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT COUNT(*) FROM CacheM3U")
    suspend fun contar(): Long
}

/**
 * Acceso a los metadatos de caché de transporte XMLTV (DC-09, S51d).
 *
 * Clave lógica `(fuenteGuiaId, tipo, urlHmac)`. XMLTV es copia de transporte,
 * nunca fuente de verdad de la EPG publicada (S51f).
 */
@Dao
interface CacheXmltvDao {
    @Query(
        "SELECT * FROM CacheXmltv " +
            "WHERE fuenteGuiaId = :fuenteGuiaId AND tipo = :tipo AND urlHmac = :urlHmac",
    )
    suspend fun porClave(
        fuenteGuiaId: Long,
        tipo: String,
        urlHmac: ByteArray,
    ): CacheXmltvEntity?

    @Query("SELECT * FROM CacheXmltv")
    suspend fun todas(): List<CacheXmltvEntity>

    @Query("SELECT * FROM CacheXmltv WHERE fuenteGuiaId = :fuenteGuiaId AND tipo = :tipo")
    suspend fun porFuenteGuia(
        fuenteGuiaId: Long,
        tipo: String,
    ): List<CacheXmltvEntity>

    @Insert
    suspend fun insertar(fila: CacheXmltvEntity): Long

    @Query(
        "UPDATE CacheXmltv SET urlCifrada = :urlCifrada, urlHmac = :urlHmac, " +
            "fetchedAt = :fetchedAt, expiresAt = :expiresAt, tamanoBytes = :tamanoBytes, " +
            "estadoFrescura = :estadoFrescura, archivoRef = :archivoRef WHERE id = :id",
    )
    suspend fun actualizar(
        id: Long,
        urlCifrada: ByteArray,
        urlHmac: ByteArray,
        fetchedAt: Instant,
        expiresAt: Instant,
        tamanoBytes: Long,
        estadoFrescura: String,
        archivoRef: String,
    ): Int

    @Query("DELETE FROM CacheXmltv WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT COUNT(*) FROM CacheXmltv")
    suspend fun contar(): Long
}
