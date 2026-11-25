package com.copernicotv.core.conexion

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonObject
import java.time.Instant

/** Clase de una respuesta HTTP, suficiente para decidir el siguiente paso. */
enum class ClaseRespuestaHttp {
    OK,
    REDIRECCION,
    NO_AUTORIZADO,
    NO_ENCONTRADO,
    ERROR_CLIENTE,
    ERROR_SERVIDOR,
    DESCONOCIDO,
}

/**
 * Diagnóstico de una respuesta HTTP.
 *
 * [reintentable] distingue un fallo transitorio (5xx, 408, 429) de uno que no
 * se arregla repitiendo la misma petición (401/403/404).
 */
data class DiagnosticoHttp(
    val clase: ClaseRespuestaHttp,
    val codigo: Int,
    val reintentable: Boolean,
    val mensaje: String,
)

/**
 * Estado de la cuenta tal como lo **reporta** el proveedor.
 *
 * Ningún campo se presenta como verificado por la aplicación: expiración,
 * conexiones activas y máximo de conexiones son datos del panel
 * ([reportadoPorProveedor]).
 */
data class EstadoCuentaXtream(
    val respuestaValida: Boolean,
    val autenticado: Boolean,
    val estado: String? = null,
    val expiraEn: Instant? = null,
    val conexionesActivas: Int? = null,
    val maxConexiones: Int? = null,
    val formatosPermitidos: List<String> = emptyList(),
    val mensaje: String? = null,
    val reportadoPorProveedor: Boolean = true,
)

/**
 * Análisis puro de respuestas de Xtream Codes.
 *
 * No hace red ni guarda estado. El adaptador de `:app` decide timeouts,
 * reintentos y redirecciones con estos diagnósticos; aquí solo se clasifica lo
 * recibido. Un cuerpo HTML en lugar de JSON se trata como portal o error del
 * proveedor, nunca como una cuenta válida.
 */
object AnalizadorRespuestaXtream {
    private val json =
        Json {
            ignoreUnknownKeys = true
            isLenient = false
        }

    fun clasificarHttp(codigo: Int): DiagnosticoHttp =
        when {
            codigo in 200..299 ->
                DiagnosticoHttp(ClaseRespuestaHttp.OK, codigo, reintentable = false, mensaje = "Respuesta correcta.")
            codigo in 300..399 ->
                DiagnosticoHttp(
                    ClaseRespuestaHttp.REDIRECCION,
                    codigo,
                    reintentable = false,
                    mensaje = "El servidor redirige a otra ubicación.",
                )
            codigo == 401 || codigo == 403 ->
                DiagnosticoHttp(
                    ClaseRespuestaHttp.NO_AUTORIZADO,
                    codigo,
                    reintentable = false,
                    mensaje = "El servidor rechazó las credenciales.",
                )
            codigo == 404 ->
                DiagnosticoHttp(
                    ClaseRespuestaHttp.NO_ENCONTRADO,
                    codigo,
                    reintentable = false,
                    mensaje = "El servidor no expone ese recurso.",
                )
            codigo == 408 || codigo == 429 ->
                DiagnosticoHttp(
                    ClaseRespuestaHttp.ERROR_CLIENTE,
                    codigo,
                    reintentable = true,
                    mensaje = "El servidor pidió esperar antes de reintentar.",
                )
            codigo in 400..499 ->
                DiagnosticoHttp(
                    ClaseRespuestaHttp.ERROR_CLIENTE,
                    codigo,
                    reintentable = false,
                    mensaje = "La petición fue rechazada.",
                )
            codigo in 500..599 ->
                DiagnosticoHttp(
                    ClaseRespuestaHttp.ERROR_SERVIDOR,
                    codigo,
                    reintentable = true,
                    mensaje = "El servidor falló; puede ser transitorio.",
                )
            else ->
                DiagnosticoHttp(ClaseRespuestaHttp.DESCONOCIDO, codigo, reintentable = false, mensaje = "Respuesta no clasificada.")
        }

    /** True si el cuerpo parece HTML (portal, página de error o captura cautiva). */
    fun esDocumentoHtml(cuerpo: String): Boolean {
        val inicio = cuerpo.trimStart().take(512).lowercase()
        return inicio.startsWith("<!doctype html") ||
            inicio.startsWith("<html") ||
            inicio.contains("<head") ||
            inicio.contains("<body")
    }

    /**
     * Interpreta el JSON de `player_api.php`.
     *
     * [ahora] solo se usa para decidir si la cuenta reportada está vencida; el
     * valor de expiración se conserva tal como lo reportó el proveedor.
     */
    fun analizarCuenta(
        cuerpo: String,
        ahora: Instant = Instant.now(),
    ): EstadoCuentaXtream {
        if (cuerpo.isBlank()) {
            return EstadoCuentaXtream(respuestaValida = false, autenticado = false, mensaje = "respuesta_vacia")
        }
        if (esDocumentoHtml(cuerpo)) {
            return EstadoCuentaXtream(respuestaValida = false, autenticado = false, mensaje = "respuesta_html")
        }
        val raiz =
            runCatching { json.parseToJsonElement(cuerpo).jsonObject }
                .getOrElse {
                    return EstadoCuentaXtream(
                        respuestaValida = false,
                        autenticado = false,
                        mensaje = "respuesta_no_json",
                    )
                }
        val info =
            raiz["user_info"] as? JsonObject
                ?: return EstadoCuentaXtream(
                    respuestaValida = false,
                    autenticado = false,
                    mensaje = "sin_user_info",
                )
        val auth = numero(info, "auth")?.toInt()
        val autenticado = auth == 1
        val estado = texto(info, "status")
        val expiraSegundos = numero(info, "exp_date")?.toLong()
        val expira = expiraSegundos?.takeIf { it > 0 }?.let { Instant.ofEpochSecond(it) }
        val activas = numero(info, "active_cons")?.toInt()
        val maximo = numero(info, "max_connections")?.toInt()
        val formatos =
            (info["allowed_output_formats"] as? JsonArray)
                ?.mapNotNull { (it as? JsonPrimitive)?.takeIf { valor -> valor !is JsonNull }?.content }
                ?.filter { it.isNotBlank() }
                .orEmpty()
        val mensaje = texto(info, "message")?.takeIf { it.isNotBlank() }
        val vencida = expira?.isBefore(ahora) == true
        return EstadoCuentaXtream(
            respuestaValida = true,
            autenticado = autenticado && !vencida,
            estado = estado,
            expiraEn = expira,
            conexionesActivas = activas,
            maxConexiones = maximo,
            formatosPermitidos = formatos,
            mensaje = if (vencida) "cuenta_vencida" else mensaje,
        )
    }

    private fun texto(
        objeto: JsonObject,
        clave: String,
    ): String? =
        (objeto[clave] as? JsonPrimitive)
            ?.takeIf { valor -> valor !is JsonNull }
            ?.content
            ?.trim()

    private fun numero(
        objeto: JsonObject,
        clave: String,
    ): Double? = texto(objeto, clave)?.toDoubleOrNull()
}
