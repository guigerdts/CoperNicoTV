package com.copernicotv.app.player

import com.copernicotv.core.player.CondicionReproduccion

/** Fase observable de la sesión de reproducción (E6-T2, ampliada en E6-T4). */
enum class EstadoReproductor {
    CARGANDO,
    BUFFERING,
    REPRODUCIENDO,
    PAUSADO,
    TERMINADO,
    REINTENTANDO,
    ERROR,
}

/**
 * Estado de reproducción que la interfaz puede leer sin tocar el motor.
 *
 * `posicionMs`, `duracionMs` y `bufferMs` son milisegundos; `duracionMs` vale
 * cero cuando el contenido todavía no declara duración. El código de error es
 * propio y estable: nunca contiene la URL ni el mensaje de Media3 (S57a).
 *
 * `reintento` y `reintentosMaximos` solo tienen sentido en
 * [EstadoReproductor.REINTENTANDO]: indican el número de reintento en curso y
 * el total configurado por S20. Fuera de esa fase valen cero.
 *
 * Los campos de detalle del fallo (`codigoMotor`, `clasesCausa`, `estadoHttp`,
 * `tipoMime`, `bytesRespuesta`, `msHastaFallo`, `intentosFallo` y
 * `clasificacionTecnica`) solo se rellenan en [EstadoReproductor.ERROR] y
 * contienen únicamente evidencia segura para mostrar (DetalleFallo.kt).
 *
 * Los campos de trazado de red de E7-T8 (`codigoHttpInicial`, `redirecciones`,
 * `esquemaInicial`, `esquemaFinal`, `cicloRedirecciones`,
 * `limiteRedirecciones`, `fuenteMedia` y `agenteUsuario`) también solo tienen
 * contenido en [EstadoReproductor.ERROR]: provienen de la [TrazadoraRed] de la
 * sesión y son evidencia segura para mostrar (esquemas, códigos y contadores).
 *
 * Los campos de formato de E7-T9 (`formatoEsperado`, `formatoObservado`,
 * `formatoObservadoMime` y `reSeleccionMedia`) también solo tienen contenido
 * en [EstadoReproductor.ERROR]: provienen de la sonda acotada de contenedor
 * que observó la respuesta real tras el fallo (S3/S5). `reSeleccionMedia`
 * contiene el MIME con que se re-seleccionó la fuente una sola vez (S4), o
 * `null` cuando no hubo alternativa plausible.
 */
data class EstadoSesion(
    val estado: EstadoReproductor = EstadoReproductor.CARGANDO,
    val posicionMs: Long = 0L,
    val duracionMs: Long = 0L,
    val bufferMs: Long = 0L,
    val puedeBuscar: Boolean = false,
    val codigoError: String? = null,
    val reintento: Int = 0,
    val reintentosMaximos: Int = 0,
    val codigoMotor: String? = null,
    val clasesCausa: List<String> = emptyList(),
    val estadoHttp: Int? = null,
    val tipoMime: String? = null,
    val bytesRespuesta: Long = 0L,
    val msHastaFallo: Long = 0L,
    val intentosFallo: Int = 0,
    val clasificacionTecnica: String? = null,
    val codigoHttpInicial: Int? = null,
    val redirecciones: Int? = null,
    val esquemaInicial: String? = null,
    val esquemaFinal: String? = null,
    val cicloRedirecciones: Boolean = false,
    val limiteRedirecciones: Boolean = false,
    val fuenteMedia: String? = null,
    val agenteUsuario: String? = null,
    val formatoEsperado: String? = null,
    val formatoObservado: String? = null,
    val formatoObservadoMime: String? = null,
    val reSeleccionMedia: String? = null,
    // E7-T10 S3: observabilidad audiovisual vía AnalyticsListener. Los
    // campos son de sesión y se proyectan del [ObservadorAudiovisual];
    // solo contienen datos que Media3 entrega realmente. `condicion`
    // distingue un READY con audio y video de uno con audio sin imagen.
    val condicion: CondicionReproduccion? = null,
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
)

/**
 * Nombre del estado del motor Media3 para la etiqueta de diagnóstico.
 *
 * Conserva la terminología de E6-T1 (`READY`, `ENDED`) para que la evidencia
 * anterior siga siendo comparable entre incrementos.
 */
fun nombreEstadoMotor(fase: EstadoReproductor): String =
    when (fase) {
        EstadoReproductor.CARGANDO,
        EstadoReproductor.REINTENTANDO,
        EstadoReproductor.ERROR,
        -> "IDLE"
        EstadoReproductor.BUFFERING -> "BUFFERING"
        EstadoReproductor.REPRODUCIENDO, EstadoReproductor.PAUSADO -> "READY"
        EstadoReproductor.TERMINADO -> "ENDED"
    }
