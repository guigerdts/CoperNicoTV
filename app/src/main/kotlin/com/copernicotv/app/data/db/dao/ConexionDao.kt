package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.ConexionEntity
import kotlinx.coroutines.flow.Flow
import java.time.Instant

/** Resumen observable de conexión, sin secretos ni BLOB. */
data class ConexionResumen(
    val id: Long,
    val nombre: String,
    val tipo: String,
    val activa: Boolean,
    val estado: String,
    val ultimoMensaje: String?,
    val ultimoCodigo: String?,
    /** Milisegundos UTC; el repositorio lo convierte a `Instant`. */
    val listaActualizadaEnMs: Long?,
    /** Milisegundos UTC; el repositorio lo convierte a `Instant`. */
    val guiaActualizadaEnMs: Long?,
)

/**
 * Perfiles de conexión IPTV (esquema v5).
 *
 * Las consultas de lista devuelven `ConexionResumen`: nunca exponen los
 * sobres cifrados ni obligan a descifrar para pintar la pantalla.
 */
@Dao
interface ConexionDao {
    @Insert
    suspend fun insertar(fila: ConexionEntity): Long

    @Query("SELECT * FROM Conexion WHERE id = :id")
    suspend fun porId(id: Long): ConexionEntity?

    @Query("SELECT * FROM Conexion WHERE nombre = :nombre LIMIT 1")
    suspend fun porNombre(nombre: String): ConexionEntity?

    @Query(
        """
        SELECT id, nombre, tipo, activa, estado, ultimoMensaje, ultimoCodigo,
            listaActualizadaEn AS listaActualizadaEnMs,
            guiaActualizadaEn AS guiaActualizadaEnMs
        FROM Conexion
        ORDER BY activa DESC, nombre COLLATE NOCASE, id
        """,
    )
    fun observarTodas(): Flow<List<ConexionResumen>>

    @Query(
        """
        SELECT id, nombre, tipo, activa, estado, ultimoMensaje, ultimoCodigo,
            listaActualizadaEn AS listaActualizadaEnMs,
            guiaActualizadaEn AS guiaActualizadaEnMs
        FROM Conexion
        ORDER BY activa DESC, nombre COLLATE NOCASE, id
        """,
    )
    suspend fun todas(): List<ConexionResumen>

    @Query("SELECT COUNT(*) FROM Conexion")
    suspend fun contar(): Int

    @Query("SELECT id FROM Conexion WHERE activa = 1 ORDER BY id")
    suspend fun idsActivas(): List<Long>

    @Query(
        """
        UPDATE Conexion SET nombre = :nombre, activa = :activa, protocolo = :protocolo,
            servidor = :servidor, puerto = :puerto, usuario = :usuario,
            formatoSalida = :formatoSalida, actualizacionAutomatica = :actualizacionAutomatica,
            intervaloHoras = :intervaloHoras, actualizadoEn = :ahora
        WHERE id = :id
        """,
    )
    suspend fun actualizarPerfil(
        id: Long,
        nombre: String,
        activa: Boolean,
        protocolo: String,
        servidor: String?,
        puerto: Int?,
        usuario: String?,
        formatoSalida: String?,
        actualizacionAutomatica: Boolean,
        intervaloHoras: Long,
        ahora: Instant,
    ): Int

    @Query(
        """
        UPDATE Conexion SET urlListaCifrada = :listaCifrada, urlListaHmac = :listaHmac,
            actualizadoEn = :ahora WHERE id = :id
        """,
    )
    suspend fun actualizarUrlLista(
        id: Long,
        listaCifrada: ByteArray?,
        listaHmac: ByteArray?,
        ahora: Instant,
    ): Int

    @Query(
        """
        UPDATE Conexion SET urlGuiaCifrada = :guiaCifrada, urlGuiaHmac = :guiaHmac,
            actualizadoEn = :ahora WHERE id = :id
        """,
    )
    suspend fun actualizarUrlGuia(
        id: Long,
        guiaCifrada: ByteArray?,
        guiaHmac: ByteArray?,
        ahora: Instant,
    ): Int

    @Query(
        """
        UPDATE Conexion SET passwordCifrada = :passwordCifrada, actualizadoEn = :ahora
        WHERE id = :id
        """,
    )
    suspend fun actualizarPassword(
        id: Long,
        passwordCifrada: ByteArray?,
        ahora: Instant,
    ): Int

    @Query(
        """
        UPDATE Conexion SET estado = :estado, ultimoMensaje = :mensaje, ultimoCodigo = :codigo,
            actualizadoEn = :ahora WHERE id = :id
        """,
    )
    suspend fun fijarEstado(
        id: Long,
        estado: String,
        mensaje: String?,
        codigo: String?,
        ahora: Instant,
    ): Int

    @Query(
        "UPDATE Conexion SET listaActualizadaEn = :cuando, actualizadoEn = :cuando WHERE id = :id",
    )
    suspend fun fijarListaActualizada(
        id: Long,
        cuando: Instant,
    ): Int

    @Query(
        "UPDATE Conexion SET guiaActualizadaEn = :cuando, actualizadoEn = :cuando WHERE id = :id",
    )
    suspend fun fijarGuiaActualizada(
        id: Long,
        cuando: Instant,
    ): Int

    /**
     * Activa una conexión y desactiva el resto en la misma transacción.
     *
     * La UI solo ofrece una conexión activa a la vez; el llamador envuelve la
     * llamada en `withTransaction` para que nunca queden dos activas.
     */
    @Query("UPDATE Conexion SET activa = 0, actualizadoEn = :ahora WHERE id != :id")
    suspend fun desactivarTodasExcepto(
        id: Long,
        ahora: Instant,
    ): Int

    @Query("DELETE FROM Conexion WHERE id = :id")
    suspend fun eliminar(id: Long): Int
}
