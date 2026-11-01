package com.copernicotv.core.actualizacion

/**
 * Clase de un error de Actualización, según DC-07 punto 7.
 *
 * La clase decide si el trabajo se reintenta o termina en fallo. No decide nada
 * sobre el catálogo: un fallo nunca borra ni modifica los datos persistidos.
 */
enum class ClaseErrorActualizacion {
    /** Timeout, 5xx, red interrumpida o error de E/S recuperable. */
    TRANSITORIO,

    /** URL inválida, 4xx no recuperable, formato inválido o rechazo definitivo. */
    PERMANENTE,

    /** Claves AES/HMAC no disponibles (DC-04): fallo tipado, sin reintento en el ciclo. */
    CREDENCIALES_NO_DISPONIBLES,
}

/**
 * Clasificación de los códigos que produce la Actualización.
 *
 * La tabla refleja los códigos de `ActualizacionRepositorio` más el código
 * propio de credenciales no disponibles de DC-04. Un código desconocido se
 * trata como permanente: reintentar a ciegas puede repetir una operación que
 * nunca va a funcionar y retrasa el siguiente ciclo periódico.
 */
object ClasificacionErroresActualizacion {
    fun clasificar(codigo: String): ClaseErrorActualizacion =
        when (codigo) {
            "timeout", "http_5xx", "sin_conexion", "archivo_inaccesible" -> ClaseErrorActualizacion.TRANSITORIO
            "credenciales_no_disponibles" -> ClaseErrorActualizacion.CREDENCIALES_NO_DISPONIBLES
            else -> ClaseErrorActualizacion.PERMANENTE
        }
}
