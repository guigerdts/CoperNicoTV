package com.copernicotv.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.Format
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.DecoderCounters
import androidx.media3.exoplayer.analytics.AnalyticsListener
import com.copernicotv.core.player.FaseBase
import com.copernicotv.core.player.SenalAudiovisual

/**
 * Datos de una pista observada por el motor (E7-T10, S3).
 *
 * Solo contiene valores que Media3 entrega realmente en el [Format]; los
 * campos que el formato no declara quedan nulos. La resolución es la pareja
 * ancho x alto tal como llega.
 */
data class InfoPista(
    val mime: String? = null,
    val codec: String? = null,
    val resolucion: String? = null,
    val frameRate: Float? = null,
    val bitrate: Int? = null,
)

/**
 * Instantánea audiovisual de la sesión (E7-T10, S3).
 *
 * `hayGrupoVideo` / `hayGrupoAudio` indican si el contenido declara grupos
 * de ese tipo: permiten distinguir un stream sin video (no aplicable) de un
 * video que existe pero no se renderiza (sin primer frame o superficie
 * perdida). `framesRenderidosVideo` y `framesOmitidosVideo` provienen de los
 * contadores de decoder que Media3 reporta (`renderedOutputBufferCount` y
 * `skippedOutputBufferCount` al deshabilitar el renderer de video); los
 * frames perdidos por el renderer llegan por `onDroppedVideoFrames`.
 * Media3 no ofrece contadores adicionales, y nada se inventa más allá de
 * esos campos.
 */
data class ObservacionAudiovisual(
    val audioActivo: Boolean,
    val videoActivo: Boolean,
    val primerFrameVideoVisto: Boolean,
    val msHastaPrimerFrameVideo: Long?,
    val framesPerdidosVideo: Int?,
    val framesRenderidosVideo: Int?,
    val framesOmitidosVideo: Int?,
    val decoderVideo: String?,
    val decoderAudio: String?,
    val pistaVideo: InfoPista?,
    val pistaAudio: InfoPista?,
    val hayGrupoVideo: Boolean,
    val hayGrupoAudio: Boolean,
)

/**
 * Observador audiovisual de la sesión de reproducción (E7-T10, S3).
 *
 * Implementa [AnalyticsListener], el mecanismo oficial de Media3 para
 * eventos crudos de reproducción. Registra lo que el motor realmente
 * ejecuta —renderizadores habilitados y deshabilitados, decoders
 * inicializados, primer frame de video, frames perdidos y pistas
 * seleccionadas— para que la condición de sesión distinga un `READY` con
 * audio y video de uno con audio pero sin imagen.
 *
 * Todos los callbacks llegan en el hilo principal del motor; el bucle de
 * publicación de la sesión lee la instantánea en ese mismo hilo, así que
 * no se necesita sincronización adicional. Las medidas de tiempo usan el
 * reloj de la sesión ([SystemClock.elapsedRealtime] desde su apertura).
 *
 * Los contadores son de sesión: un primer frame visto lo sigue estando
 * aunque el video se pierda después, que es exactamente la evidencia que
 * la recuperación de imagen necesita (S5).
 */
