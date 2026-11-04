package com.copernicotv.app.data.db.dao

import androidx.paging.PagingSource
import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.ProgramaEntity
import java.time.Instant

/** Tamaño lógico de una fila `Programa` para el presupuesto de retención. */
data class ProgramaTamano(
    val id: Long,
    val tamano: Long,
)

/** Programación de una generación publicada (E5-T2/T3/T5). */
@Dao
interface ProgramaDao {
    @Insert
    suspend fun insertarTodos(filas: List<ProgramaEntity>)

    @Query(
        "SELECT * FROM Programa WHERE generacionId = :generacionId AND channelIdTexto = :channelId " +
            "AND fin > :ahora ORDER BY inicio ASC, id ASC",
    )
    suspend fun desde(
        generacionId: Long,
        channelId: String,
        ahora: Instant,
    ): List<ProgramaEntity>

    @Query(
        "SELECT * FROM Programa WHERE generacionId = :generacionId AND channelIdTexto = :channelId " +
            "AND inicio <= :ahora AND fin > :ahora ORDER BY inicio ASC, id ASC LIMIT 1",
    )
    suspend fun actual(
        generacionId: Long,
        channelId: String,
        ahora: Instant,
    ): ProgramaEntity?

    @Query(
        "SELECT * FROM Programa WHERE generacionId = :generacionId AND channelIdTexto = :channelId " +
            "AND inicio > :ahora ORDER BY inicio ASC, id ASC LIMIT 1",
    )
    suspend fun siguiente(
        generacionId: Long,
        channelId: String,
        ahora: Instant,
    ): ProgramaEntity?

    @Query(
        "SELECT * FROM Programa WHERE generacionId = :generacionId AND channelIdTexto = :channelId " +
            "AND fin > :desde AND inicio < :hasta ORDER BY inicio ASC, id ASC",
    )
    suspend fun enIntervalo(
        generacionId: Long,
        channelId: String,
        desde: Instant,
        hasta: Instant,
    ): List<ProgramaEntity>

    @Query(
        "SELECT * FROM Programa WHERE generacionId = :generacionId AND channelIdTexto = :channelId " +
            "AND fin > :desde AND inicio < :hasta ORDER BY inicio ASC, id ASC",
    )
    fun paginado(
        generacionId: Long,
        channelId: String,
        desde: Instant,
        hasta: Instant,
    ): PagingSource<Int, ProgramaEntity>

    @Query("SELECT COUNT(*) FROM Programa WHERE generacionId = :generacionId")
    suspend fun contar(generacionId: Long): Int

    @Query("SELECT COUNT(*) FROM Programa WHERE generacionId = :generacionId AND channelIdTexto = :channelId")
    suspend fun contarCanal(
        generacionId: Long,
        channelId: String,
    ): Int

    /** Canales XMLTV que aportaron al menos un programa a la generación. */
    @Query("SELECT DISTINCT channelIdTexto FROM Programa WHERE generacionId = :generacionId ORDER BY channelIdTexto")
    suspend fun canales(generacionId: Long): List<String>

    /** Aplica la ventana temporal de retención; conserva lo que solapa el borde. */
    @Query("DELETE FROM Programa WHERE generacionId = :generacionId AND NOT (fin > :desde AND inicio < :hasta)")
    suspend fun eliminarFueraDeVentana(
        generacionId: Long,
        desde: Instant,
        hasta: Instant,
    ): Int

    /** Conserva los `maximo` programas más nuevos de un canal y evicta el resto. */
    @Query(
        "DELETE FROM Programa WHERE generacionId = :generacionId AND channelIdTexto = :channelId " +
            "AND id NOT IN (SELECT id FROM Programa WHERE generacionId = :generacionId " +
            "AND channelIdTexto = :channelId ORDER BY inicio DESC, id DESC LIMIT :maximo)",
    )
    suspend fun recortarCanal(
        generacionId: Long,
        channelId: String,
        maximo: Int,
    ): Int

    /**
     * Filas con su tamaño lógico serializado, de la más antigua a la más nueva.
     *
     * `length(CAST(x AS BLOB))` cuenta bytes UTF-8, no caracteres. El cálculo
     * replica el presupuesto de E5-T3 sin contar PK, FKs, índices ni páginas.
     */
    @Query(
        "SELECT id, 4 + length(CAST(channelIdTexto AS BLOB)) + 4 + length(CAST(titulo AS BLOB)) + " +
            "4 + length(CAST(COALESCE(descripcion, '') AS BLOB)) + 16 AS tamano " +
            "FROM Programa WHERE generacionId = :generacionId ORDER BY inicio ASC, id ASC",
    )
    suspend fun tamanosOrdenados(generacionId: Long): List<ProgramaTamano>

    @Query("DELETE FROM Programa WHERE id IN (:ids)")
    suspend fun eliminarPorIds(ids: List<Long>): Int

    @Query("DELETE FROM Programa WHERE generacionId = :generacionId")
    suspend fun eliminarPorGeneracion(generacionId: Long): Int
}
