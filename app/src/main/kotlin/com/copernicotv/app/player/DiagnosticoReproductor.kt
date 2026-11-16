package com.copernicotv.app.player

import com.copernicotv.core.player.CondicionReproduccion
import com.copernicotv.core.player.ProtocoloStream
import java.util.Locale

/**
 * Diagnóstico técnico de la sesión de reproducción (E6-T5, S22).
 *
 * Se deriva de [EstadoSesion] y [EstadoPistas]; no es una segunda fuente de
 * verdad. La URL se guarda ya saneada: nunca contiene userinfo, ruta, query ni
 * fragmento (S57a). El ViewModel conserva una instantánea de este diagnóstico
 * cuando un error definitivo cierra la sesión, para que la pantalla de error
 * siga mostrando el último estado real.
 */
data class DiagnosticoReproductor(
    val protocolo: ProtocoloStream? = null,
    val urlSaneada: String? = null,
    val canalId: Long? = null,
    val fase: EstadoReproductor = EstadoReproductor.CARGANDO,
    val posicionMs: Long = 0L,
    val duracionMs: Long = 0L,
    val bufferMs: Long = 0L,
    val audio: List<PistaDisponible> = emptyList(),
    val subtitulos: List<PistaDisponible> = emptyList(),
    val subtitulosDesactivados: Boolean = true,
    val ultimoError: String? = null,
    val reintento: Int = 0,
    val reintentosMaximos: Int = 0,
    val codigoMotor: String? = null,
    val claseExcepcion: String? = null,
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
    // E7-T10 S3/S6: observabilidad audiovisual (AnalyticsListener) y
    // condición de sesión. Solo datos que Media3 entrega realmente.
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
    // E7-T10 T7: la pista de video que el contenedor declara aunque no se
    // haya seleccionado, con su soporte observado en el dispositivo.
    val pistaVideoDetectadaMime: String? = null,
    val pistaVideoDetectadaCodec: String? = null,
    val pistaVideoSoportada: Boolean? = null,
) {
    /** Pista de audio que el motor tiene activa, si el contenido la publica. */
    val audioSeleccionado: PistaDisponible? get() = audio.firstOrNull { it.activa }

    /** Pista de subtítulos activa; no hay selección cuando están desactivados. */
    val subtitulosSeleccionados: PistaDisponible? get() = subtitulos.firstOrNull { it.activa }
}

/**
 * Construye el diagnóstico a partir del estado vigente de la sesión.
 *
 * [ultimoError] conserva el último código visto aunque el motor ya haya
 * recuperado la reproducción; si es `null`, se usa el error actual.
 * [canalId] identifica el canal sin exponer su URL (S57a).
 */
fun construirDiagnostico(
    protocolo: ProtocoloStream?,
    url: String?,
    estado: EstadoSesion,
    pistas: EstadoPistas,
    ultimoError: String? = null,
    canalId: Long? = null,
): DiagnosticoReproductor =
    DiagnosticoReproductor(
        protocolo = protocolo,
        urlSaneada = sanearUrl(url),
        canalId = canalId,
        fase = estado.estado,
        posicionMs = estado.posicionMs.coerceAtLeast(0L),
        duracionMs = estado.duracionMs.coerceAtLeast(0L),
        bufferMs = estado.bufferMs.coerceAtLeast(0L),
        audio = pistas.audio,
        subtitulos = pistas.subtitulos,
        subtitulosDesactivados = pistas.subtitulosDesactivados,
        ultimoError = ultimoError ?: estado.codigoError,
        reintento = estado.reintento.coerceAtLeast(0),
        reintentosMaximos = estado.reintentosMaximos.coerceAtLeast(0),
        codigoMotor = estado.codigoMotor,
        claseExcepcion = estado.clasesCausa.lastOrNull(),
        estadoHttp = estado.estadoHttp,
        tipoMime = estado.tipoMime,
        bytesRespuesta = estado.bytesRespuesta.coerceAtLeast(0L),
        msHastaFallo = estado.msHastaFallo.coerceAtLeast(0L),
        intentosFallo = estado.intentosFallo.coerceAtLeast(0),
        clasificacionTecnica = estado.clasificacionTecnica,
        codigoHttpInicial = estado.codigoHttpInicial,
        redirecciones = estado.redirecciones,
        esquemaInicial = estado.esquemaInicial,
        esquemaFinal = estado.esquemaFinal,
        cicloRedirecciones = estado.cicloRedirecciones,
        limiteRedirecciones = estado.limiteRedirecciones,
        fuenteMedia = estado.fuenteMedia,
        agenteUsuario = estado.agenteUsuario,
        formatoEsperado = estado.formatoEsperado,
        formatoObservado = estado.formatoObservado,
        formatoObservadoMime = estado.formatoObservadoMime,
        reSeleccionMedia = estado.reSeleccionMedia,
        condicion = estado.condicion,
        pistaVideoMime = estado.pistaVideoMime,
        pistaVideoCodec = estado.pistaVideoCodec,
        pistaVideoResolucion = estado.pistaVideoResolucion,
        pistaVideoFrameRate = estado.pistaVideoFrameRate,
        pistaVideoBitrate = estado.pistaVideoBitrate,
        pistaAudioMime = estado.pistaAudioMime,
        pistaAudioCodec = estado.pistaAudioCodec,
        pistaAudioBitrate = estado.pistaAudioBitrate,
        decoderVideo = estado.decoderVideo,
        decoderAudio = estado.decoderAudio,
        msHastaPrimerFrameVideo = estado.msHastaPrimerFrameVideo,
        framesPerdidosVideo = estado.framesPerdidosVideo,
        framesRenderidosVideo = estado.framesRenderidosVideo,
        framesOmitidosVideo = estado.framesOmitidosVideo,
        videoRendererActivo = estado.videoRendererActivo,
        audioRendererActivo = estado.audioRendererActivo,
        pistaVideoDetectada = estado.pistaVideoDetectada,
        pistaAudioDetectada = estado.pistaAudioDetectada,
        pistaVideoDetectadaMime = estado.pistaVideoDetectadaMime,
        pistaVideoDetectadaCodec = estado.pistaVideoDetectadaCodec,
        pistaVideoSoportada = estado.pistaVideoSoportada,
    )

