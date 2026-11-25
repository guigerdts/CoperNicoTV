package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import java.time.Instant

/**
 * Fuentes de guía EPG.
 *
 * La tabla existía desde el esquema v1 sin DAO. Se añade el mínimo que la
 * fusión de Canales necesita para satisfacer la FK de `Asociacion`.
 */
@Dao
interface FuenteGuiaDao {
    @Insert
    suspend fun insertar(fila: FuenteGuiaEntity): Long

    @Query("SELECT * FROM FuenteGuia WHERE id = :id")
    suspend fun porId(id: Long): FuenteGuiaEntity?

    @Query("SELECT * FROM FuenteGuia ORDER BY id")
    suspend fun todas(): List<FuenteGuiaEntity>

    @Query(
        "UPDATE FuenteGuia SET generacionPublicadaId = :generacionId, actualizadoEn = :ahora WHERE id = :id",
    )
    suspend fun fijarPublicada(
        id: Long,
        generacionId: Long,
        ahora: Instant,
    ): Int

    @Query(
        "UPDATE FuenteGuia SET retencionVentanaPasadoDias = :ventanaPasadoDias, " +
            "retencionVentanaFuturoDias = :ventanaFuturoDias, retencionMaxProgramas = :maxProgramas, " +
            "retencionPresupuestoBytes = :presupuestoBytes, usarRetencionGlobal = :usarGlobal, " +
            "actualizadoEn = :ahora WHERE id = :id",
    )
    suspend fun actualizarRetencion(
        id: Long,
        ventanaPasadoDias: Long?,
        ventanaFuturoDias: Long?,
        maxProgramas: Long?,
        presupuestoBytes: Long?,
        usarGlobal: Boolean,
        ahora: Instant,
    ): Int

    /** Guía administrada por una conexión (`conexionId`), si existe. */
    @Query("SELECT * FROM FuenteGuia WHERE conexionId = :conexionId ORDER BY id LIMIT 1")
    suspend fun porConexion(conexionId: Long): FuenteGuiaEntity?

    /**
     * Guía que la propia conexión creó (`origen` `xtream` o `m3u`).
     *
     * Una guía `manual` puede estar vinculada a la misma conexión; no se toca
     * al editar el perfil porque la administra el usuario por separado.
     */
    @Query(
        "SELECT * FROM FuenteGuia WHERE conexionId = :conexionId AND origen IN ('xtream', 'm3u') " +
            "ORDER BY id LIMIT 1",
    )
    suspend fun porConexionAdministrada(conexionId: Long): FuenteGuiaEntity?

    /**
     * Reescribe la URL de una guía administrada por una conexión.
     *
     * El estado vuelve a `sin_actualizar`: la URL cambió, así que la guía
     * publicada ya no corresponde a la fuente y la próxima ejecución debe
     * intentarlo de nuevo antes de declararla vigente.
     */
    @Query(
        """
        UPDATE FuenteGuia SET urlCifrada = :urlCifrada, urlHmac = :urlHmac, origen = :origen,
            estado = 'sin_actualizar', ultimoErrorCodigo = NULL, ultimoErrorMensaje = NULL,
            actualizadoEn = :ahora WHERE id = :id
        """,
    )
    suspend fun actualizarUrlDesdeConexion(
        id: Long,
        urlCifrada: ByteArray?,
        urlHmac: ByteArray?,
        origen: String,
        ahora: Instant,
    ): Int

    /** Desvincula las guías de una conexión sin borrarlas. */
    @Query("UPDATE FuenteGuia SET conexionId = NULL, actualizadoEn = :ahora WHERE conexionId = :conexionId")
    suspend fun desvincularConexion(
        conexionId: Long,
        ahora: Instant,
    ): Int

    @Query("DELETE FROM FuenteGuia WHERE id = :id")
    suspend fun eliminar(id: Long): Int

    @Query("SELECT COUNT(*) FROM FuenteGuia")
    suspend fun contar(): Int
}
