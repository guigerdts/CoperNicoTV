package com.copernicotv.app.data.actualizacion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.copernicotv.app.data.AdministracionGuiaRepositorio
import com.copernicotv.app.data.ResultadoActualizacionGuia
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Actualización manual de una guía EPG concreta (S7).
 *
 * El trabajo es único por guía: dos pulsaciones seguidas no lanzan dos
 * descargas del mismo XMLTV. Un fallo transitorio pide reintento con el backoff
 * del planificador; un rechazo permanente cierra el trabajo como fallido y deja
 * el diagnóstico en la fila de la guía.
 */
@HiltWorker
class GuiaFuenteWorker
    @AssistedInject
    constructor(
        @Assisted contexto: Context,
        @Assisted parametros: WorkerParameters,
        private val repositorio: AdministracionGuiaRepositorio,
    ) : CoroutineWorker(contexto, parametros) {
        override suspend fun doWork(): Result {
            val fuenteGuiaId = DatosGuia.fuenteGuiaId(inputData) ?: return Result.failure(DatosGuia.fallo())
            return when (repositorio.actualizarAhora(fuenteGuiaId)) {
                is ResultadoActualizacionGuia.Exitosa -> Result.success(DatosGuia.de(fuenteGuiaId))
                ResultadoActualizacionGuia.SinTrabajo -> Result.success(DatosGuia.sinTrabajo(fuenteGuiaId))
                is ResultadoActualizacionGuia.Reintentar ->
                    if (runAttemptCount < MAX_INTENTOS) {
                        Result.retry()
                    } else {
                        Result.failure(DatosGuia.fallo(fuenteGuiaId))
                    }
                is ResultadoActualizacionGuia.Fallo -> Result.failure(DatosGuia.fallo(fuenteGuiaId))
            }
        }

        private companion object {
            /** Tope de reintentos de WorkManager; el reintento lento lo pone la política. */
            const val MAX_INTENTOS = 3
        }
    }
