package com.copernicotv.app.data.actualizacion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.Data
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Trabajo de Actualización de una Fuente (DC-07 puntos 1, 5, 6 y 7).
 *
 * Lo usan el trabajo periódico y el manual con el mismo formato de datos de
 * entrada. El worker no conoce la política de reintentos ni de exclusión: pide
 * la ejecución al coordinador y traduce su decisión a `Result`.
 *
 * `runAttemptCount` es el intento de WorkManager (0 en la primera ejecución).
 * Se pasa tal cual para que el tope de 3 reintentos viva en el coordinador,
 * donde se prueba sin WorkManager.
 */
@HiltWorker
class ActualizacionWorker
    @AssistedInject
    constructor(
        @Assisted contexto: Context,
        @Assisted parametros: WorkerParameters,
        private val coordinador: CoordinadorActualizacion,
    ) : CoroutineWorker(contexto, parametros) {
        override suspend fun doWork(): Result {
            val fuenteId = DatosActualizacion.fuenteId(inputData) ?: return Result.failure()
            val causa = DatosActualizacion.causa(inputData) ?: CausasActualizacion.AUTOMATICA
            val resultado = coordinador.ejecutar(fuenteId, causa, runAttemptCount)
            return when (decisionDe(resultado)) {
                DecisionEjecucion.EXITO -> Result.success(datosDe(resultado, fuenteId, causa))
                DecisionEjecucion.REINTENTAR -> Result.retry()
                DecisionEjecucion.FALLAR -> Result.failure(datosDe(resultado, fuenteId, causa))
            }
        }

        /**
         * Datos de salida del trabajo.
         *
         * Coalescido y Reintentar no publican resultado: en el primer caso lo
         * publica la ejecución que sí corrió, y en el segundo el resultado
         * parcial se descarta porque el ciclo aún no terminó.
         */
        private fun datosDe(
            resultado: ResultadoEjecucion,
            fuenteId: Long,
            causa: String,
        ): Data =
            when (resultado) {
                is ResultadoEjecucion.Exito -> DatosActualizacion.de(fuenteId, causa, resultado.resumenes)
                is ResultadoEjecucion.Fallo -> DatosActualizacion.de(fuenteId, causa, resultado.resumenes)
                is ResultadoEjecucion.Reintentar, ResultadoEjecucion.Coalescido -> Data.EMPTY
            }
    }