/**
 * Reduce una URL de stream a esquema, host y puerto.
 *
 * La UI y los textos copiables nunca muestran la URL completa: userinfo, ruta,
 * query y fragmento pueden contener credenciales o tokens (S57a, DC-04 §4). Si
 * la URL no se puede interpretar, se oculta entera en lugar de arriesgar una
 * representación parcial.
 */
fun sanearUrl(url: String?): String {
    val texto = url?.trim().orEmpty()
    val separador = texto.indexOf("://")
    val esquema = if (separador > 0) texto.substring(0, separador).lowercase(Locale.ROOT) else null
    val resto = if (separador > 0) texto.substring(separador + 3) else ""
    val finAutoridad = resto.indexOfFirst { it == '/' || it == '?' || it == '#' }
    val autoridad = if (finAutoridad < 0) resto else resto.substring(0, finAutoridad)
    val hostPuerto = autoridad.substringAfterLast('@')
    val (host, puertoTexto) = dividirHostPuerto(hostPuerto) ?: ("" to "")
    val valida =
        esquema != null &&
            esquema in ESQUEMAS_VISIBLES &&
            host.isNotEmpty() &&
            host != "[]" &&
            puertoValido(puertoTexto)
    return if (valida) {
        val puerto = if (puertoTexto.isEmpty()) "" else ":$puertoTexto"
        "$esquema://${host.lowercase(Locale.ROOT)}$puerto"
    } else {
        URL_OCULTA
    }
}

private fun puertoValido(puerto: String): Boolean {
    if (puerto.isEmpty()) return true
    if (puerto.any { it !in '0'..'9' }) return false
    val numero = puerto.toIntOrNull() ?: return false
    return numero in 0..65535
}

/**
 * Texto estable que se muestra en el panel y se copia al portapapeles.
 *
 * Incluye los campos mínimos de S22a —protocolo, pistas, buffer y último
 * error— y la URL saneada. No contiene la URL original ni mensajes de Media3.
 */
fun textoDiagnostico(diagnostico: DiagnosticoReproductor): String =
    buildString {
        val duracion =
            if (diagnostico.duracionMs > 0L) {
                formatearTiempo(diagnostico.duracionMs)
            } else {
                SIN_DURACION
            }
        appendLine("Protocolo: ${diagnostico.protocolo?.etiqueta ?: SIN_DATO}")
        appendLine("URL: ${diagnostico.urlSaneada ?: URL_OCULTA}")
        appendLine("Fase: ${nombreEstadoMotor(diagnostico.fase)}")
        appendLine("Posición: ${formatearTiempo(diagnostico.posicionMs)}")
        appendLine("Duración: $duracion")
        appendLine("Búfer: ${formatearTiempo(diagnostico.bufferMs)}")
        appendLine("Audio disponible: ${resumenPistas(diagnostico.audio)}")
        appendLine("Audio seleccionado: ${nombreSeleccionado(diagnostico.audioSeleccionado)}")
        appendLine("Subtítulos disponibles: ${resumenPistas(diagnostico.subtitulos)}")
        appendLine("Subtítulos seleccionados: ${textoSubtitulosSeleccionados(diagnostico)}")
        appendLine("Último error: ${diagnostico.ultimoError ?: SIN_ERROR}")
        appendLine("Clasificación: ${diagnostico.clasificacionTecnica ?: SIN_DATO}")
        appendLine("Identificador de canal: ${diagnostico.canalId?.toString() ?: SIN_DATO}")
        append(lineasDetalleMotor(diagnostico))
        append(lineasTrazadoRed(diagnostico))
        append(lineasAudiovisual(diagnostico))
    }