class ObservadorAudiovisual(
    private val inicioSesionMs: Long,
) : AnalyticsListener {
    private var audioActivo = false
    private var videoActivo = false
    private var primerFrameVideoVisto = false
    private var msHastaPrimerFrameVideo: Long? = null
    private var framesPerdidosVideo: Int? = null
    private var framesRenderidosVideo: Int? = null
    private var framesOmitidosVideo: Int? = null
    private var decoderVideo: String? = null
    private var decoderAudio: String? = null
    private var pistaVideo: InfoPista? = null
    private var pistaAudio: InfoPista? = null
    private var hayGrupoVideo = false
    private var hayGrupoAudio = false

    /** Instantánea inmutable del estado audiovisual observado. */
    fun observacion(): ObservacionAudiovisual =
        ObservacionAudiovisual(
            audioActivo = audioActivo,
            videoActivo = videoActivo,
            primerFrameVideoVisto = primerFrameVideoVisto,
            msHastaPrimerFrameVideo = msHastaPrimerFrameVideo,
            framesPerdidosVideo = framesPerdidosVideo,
            framesRenderidosVideo = framesRenderidosVideo,
            framesOmitidosVideo = framesOmitidosVideo,
            decoderVideo = decoderVideo,
            decoderAudio = decoderAudio,
            pistaVideo = pistaVideo,
            pistaAudio = pistaAudio,
            hayGrupoVideo = hayGrupoVideo,
            hayGrupoAudio = hayGrupoAudio,
        )

    /** Señal combinada para el clasificador puro de `:core` (S3). */
    fun senal(fase: FaseBase): SenalAudiovisual =
        SenalAudiovisual(
            fase = fase,
            audioActivo = audioActivo,
            videoActivo = videoActivo,
            primerFrameVideoVisto = primerFrameVideoVisto,
        )

    override fun onTracksChanged(
        eventTime: AnalyticsListener.EventTime,
        tracks: Tracks,
    ) {
        hayGrupoVideo = tracks.groups.any { it.type == C.TRACK_TYPE_VIDEO && it.length > 0 }
        hayGrupoAudio = tracks.groups.any { it.type == C.TRACK_TYPE_AUDIO && it.length > 0 }
        pistaVideo = formatoSeleccionado(tracks, C.TRACK_TYPE_VIDEO)?.let { formatoDe(it) }
        pistaAudio = formatoSeleccionado(tracks, C.TRACK_TYPE_AUDIO)?.let { formatoDe(it) }
    }

    override fun onAudioEnabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        audioActivo = true
    }

    override fun onAudioDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        audioActivo = false
    }

    override fun onVideoEnabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        videoActivo = true
    }

    override fun onVideoDisabled(
        eventTime: AnalyticsListener.EventTime,
        decoderCounters: DecoderCounters,
    ) {
        videoActivo = false
        framesRenderidosVideo = decoderCounters.renderedOutputBufferCount.coerceAtLeast(0)
        framesOmitidosVideo = decoderCounters.skippedOutputBufferCount.coerceAtLeast(0)
    }

    override fun onVideoDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoderVideo = decoderName
    }

    override fun onAudioDecoderInitialized(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
        initializedTimestampMs: Long,
        initializationDurationMs: Long,
    ) {
        decoderAudio = decoderName
    }

    override fun onVideoDecoderReleased(
        eventTime: AnalyticsListener.EventTime,
        decoderName: String,
    ) {
        if (decoderVideo == decoderName) decoderVideo = null
    }

    override fun onRenderedFirstFrame(
        eventTime: AnalyticsListener.EventTime,
        output: Any,
        renderTimeMs: Long,
    ) {
        if (!primerFrameVideoVisto) {
            primerFrameVideoVisto = true
            msHastaPrimerFrameVideo =
                (SystemClock.elapsedRealtime() - inicioSesionMs).coerceAtLeast(0L)
        }
    }

    override fun onDroppedVideoFrames(
        eventTime: AnalyticsListener.EventTime,
        droppedFrames: Int,
        elapsedMs: Long,
    ) {
        val acumulados = framesPerdidosVideo ?: 0
        framesPerdidosVideo = (acumulados + droppedFrames.coerceAtLeast(0))
    }

    private fun formatoSeleccionado(
        tracks: Tracks,
        tipo: Int,
    ): Format? =
        tracks.groups
            .filter { it.type == tipo }
            .firstNotNullOfOrNull { grupo ->
                (0 until grupo.length).firstOrNull { grupo.isTrackSelected(it) }?.let {
                    grupo.getTrackFormat(it)
                }
            }

    private fun formatoDe(formato: Format): InfoPista =
        InfoPista(
            mime = formato.sampleMimeType,
            codec = formato.codecs,
            resolucion =
                if (formato.width > 0 && formato.height > 0) {
                    "${formato.width}x${formato.height}"
                } else {
                    null
                },
            frameRate = if (formato.frameRate > 0f) formato.frameRate else null,
            bitrate = if (formato.bitrate > 0) formato.bitrate else null,
        )
}
