package com.copernicotv.app.data.actualizacion

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.copernicotv.app.data.AdministracionGuiaRepositorio
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Pasada automática sobre todas las guías EPG vencidas (S7, H-04).
 *
 * No actualiza "todas": el repositorio respeta el intervalo configurado por
 * guía y la política de reintento. Si no había nada vencido, el trabajo no
 * publica `completado` sino `sin_trabajo`, para que una pasada vacía no se
 * presente como una actualización exitosa.
 */
@HiltWorker
class GuiaAutomaticaWorker
    @AssistedInject
    constructor(
        @Assisted contexto: Context,
        @Assisted parametros: WorkerParameters,
        private val repositorio: AdministracionGuiaRepositorio,
    ) : CoroutineWorker(contexto, parametros) {
        override suspend fun doWork(): Result {
            val resumen = repositorio.procesarPendientes()
            return when {
                resumen.ejecutadas == 0 -> Result.success(DatosGuia.sinTrabajo())
                resumen.reintentables > 0 -> Result.retry()
                resumen.exitosas == 0 && resumen.fallidas > 0 -> Result.failure(DatosGuia.fallo())
                else -> Result.success(DatosGuia.de(0L))
            }
        }
    }
