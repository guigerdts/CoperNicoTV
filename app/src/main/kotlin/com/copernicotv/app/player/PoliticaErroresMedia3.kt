package com.copernicotv.app.player

import androidx.media3.common.C
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy

/**
 * Política de errores del motor para S20.
 *
 * Media3 no se queda quieto con `DefaultLoadErrorHandlingPolicy(0)`: cuando la
 * respuesta HTTP declara longitud conocida, `ProgressiveMediaPeriod.configureRetry`
 * devuelve `true` y `getRetryDelayMsFor` calcula `0` ms para el primer fallo, así
 * que el motor repite la petición por su cuenta. Ese intento extra duplicaría los
 * que gobierna la aplicación y haría que un 4xx no recuperable se pidiera dos veces.
 *
 * Devolver `C.TIME_UNSET` hace que el motor no reintente nunca: la sesión recibe el
 * error y [PoliticaReintentos] aplica la política de S20 (1 s, 2 s y 4 s, sin
 * reintento para los 4xx no recuperables). El presupuesto de intentos queda en un
 * único dueño.
 */
class PoliticaErroresMedia3 : LoadErrorHandlingPolicy {
    override fun getFallbackSelectionFor(
        fallbackOptions: LoadErrorHandlingPolicy.FallbackOptions,
        loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo,
    ): LoadErrorHandlingPolicy.FallbackSelection? = null

    override fun getRetryDelayMsFor(loadErrorInfo: LoadErrorHandlingPolicy.LoadErrorInfo): Long = C.TIME_UNSET

    override fun getMinimumLoadableRetryCount(dataType: Int): Int = 0
}
