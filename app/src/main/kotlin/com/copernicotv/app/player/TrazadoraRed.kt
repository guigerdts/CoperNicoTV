package com.copernicotv.app.player

import com.copernicotv.core.player.TrazaRedireccion

/**
 * User-Agent determinista de las peticiones HTTP de reproducción (E7-T8).
 *
 * Sin él, la fuente HTTP de Media3 usa el User-Agent de la plataforma, que no
 * es observable ni reproducible. Es una cabecera pública, no una credencial:
 * puede reenviarse a cualquier dominio de la cadena de redirecciones.
 */
const val AGENTE_REPRODUCCION = "CoperNicoTV/1.0 (Android)"

/**
 * Estado de trazado de red de una sesión de reproducción (E7-T8, S5).
 *
 * Recoge, por sesión, la última traza de redirecciones observada en un fallo
 * HTTP y el tipo de MediaSource creado por la fábrica de fuentes. Los escritos
 * llegan de hilos de carga del motor; los lectores son la sesión y el
 * diagnóstico, por lo que cada campo se protege con su candado.
 *
 * El contenido es evidencia segura para mostrar (S57a): esquemas, códigos y
 * contadores, nunca la URL completa ni cabeceras de credenciales.
 */
class TrazadoraRed(
    val agenteUsuario: String,
) {
    private val candado = Any()
    private var trazaInterna: TrazaRedireccion? = null
    private var fuenteMediaInterna: String? = null

    /** Última traza de redirecciones de un fallo HTTP, si hubo alguna. */
    val traza: TrazaRedireccion?
        get() = synchronized(candado) { trazaInterna }

    /** Tipo de MediaSource creado (p. ej. `ProgressiveMediaSource`). */
    val tipoFuenteMedia: String?
        get() = synchronized(candado) { fuenteMediaInterna }

    /** Registra la última traza de un fallo HTTP observado por la fuente. */
    fun registrar(nueva: TrazaRedireccion) {
        synchronized(candado) { trazaInterna = nueva }
    }

    /** Registra el tipo de MediaSource creado por la fábrica de fuentes. */
    fun registrarFuenteMedia(tipo: String) {
        synchronized(candado) { fuenteMediaInterna = tipo }
    }
}