/**
 * Líneas de la observación audiovisual (E7-T10, S3/S6): condición real de
 * la sesión, pistas, códecs, decoders, primer frame y contadores. Cada
 * campo muestra el valor observado o «no disponible»; nada se infiere de
 * métricas que Media3 no entregue.
 */
private fun lineasAudiovisual(diagnostico: DiagnosticoReproductor): String =
    listOf(
        "Condición" to valorTexto(diagnostico.condicion?.name),
        "Video MIME" to valorTexto(diagnostico.pistaVideoMime),
        "Video códec" to valorTexto(diagnostico.pistaVideoCodec),
        "Video resolución" to valorTexto(diagnostico.pistaVideoResolucion),
        "Video fps" to valorTexto(diagnostico.pistaVideoFrameRate),
        "Video bitrate" to valorTexto(diagnostico.pistaVideoBitrate),
        "Audio MIME" to valorTexto(diagnostico.pistaAudioMime),
        "Audio códec" to valorTexto(diagnostico.pistaAudioCodec),
        "Audio bitrate" to valorTexto(diagnostico.pistaAudioBitrate),
        "Decoder video" to valorTexto(diagnostico.decoderVideo),
        "Decoder audio" to valorTexto(diagnostico.decoderAudio),
        "Primer frame de video" to valorTexto(primerFrameTexto(diagnostico)),
        "Frames perdidos" to valorTexto(diagnostico.framesPerdidosVideo),
        "Frames renderizados" to valorTexto(diagnostico.framesRenderidosVideo),
        "Frames omitidos" to valorTexto(diagnostico.framesOmitidosVideo),
        "Renderer video activo" to valorTexto(booleanoTexto(diagnostico.videoRendererActivo)),
        "Renderer audio activo" to valorTexto(booleanoTexto(diagnostico.audioRendererActivo)),
        "Video detectado" to valorTexto(booleanoTexto(diagnostico.pistaVideoDetectada)),
        "Audio detectado" to valorTexto(booleanoTexto(diagnostico.pistaAudioDetectada)),
        "Video MIME detectado" to valorTexto(diagnostico.pistaVideoDetectadaMime),
        "Video códec detectado" to valorTexto(diagnostico.pistaVideoDetectadaCodec),
        "Pista video soportada" to valorTexto(booleanoTexto(diagnostico.pistaVideoSoportada)),
    ).joinToString(separator = "\n", postfix = "\n") { "${it.first}: ${it.second}" }

/** Valor observable como texto; nulo significa «no disponible» (S6). */
internal fun valorTexto(valor: Any?): String = valor?.toString() ?: SIN_DATO

/** Duración hasta el primer frame de video, en formato de tiempo. */
private fun primerFrameTexto(diagnostico: DiagnosticoReproductor): String? =
    diagnostico.msHastaPrimerFrameVideo?.let { formatearTiempo(it) }

/** Texto estable para booleanos observables, con «no disponible» si es nulo. */
internal fun booleanoTexto(valor: Boolean?): String = valor?.let { if (it) "sí" else "no" } ?: SIN_DATO

/**
 * Líneas del detalle técnico del fallo observado por Media3: códigos, MIME,
 * excepción, intentos y reintentos.
 */
private fun lineasDetalleMotor(diagnostico: DiagnosticoReproductor): String =
    buildString {
        appendLine("Código Media3: ${diagnostico.codigoMotor ?: SIN_DATO}")
        appendLine("Código HTTP: ${diagnostico.estadoHttp?.toString() ?: SIN_DATO}")
        appendLine("Código HTTP inicial: ${diagnostico.codigoHttpInicial?.toString() ?: SIN_DATO}")
        appendLine("MIME: ${diagnostico.tipoMime ?: SIN_DATO}")
        appendLine(
            "Tamaño de respuesta: " +
                if (diagnostico.bytesRespuesta > 0L) "${diagnostico.bytesRespuesta} bytes" else SIN_DATO,
        )
        appendLine("Excepción: ${diagnostico.claseExcepcion ?: SIN_DATO}")
    }

