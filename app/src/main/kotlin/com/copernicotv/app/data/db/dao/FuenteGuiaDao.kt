package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/**
 * Vista administrativa de una guía: sin BLOB de URL ni secretos.
 *
 * Los campos nulos del esquema se normalizan aquí (activa, automática,
 * intervalo, intentos) para que la interfaz no repita la interpretación.
 */
data class FuenteGuiaResumen(
    val id: Long,
    val nombre: String,
    val origen: String?,
    val estado: String?,
    val conexionId: Long?,
    val ultimaActualizacionEn: Instant?,
    val ultimoErrorCodigo: String?,
    val ultimoErrorMensaje: String?,
    val intentosFallidos: Long?,
    val activa: Boolean?,
    val actualizacionAutomatica: Boolean?,
    val intervaloHoras: Long?,
    val generacionPublicadaId: Long?,
    val urlPresente: Boolean,
)

/**
 * Fuentes de guía EPG.
 *
 * La tabla existía desde el esquema v1 sin DAO. Se añade el mínimo que la
 * fusión de Canales necesita para satisfacer la FK de `Asociacion`, más la
 * administración de guías de S7.
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

    /** Guías visibles para administración, sin BLOB ni secretos. */
    @Query(
        """
        SELECT id, nombre, origen, estado, conexionId, ultimaActualizacionEn,
            ultimoErrorCodigo, ultimoErrorMensaje, intentosFallidos, activa,
            actualizacionAutomatica, intervaloHoras, generacionPublicadaId,
            urlCifrada IS NOT NULL AS urlPresente
        FROM FuenteGuia
        ORDER BY nombre COLLATE NOCASE, id
        """,
    )
    fun observarResumenes(): Flow<List<FuenteGuiaResumen>>

    /** Guías activas; `activa` nulo equivale a activa. */
    @Query("SELECT * FROM FuenteGuia WHERE COALESCE(activa, 1) = 1 ORDER BY id")
    suspend fun activas(): List<FuenteGuiaEntity>

    /** Guías activas con actualización automática y URL disponible. */
    @Query(
        """
        SELECT * FROM FuenteGuia
        WHERE COALESCE(activa, 1) = 1
            AND COALESCE(actualizacionAutomatica, 0) = 1
            AND urlCifrada IS NOT NULL
        ORDER BY id
        """,
    )
    suspend fun automaticas(): List<FuenteGuiaEntity>

    /**
     * Registra el desenlace de una actualización.
     *
     * `intentosFallidos` es la fuente de verdad del reintento controlado: el
     * éxito lo vuelve a cero y el fallo lo incrementa.
     */
    @Query(
        """
        UPDATE FuenteGuia SET estado = :estado, ultimaActualizacionEn = :ultimaActualizacionEn,
            ultimoErrorCodigo = :codigo, ultimoErrorMensaje = :mensaje,
            intentosFallidos = :intentos, actualizadoEn = :ahora
        WHERE id = :id
        """,
    )
    suspend fun fijarEstado(
        id: Long,
        estado: String,
        ultimaActualizacionEn: Instant?,
        codigo: String?,
        mensaje: String?,
        intentos: Long,
        ahora: Instant,
    ): Int

    @Query("UPDATE FuenteGuia SET activa = :activa, actualizadoEn = :ahora WHERE id = :id")
    suspend fun cambiarActiva(
        id: Long,
        activa: Boolean,
        ahora: Instant,
    ): Int

    /** Vincula o desvincula la guía de una conexión sin tocar su URL. */
    @Query("UPDATE FuenteGuia SET conexionId = :conexionId, actualizadoEn = :ahora WHERE id = :id")
    suspend fun cambiarConexion(
        id: Long,
        conexionId: Long?,
        ahora: Instant,
    ): Int

    @Query(
        """
        UPDATE FuenteGuia SET nombre = :nombre, actualizacionAutomatica = :automatica,
            intervaloHoras = :intervaloHoras, actualizadoEn = :ahora
        WHERE id = :id
        """,
    )
    suspend fun actualizarConfiguracion(
        id: Long,
        nombre: String,
        automatica: Boolean,
        intervaloHoras: Long?,
        ahora: Instant,
    ): Int

    /**
     * Reescribe la URL de una guía editada a mano.
     *
     * Como en la edición desde una conexión, el estado vuelve a
     * `sin_actualizar`: la guía publicada ya no corresponde a la fuente.
     */
    @Query(
        """
        UPDATE FuenteGuia SET urlCifrada = :urlCifrada, urlHmac = :urlHmac,
            estado = 'sin_actualizar', ultimoErrorCodigo = NULL, ultimoErrorMensaje = NULL,
            intentosFallidos = 0, actualizadoEn = :ahora
        WHERE id = :id
        """,
    )
    suspend fun actualizarUrl(
        id: Long,
        urlCifrada: ByteArray,
        urlHmac: ByteArray,
        ahora: Instant,
    ): Int
}
