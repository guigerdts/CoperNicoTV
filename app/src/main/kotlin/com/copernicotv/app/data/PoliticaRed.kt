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
 * Es el valor por defecto de las verificaciones inyectables de
 * [DescargadorHttp], `CatalogoRepositorio` y `ActualizacionRepositorio`; las
 * pruebas JVM/Robolectric inyectan su propio predicado para no depender de la
 * política del entorno.
 */
internal fun politicaCleartextPermitida(host: String): Boolean = NetworkSecurityPolicy.getInstance().isCleartextTrafficPermitted(host)
