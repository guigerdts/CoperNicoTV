package com.copernicotv.app.data

import android.security.NetworkSecurityPolicy

/**
 * Consulta la política de tráfico en texto claro del sistema para un host
 * (E7-T7).
 *
 * Con targetSdk >= 28 la plataforma bloquea HTTP sin cifrar cuando la
 * configuración de seguridad de red no lo permite. El bloqueo se produce
 * dentro de `HttpURLConnection` y llega como una `IOException` genérica, que
 * se traduciría a `sin_conexion` y ocultaría la causa real. Consultar la
 * política antes de abrir la conexión permite devolver `bloqueo_http` sin
 * intentar la conexión y sin registrar el host.
 *
 * Es la implementación por defecto de [VerificacionCleartext]; las pruebas
 * inyectan la suya para no depender de la política del entorno.
 */
internal fun politicaCleartextPermitida(host: String): Boolean = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)

/**
 * Verificación inyectable de la política de texto claro.
 *
 * Es un tipo propio y no `Function1` porque Hilt inyecta los constructores de
 * `CatalogoRepositorio` y `ActualizacionRepositorio`: el grafo necesita un
 * binding declarado (`AppModule`) y no puede satisfacer un tipo función.
 */
fun interface VerificacionCleartext {
    /** `true` si el host puede recibir HTTP en texto claro. */
    fun permitido(host: String): Boolean
}
