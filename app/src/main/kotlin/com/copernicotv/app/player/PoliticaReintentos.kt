package com.copernicotv.app.player

import androidx.media3.common.PlaybackException
import com.copernicotv.core.player.ClaseFallo
import com.copernicotv.core.player.ConfiguracionRecuperacion
import com.copernicotv.core.player.MaquinaRecuperacion
import kotlinx.coroutines.delay

/**
 * Política de recuperación de E7-T10 (S4), inyectable.
 *
 * Envuelve la máquina pura de `:core` ([MaquinaRecuperacion]) con las dos
 * esperas sustituibles por las pruebas: la espera entre reintentos
 * ([esperar], puerta de E6-T4) y la espera del periodo de estabilidad que
 * repone el presupuesto ([esperarEstabilidad]). El cálculo de las esperas —
 * backoff exponencial con jitter, techo configurable y presupuesto con
 * reposición — vive en `:core` y no se duplica aquí.
 */
class PoliticaReintentos(
    val configuracion: ConfiguracionRecuperacion = ConfiguracionRecuperacion(),
    val esperar: suspend (Long) -> Unit = { delay(it) },
    val esperarEstabilidad: suspend (Long) -> Unit = { delay(it) },
) {
    /** Reintentos configurados para una sesión. */
    val maximo: Int
        get() = configuracion.maximoIntentos

    /** Estado de reintentos de una sesión nueva. */
    fun maquina(): MaquinaRecuperacion = MaquinaRecuperacion(configuracion)
}

/**
 * Clasifica un fallo del motor según S4.
 *
 * Son transitorios los cortes y timeouts de red, el timeout de reproducción
 * y los HTTP 5xx; todo lo demás es definitivo. Ningún 4xx se reintenta,
 * incluidos 408 y 429: reintentar un error de cliente repite una petición
 * que no puede mejorar, y 401/403/404 no se recuperan con más intentos.
 * `IO_UNSPECIFIED` también es definitivo porque no hay evidencia de que sea
 * transitorio. Los errores de decodificación son un problema audiovisual,
 * no de red (S3), y por eso caen en definitivo aquí.
 */
fun clasificarFallo(
    codigoError: Int,
    estadoHttp: Int?,
): ClaseFallo =
    when (codigoError) {
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        PlaybackException.ERROR_CODE_TIMEOUT,
        -> ClaseFallo.TRANSITORIO
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            if (estadoHttp != null && estadoHttp >= 500) ClaseFallo.TRANSITORIO else ClaseFallo.DEFINITIVO
        else -> ClaseFallo.DEFINITIVO
    }

/**
 * Extrae el estado HTTP de la cadena de causas del error.
 *
 * Devuelve solo el número: ni la URL ni el mensaje de Media3 salen de aquí
 * (S57a). Delega en la captura de detalle (DetalleFallo.kt) para que exista un
 * único recorrido de la cadena: mismo resultado, misma profundidad acotada.
 */
fun estadoHttpDe(error: PlaybackException): Int? = capturarDetalleFallo(error).estadoHttp
