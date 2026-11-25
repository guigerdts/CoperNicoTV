package com.copernicotv.app.data.actualizacion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.copernicotv.app.data.ResumenActualizacion
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
            // H-04: el avance se publica con `setProgress` para que la UI pueda
            // mostrar Listas terminadas sobre el total mientras la ejecución corre.
            val resultado =
                coordinador.ejecutar(fuenteId, causa, runAttemptCount) { hechos, total ->
                    setProgress(DatosActualizacion.progreso(hechos, total))
                }
            return when (decisionDe(resultado)) {
                DecisionEjecucion.EXITO -> Result.success(DatosActualizacion.de(fuenteId, causa, resumenesDe(resultado)))
                // H-04: la ejecución no hizo trabajo. WorkManager no tiene un
                // cuarto estado final, así que se publica `resultado=sin_trabajo`
                // en los datos de salida y nunca se presenta como actualización
                // exitosa. El estado SUCCEEDED solo evita un reintento inútil.
                DecisionEjecucion.SIN_TRABAJO -> Result.success(DatosActualizacion.sinTrabajo(fuenteId, causa))
                DecisionEjecucion.REINTENTAR -> Result.retry()
                DecisionEjecucion.FALLAR ->
                    Result.failure(DatosActualizacion.fallo(fuenteId, causa, resumenesDe(resultado)))
            }
        }

        /**
         * Resúmenes que publica cada desenlace.
         *
         * `Reintentar` no los publica: el ciclo todavía no terminó y la
         * ejecución que lo cierre será la que informe el resultado final.
         */
        private fun resumenesDe(resultado: ResultadoEjecucion): List<ResumenActualizacion> =
            when (resultado) {
                is ResultadoEjecucion.Exito -> resultado.resumenes
                is ResultadoEjecucion.Fallo -> resultado.resumenes
                is ResultadoEjecucion.Reintentar, ResultadoEjecucion.Coalescido -> emptyList()
            }
    }
