package com.copernicotv.app.ota

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject

/**
 * Trabajo periódico y manual del OTA.
 *
 * No conoce la política de reintentos: el coordinador decide el resultado y el
 * worker solo traduce. Un fallo de red o del servidor pide reintento con el
 * backoff configurado en [ProgramadorOta]; un fallo de configuración, firma,
 * manifiesto o verificación es definitivo hasta la próxima versión publicada.
 */
@HiltWorker
class OtaWorker
    @AssistedInject
    constructor(
        @Assisted contexto: Context,
        @Assisted parametros: WorkerParameters,
        private val coordinador: CoordinadorOta,
    ) : CoroutineWorker(contexto, parametros) {
        override suspend fun doWork(): Result =
            when (val resultado = coordinador.ejecutar()) {
                is ResultadoOta.Fallo ->
                    if (reintentable(resultado.tipo)) {
                        if (runAttemptCount < INTENTOS_MAXIMOS) Result.retry() else Result.failure()
                    } else {
                        Result.failure()
                    }
                is ResultadoOta.SinNovedad, is ResultadoOta.Incompatible, is ResultadoOta.Descargada -> Result.success()
            }

        private fun reintentable(tipo: FalloOta): Boolean = tipo == FalloOta.SIN_RED || tipo == FalloOta.SERVIDOR

        private companion object {
            /** WorkManager ya contó el intento actual con `runAttemptCount` desde 0. */
            const val INTENTOS_MAXIMOS = 3
        }
    }