/**
 * Líneas del trazado de red observable (E7-T8, S5): redirecciones, esquemas,
 * MediaSource, User-Agent, intentos, duración y reintentos.
 */
private fun lineasTrazadoRed(diagnostico: DiagnosticoReproductor): String =
    buildString {
        appendLine(
            "Redirecciones: " +
                redireccionesTexto(
                    diagnostico.redirecciones,
                    diagnostico.cicloRedirecciones,
                    diagnostico.limiteRedirecciones,
                ),
        )
        appendLine("Esquema inicial: ${diagnostico.esquemaInicial ?: SIN_DATO}")
        appendLine("Esquema final: ${diagnostico.esquemaFinal ?: SIN_DATO}")
        appendLine("MediaSource: ${diagnostico.fuenteMedia ?: SIN_DATO}")
        appendLine("Formato esperado: ${diagnostico.formatoEsperado ?: SIN_DATO}")
        appendLine("Formato observado: ${formatoObservadoTexto(diagnostico)}")
        appendLine("Re-selección: ${diagnostico.reSeleccionMedia ?: SIN_DATO}")
        appendLine("User-Agent: ${diagnostico.agenteUsuario ?: SIN_DATO}")
        appendLine("Intentos: ${diagnostico.intentosFallo}")
        appendLine(
            "Duración hasta el fallo: " +
                if (diagnostico.msHastaFallo > 0L) formatearTiempo(diagnostico.msHastaFallo) else SIN_DATO,
        )
        append("Reintentos: ${diagnostico.reintento}/${diagnostico.reintentosMaximos}")
    }

/** Texto del contador de redirecciones y sus avisos de ciclo o límite. */
internal fun redireccionesTexto(
    redirecciones: Int?,
    ciclo: Boolean,
    limite: Boolean,
): String {
    if (redirecciones == null) return SIN_DATO
    val avisos =
        listOfNotNull(
            "ciclo detectado".takeIf { ciclo },
            "límite alcanzado".takeIf { limite },
        )
    val numero = redirecciones.toString()
    return if (avisos.isEmpty()) numero else "$numero (${avisos.joinToString("; ")})"
}

/** Texto del formato observado por la sonda con su MIME declarado (E7-T9). */
internal fun formatoObservadoTexto(diagnostico: DiagnosticoReproductor): String {
    val observado = diagnostico.formatoObservado ?: return SIN_DATO
    val mime = diagnostico.formatoObservadoMime ?: return observado
    return "$observado (MIME $mime)"
}

private fun dividirHostPuerto(hostPuerto: String): Pair<String, String>? {
    if (!hostPuerto.startsWith("[")) {
        val dosPuntos = hostPuerto.indexOf(':')
        return if (dosPuntos < 0) {
            hostPuerto to ""
        } else {
            hostPuerto.substring(0, dosPuntos) to hostPuerto.substring(dosPuntos + 1)
        }
    }
    val cierre = hostPuerto.indexOf(']')
    if (cierre < 0) return null
    val host = hostPuerto.substring(0, cierre + 1)
    val despues = hostPuerto.substring(cierre + 1)
    return when {
        despues.isEmpty() -> host to ""
        despues.startsWith(":") -> host to despues.substring(1)
        else -> null
    }
}

private fun resumenPistas(pistas: List<PistaDisponible>): String {
    if (pistas.isEmpty()) return SIN_PISTAS
    return pistas.mapIndexed { indice, pista ->
        etiquetaPista(pista) ?: "Pista ${indice + 1}"
    }.joinToString(", ")
}

private fun nombreSeleccionado(pista: PistaDisponible?): String =
    pista?.let { etiquetaPista(it) ?: "Pista ${it.indice + 1}" } ?: SIN_SELECCION

private fun textoSubtitulosSeleccionados(diagnostico: DiagnosticoReproductor): String =
    when {
        diagnostico.subtitulosDesactivados -> "Desactivados"
        else -> nombreSeleccionado(diagnostico.subtitulosSeleccionados)
    }

private val ESQUEMAS_VISIBLES = setOf("http", "https", "rtsp", "rtmp")

internal const val URL_OCULTA = "oculta"

private const val SIN_DATO = "no disponible"
private const val SIN_DURACION = "--:--"
private const val SIN_ERROR = "ninguno"
private const val SIN_PISTAS = "sin pistas"
private const val SIN_SELECCION = "ninguno"
