package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import com.copernicotv.app.data.db.entities.EventoDiagnosticoEntity
import com.copernicotv.app.data.db.entities.SesionDiagnosticoEntity
import java.time.Instant

/**
 * Consultas y escrituras del diagnóstico persistente de reproducción
 * (E7-T10, S6a).
 *
 * Todas las operaciones son `suspend`: Room las ejecuta en su propio
 * ejecutor y nunca bloquean el hilo principal. La retención solo elimina
 * sesiones cerradas y anteriores al corte; nunca toca sesiones activas ni
 * diagnósticos recientes.
 */
@Dao
interface DiagnosticoDao {
    @Insert
    suspend fun insertarSesion(sesion: SesionDiagnosticoEntity): Long

    @Insert
    suspend fun insertarEvento(evento: EventoDiagnosticoEntity): Long

    @Update
    suspend fun actualizarSesion(sesion: SesionDiagnosticoEntity)

    @Query("SELECT * FROM SesionDiagnostico ORDER BY inicio DESC, id DESC LIMIT :limite")
    suspend fun recientes(limite: Int): List<SesionDiagnosticoEntity>

    @Query("SELECT * FROM SesionDiagnostico WHERE canalId = :canalId ORDER BY inicio DESC, id DESC LIMIT :limite")
    suspend fun deCanal(
        canalId: Long,
        limite: Int,
    ): List<SesionDiagnosticoEntity>

    @Query("SELECT * FROM SesionDiagnostico WHERE id = :id")
    suspend fun porId(id: Long): SesionDiagnosticoEntity?

    @Query("SELECT * FROM EventoDiagnostico WHERE sesionId = :sesionId ORDER BY momento ASC, id ASC")
    suspend fun eventosDeSesion(sesionId: Long): List<EventoDiagnosticoEntity>

    @Query(
        "SELECT * FROM SesionDiagnostico WHERE resultado = 'ERROR' " +
            "ORDER BY inicio DESC, id DESC LIMIT :limite",
    )
    suspend fun conError(limite: Int): List<SesionDiagnosticoEntity>

    @Query(
        "SELECT * FROM SesionDiagnostico WHERE estadoHttp IS NOT NULL " +
            "ORDER BY inicio DESC, id DESC LIMIT :limite",
    )
    suspend fun conErrorHttp(limite: Int): List<SesionDiagnosticoEntity>

    @Query(
        "SELECT * FROM SesionDiagnostico WHERE clasificacionTecnica IN " +
            "('formato_incompatible', 'contenedor_no_reconocido') " +
            "OR imagenFinal = 'ERROR_DECODIFICACION' " +
            "ORDER BY inicio DESC, id DESC LIMIT :limite",
    )
    suspend fun conErrorDecodificacion(limite: Int): List<SesionDiagnosticoEntity>

    @Query(
        "SELECT * FROM SesionDiagnostico WHERE recuperacionesRed > 0 OR recuperacionesImagen > 0 " +
            "ORDER BY inicio DESC, id DESC LIMIT :limite",
    )
    suspend fun conRecuperaciones(limite: Int): List<SesionDiagnosticoEntity>

    @Query(
        "SELECT * FROM SesionDiagnostico WHERE imagenFinal IN " +
            "('RECUPERANDO_IMAGEN', 'RESTAURANDO_SUPERFICIE', 'REINICIALIZANDO_RENDERER', " +
            "'IMAGEN_RECUPERADA', 'ERROR_DECODIFICACION', 'ERROR_RENDERIZADO') " +
            "ORDER BY inicio DESC, id DESC LIMIT :limite",
    )
    suspend fun conAudioSinImagen(limite: Int): List<SesionDiagnosticoEntity>

    @Query(
        "DELETE FROM SesionDiagnostico WHERE fin IS NOT NULL AND inicio < :corte",
    )
    suspend fun eliminarSesionesCerradasAnterioresA(corte: Instant): Int
}
