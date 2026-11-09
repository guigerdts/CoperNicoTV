package com.copernicotv.app.player

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
 */
fun construirDiagnostico(
    protocolo: ProtocoloStream?,
    url: String?,
    estado: EstadoSesion,
    pistas: EstadoPistas,
    ultimoError: String? = null,
): DiagnosticoReproductor =
    DiagnosticoReproductor(
        protocolo = protocolo,
        urlSaneada = sanearUrl(url),
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
        append("Reintentos: ${diagnostico.reintento}/${diagnostico.reintentosMaximos}")
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
