package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/**
 * Sesión de reproducción persistida para diagnóstico (E7-T10, S6a).
 *
 * Cada fila corresponde a una sesión Media3: se crea al abrir la sesión, se
 * actualiza con los eventos importantes y se cierra al cambiar de canal,
 * terminar la reproducción o fallar de forma definitiva. Sobrevive al cierre
 * de la aplicación.
 *
 * Convención de valores (S6a, «diferenciar»):
 * - `null` en una columna observable significa «no disponible / no recopilado»:
 *   nunca hubo evidencia de ese dato en la sesión.
 * - `false` en un booleano observable significa «no detectado»: se observó y
 *   el dato no está (por ejemplo, `pistaVideoDetectada = false`).
 * - Los campos de video en un stream sin pista de video quedan `null`
 *   («no aplicable»); el contexto queda en `condicion`/`imagenFinal`
 *   (`SIN_VIDEO`).
 *
 * Nunca persiste la URL cruda: solo la versión saneada (S57a), ni credenciales,
 * tokens ni cabeceras de autorización.
 */
@Entity(
    tableName = "SesionDiagnostico",
    indices = [
        Index(value = ["canalId"]),
        Index(value = ["inicio"]),
        Index(value = ["fin"]),
        Index(value = ["resultado"]),
    ],
)
data class SesionDiagnosticoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val canalId: Long? = null,
    val canalNombre: String? = null,
    val urlSaneada: String? = null,
    val inicio: Instant,
    val fin: Instant? = null,
    /** Última fase observada al cerrar (nombre de `EstadoReproductor`). */
    val faseFinal: String? = null,
    /** `TERMINADA` (fin de stream), `ERROR` o `CERRADA` (zapeo/abandono). */
    val resultado: String? = null,
    val posicionMs: Long? = null,
    val duracionMs: Long? = null,
    val ultimoError: String? = null,
    val codigoMotor: String? = null,
    val claseExcepcion: String? = null,
    val clasificacionTecnica: String? = null,
    val reintento: Int? = null,
    val reintentosMaximos: Int? = null,
    /** Recuperaciones de red observadas (REINTENTANDO/ESPERANDO_CONEXION → REPRODUCIENDO). */
    val recuperacionesRed: Int = 0,
    /** Recuperaciones de imagen confirmadas (E7-T10, S5). */
    val recuperacionesImagen: Int = 0,
    /** Último `EstadoImagen` observado. */
    val imagenFinal: String? = null,
    val condicion: String? = null,
    val estadoHttp: Int? = null,
    val codigoHttpInicial: Int? = null,
    val tipoMime: String? = null,
    val bytesRespuesta: Long? = null,
    val msHastaFallo: Long? = null,
    val intentosFallo: Int? = null,
    val redirecciones: Int? = null,
    val esquemaInicial: String? = null,
    val esquemaFinal: String? = null,
    val cicloRedirecciones: Boolean? = null,
    val limiteRedirecciones: Boolean? = null,
    val fuenteMedia: String? = null,
    val formatoEsperado: String? = null,
    val formatoObservado: String? = null,
    val formatoObservadoMime: String? = null,
    val reSeleccionMedia: String? = null,
    val pistaVideoMime: String? = null,
    val pistaVideoCodec: String? = null,
    val pistaVideoResolucion: String? = null,
    val pistaVideoFrameRate: Float? = null,
    val pistaVideoBitrate: Int? = null,
    val pistaAudioMime: String? = null,
    val pistaAudioCodec: String? = null,
    val pistaAudioBitrate: Int? = null,
    val decoderVideo: String? = null,
    val decoderAudio: String? = null,
    val msHastaPrimerFrameVideo: Long? = null,
    val framesPerdidosVideo: Int? = null,
    val framesRenderidosVideo: Int? = null,
    val framesOmitidosVideo: Int? = null,
    val videoRendererActivo: Boolean? = null,
    val audioRendererActivo: Boolean? = null,
    val pistaVideoDetectada: Boolean? = null,
    val pistaAudioDetectada: Boolean? = null,
    /** Resumen de pistas de audio detectadas y seleccionada, saneado y acotado. */
    val pistasAudioTexto: String? = null,
    /** Resumen de subtítulos detectados y seleccionado, saneado y acotado. */
    val pistasSubtituloTexto: String? = null,
)

/**
 * Evento puntual de una sesión de diagnóstico (E7-T10, S6a).
 *
 * Solo se registran eventos significativos: errores, recuperaciones de red,
 * recuperaciones de imagen y el cierre. No se persiste cada cambio de fase ni
 * cada frame; eventos consecutivos idénticos se agrupan (no se duplican).
 */
@Entity(
    tableName = "EventoDiagnostico",
    indices = [
        Index(value = ["sesionId", "momento"]),
    ],
    foreignKeys = [
        ForeignKey(
            entity = SesionDiagnosticoEntity::class,
            parentColumns = ["id"],
            childColumns = ["sesionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
)
data class EventoDiagnosticoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0L,
    val sesionId: Long,
    val momento: Instant,
    /** `ERROR`, `RECUPERACION_RED`, `RECUPERACION_IMAGEN` o `CIERRE`. */
    val tipo: String,
    /** Evidencia acotada y saneada (clasificación, código HTTP, fase). */
    val detalle: String? = null,
    /** Código estable del evento: `codigoMotor` o nombre de `EstadoImagen`. */
    val codigo: String? = null,
)
