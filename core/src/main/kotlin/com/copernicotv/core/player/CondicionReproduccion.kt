package com.copernicotv.core.player

/**
 * Condición audiovisual de una sesión de reproducción (E7-T10, S3).
 *
 * Clasifica lo que el usuario realmente ve, no solo el estado del motor:
 * un `READY` con audio pero sin imagen nunca se reporta como reproducción
 * correcta. Solo combina señales que Media3 entrega; no inventa métricas.
 */
enum class CondicionReproduccion {
    /** La sesión todavía no publica datos de reproducción. */
    ESPERANDO_DATOS,

    /** El motor declara buffering. */
    BUFFERING,

    /** Pausada, terminada o en reintento: no hay reproducción en curso. */
    DETENIDA,

    /** La sesión terminó con un error de transporte (red, HTTP, IO). */
    ERROR_TRANSPORTE,

    /** La sesión terminó con un error de decodificación (códec, renderer). */
    ERROR_DECODIFICACION,

    /** Audio y video activos con primer frame de video renderizado. */
    READY_AUDIO_VIDEO,

    /** Audio y video activos, pero el primer frame de video nunca llegó. */
    READY_SIN_PRIMER_FRAME,

    /** Audio activo y video inactivo sin haber renderizado nunca un frame. */
    READY_SOLO_AUDIO,

    /** El video llegó a renderizar y luego se perdió mientras el audio sigue. */
    PERDIDA_SUPERFICIE,

    /** `READY` sin renderizadores útiles: ni audio ni video en reproducción. */
    READY_SIN_PISTAS,
}

/** Fase base de la sesión, en vocabulario neutro para `:core` (E7-T10 S3). */
enum class FaseBase {
    /** Sin datos aún (cargando). */
    ESPERANDO,

    /** Buffering declarado por el motor. */
    BUFFERING,

    /** `READY` con reproducción en curso. */
    REPRODUCIENDO,

    /** Pausada, terminada o reintentando. */
    DETENIDA,

    /** Sesión terminada con error. */
    ERROR,
}

/** Clase de fallo observada, para separar transporte de decodificación. */
enum class FalloVisto {
    NINGUNO,
    TRANSPORTE,
    DECODIFICACION,
}

/**
 * Señales audiovisuales que la sesión observó mediante AnalyticsListener.
 *
 * `audioActivo` y `videoActivo` reflejan renderizadores habilitados y no
 * deshabilitados (`onAudioEnabled/onAudioDisabled`, `onVideoEnabled/
 * onVideoDisabled`); `primerFrameVideoVisto` refleja `onRenderedFirstFrame`.
 */
data class SenalAudiovisual(
    val fase: FaseBase,
    val audioActivo: Boolean = false,
    val videoActivo: Boolean = false,
    val primerFrameVideoVisto: Boolean = false,
    val falloVisto: FalloVisto = FalloVisto.NINGUNO,
)

/**
 * Clasifica la condición audiovisual de la sesión a partir de las señales.
 *
 * Orden de decisión: error de sesión, luego fases no productivas, y solo
 * entonces la combinación de renderizadores activos y primer frame. Un
 * video que dejó de renderizar tras haber mostrado frames, con audio
 * activo, se reporta como [CondicionReproduccion.PERDIDA_SUPERFICIE]; el
 * caso sin primer frame y sin video activo es
 * [CondicionReproduccion.READY_SOLO_AUDIO] (stream sin video o pista no
 * elegible). Una reproducción con video activo pero sin audio no es
 * útil para TV y se reporta como
 * [CondicionReproduccion.READY_SIN_PISTAS]; el detalle queda en los
 * campos de observación de la sesión.
 */
fun clasificarCondicion(senal: SenalAudiovisual): CondicionReproduccion =
    when (senal.fase) {
        FaseBase.ERROR ->
            if (senal.falloVisto == FalloVisto.DECODIFICACION) {
                CondicionReproduccion.ERROR_DECODIFICACION
            } else {
                CondicionReproduccion.ERROR_TRANSPORTE
            }
        FaseBase.BUFFERING -> CondicionReproduccion.BUFFERING
        FaseBase.DETENIDA -> CondicionReproduccion.DETENIDA
        FaseBase.ESPERANDO -> CondicionReproduccion.ESPERANDO_DATOS
        FaseBase.REPRODUCIENDO ->
            when {
                !senal.audioActivo -> CondicionReproduccion.READY_SIN_PISTAS
                senal.videoActivo && senal.primerFrameVideoVisto ->
                    CondicionReproduccion.READY_AUDIO_VIDEO
                senal.videoActivo && !senal.primerFrameVideoVisto ->
                    CondicionReproduccion.READY_SIN_PRIMER_FRAME
                !senal.videoActivo && senal.primerFrameVideoVisto ->
                    CondicionReproduccion.PERDIDA_SUPERFICIE
                else -> CondicionReproduccion.READY_SOLO_AUDIO
            }
    }
