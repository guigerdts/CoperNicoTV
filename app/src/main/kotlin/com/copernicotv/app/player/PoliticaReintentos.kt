package com.copernicotv.app.player

import androidx.media3.common.PlaybackException
import kotlinx.coroutines.delay

/** Esperas de S20: primer reintento a 1 s, segundo a 2 s y tercero a 4 s. */
val ESPERAS_REINTENTO_MS: List<Long> = listOf(1_000L, 2_000L, 4_000L)

/** Clasificación de un fallo del motor respecto de la política S20. */
enum class ClaseFallo {
    TRANSITORIO,
    DEFINITIVO,
}

/** Acción que la sesión debe ejecutar ante un fallo del motor. */
sealed interface DecisionReintento {
    /** Programa el reintento número [numero] tras [esperaMs] milisegundos. */
    data class Reintentar(
        val numero: Int,
        val esperaMs: Long,
    ) : DecisionReintento

    /** Ya hay un reintento pendiente: el fallo duplicado no programa otro. */
    data object Ignorar : DecisionReintento

    /** S20 agotó los reintentos o el fallo no es recuperable. */
    data object Definitivo : DecisionReintento
}

/**
 * Política de recuperación de S20, inyectable.
 *
 * [esperar] sustituye la espera real en las pruebas instrumentadas: la puerta
 * libera cada reintento a demanda, así que la secuencia se observa sin depender
 * del reloj. Las esperas en sí son las de S20 y no se negocian.
 */
class PoliticaReintentos(
    val esperasMs: List<Long> = ESPERAS_REINTENTO_MS,
    val esperar: suspend (Long) -> Unit = { delay(it) },
) {
    /** Número máximo de reintentos de una sesión. */
    val maximo: Int
        get() = esperasMs.size

    /** Estado de reintentos de una sesión nueva. */
    fun maquina(): MaquinaReintentos = MaquinaReintentos(esperasMs)
}

/**
 * Estado puro de los reintentos de una sesión de reproducción.
 *
 * No toca el motor ni la interfaz: con los fallos recibidos decide si
 * corresponde reintentar, ignorar un duplicado o rendirse.
 *
 * S20 no define si un `READY` intermedio repone el presupuesto. Se adopta un
 * presupuesto por sesión que no se repone: garantiza como máximo tres
 * reintentos por apertura de Canal y evita un bucle si el contenido falla una y
 * otra vez tras recuperarse brevemente.
 */
class MaquinaReintentos(
    private val esperasMs: List<Long> = ESPERAS_REINTENTO_MS,
) {
    private var completados = 0
    private var pendiente = false

    /** Reintentos configurados para la sesión. */
    val maximo: Int
        get() = esperasMs.size

    /** Reintentos ya enviados al motor. */
    val realizados: Int
        get() = completados

    /** Decide el siguiente paso ante un fallo del motor. */
    fun alFallar(clasificacion: ClaseFallo): DecisionReintento {
        if (clasificacion == ClaseFallo.DEFINITIVO) {
            pendiente = false
            return DecisionReintento.Definitivo
        }
        if (pendiente) return DecisionReintento.Ignorar
        val espera = esperasMs.getOrNull(completados) ?: return DecisionReintento.Definitivo
        pendiente = true
        return DecisionReintento.Reintentar(numero = completados + 1, esperaMs = espera)
    }

    /** Confirma que el reintento pendiente ya se envió al motor. */
    fun alEjecutar() {
        if (!pendiente) return
        pendiente = false
        completados += 1
    }
}

/**
 * Clasifica un fallo del motor según S20.
 *
 * S20 pide reintentar errores transitorios y no reintentar 4xx no
 * recuperables, pero no enumera los códigos. Decisión documentada: son
 * transitorios los cortes y timeouts de red, el timeout de reproducción y los
 * HTTP 5xx; todo lo demás es definitivo. Ningún 4xx se reintenta, incluidos 408
 * y 429: S20 no define 4xx recuperables y reintentar un error de cliente repite
 * una petición que no puede mejorar. `IO_UNSPECIFIED` también es definitivo
 * porque no hay evidencia de que sea transitorio.
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
