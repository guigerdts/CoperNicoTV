package com.copernicotv.app.player

import androidx.media3.common.PlaybackException
import androidx.media3.datasource.HttpDataSource

/**
 * Detalle técnico capturado en el momento del fallo (E7-T7, Objetivo B).
 *
 * Todo el contenido es seguro para mostrar y copiar (S57a): nombre del código
 * de error de Media3, nombres de clase de la cadena de causas, código HTTP de
 * la respuesta, MIME declarado y tamaño de la respuesta. Nunca incluye la URL
 * completa, mensajes del motor ni encabezados de la petición.
 */
data class DetalleFallo(
    val codigoMotor: String,
    val clasesCausa: List<String>,
    val estadoHttp: Int?,
    val tipoMime: String?,
    val bytesRespuesta: Long,
) {
    /** Clase más profunda de la cadena: la excepción original. */
    val claseExcepcion: String? get() = clasesCausa.lastOrNull()
}

/**
 * Captura la evidencia técnica de un fallo del motor caminando la cadena de
 * causas una sola vez.
 *
 * [codigoMotor] es `errorCodeName` de Media3 (nombre estable, sin URL). El
 * código HTTP y el MIME se extraen de la respuesta; el tamaño es la longitud
 * del cuerpo cuando Media3 la conservó. La profundidad está acotada para no
 * recorrer cadenas patológicas.
 */
internal fun capturarDetalleFallo(error: PlaybackException): DetalleFallo {
    val clases = mutableListOf<String>()
    var estadoHttp: Int? = null
    var tipoMime: String? = null
    var bytesRespuesta = 0L
    var causa: Throwable? = error.cause
    var profundidad = 0
    while (causa != null && profundidad < PROFUNDIDAD_MAXIMA_CAUSA) {
        clases.add(causa.javaClass.simpleName)
        when (causa) {
            is HttpDataSource.InvalidResponseCodeException -> {
                estadoHttp = causa.responseCode
                bytesRespuesta = causa.responseBody.size.toLong()
                if (tipoMime == null) {
                    tipoMime = mimeDeCabecera(causa.headerFields)
                }
            }
            is HttpDataSource.InvalidContentTypeException -> tipoMime = causa.contentType
            else -> Unit
        }
        causa = causa.cause
        profundidad += 1
    }
    return DetalleFallo(
        codigoMotor = error.errorCodeName,
        clasesCausa = clases,
        estadoHttp = estadoHttp,
        tipoMime = tipoMime,
        bytesRespuesta = bytesRespuesta,
    )
}

private fun mimeDeCabecera(cabeceras: Map<String, List<String>>): String? =
    cabeceras.entries
        .firstOrNull { it.key.equals("Content-Type", ignoreCase = true) }
        ?.value
        ?.joinToString(", ")
        ?.take(LONGITUD_MAXIMA_MIME)

/**
 * Clasificación técnica del fallo a partir de la evidencia capturada.
 *
 * Un código HTTP se clasifica por sí mismo (es la evidencia más específica);
 * el MIME incompatible viene de `InvalidContentTypeException`; los fallos de
 * red se reconocen por la clase de la excepción original. Sin evidencia, queda
 * `otro` en lugar de inventar una causa.
 */
internal fun clasificacionTecnica(detalle: DetalleFallo): String {
    val estadoHttp = detalle.estadoHttp
    if (estadoHttp != null) {
        return when (estadoHttp) {
            401, 403 -> "credenciales_rechazadas"
            404 -> "recurso_no_encontrado"
            429 -> "limite_solicitudes"
            in 400..499 -> "http_cliente"
            in 500..599 -> "fallo_servidor"
            else -> "http"
        }
    }
    if (detalle.clasesCausa.contains("InvalidContentTypeException")) return "formato_incompatible"
    return when (detalle.claseExcepcion) {
        "UnknownHostException" -> "dns"
        "ConnectException" -> "conexion"
        "SocketTimeoutException" -> "tiempo_agotado"
        else -> "otro"
    }
}

internal const val PROFUNDIDAD_MAXIMA_CAUSA = 8
private const val LONGITUD_MAXIMA_MIME = 64
