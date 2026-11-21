package com.copernicotv.app.ota

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequest
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequest
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.WorkRequest
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Nombres, tags y período de los trabajos del OTA.
 *
 * Deliberadamente distintos de los de la actualización de listas M3U/M3U8:
 * ambos sistemas comparten WorkManager y un tag reutilizado cancelaría o
 * observaría trabajo ajeno. Las pruebas comparan contra los nombres del otro
 * sistema para que el aislamiento no dependa de la memoria de nadie.
 */
object TrabajosOta {
    const val PERIODICO = "ota-comprobacion-periodica"
    const val MANUAL = "ota-comprobacion-manual"

    /** Tag común a todo el OTA; sirve para reconciliar y para observar la UI. */
    const val ETIQUETA = "ota"
    const val ETIQUETA_PERIODICO = "ota-periodico"
    const val ETIQUETA_MANUAL = "ota-manual"

    const val HORAS_PERIODO = 12L

    /** Tags con los que se encola cada trabajo. */
    val ETIQUETAS_PERIODICO = listOf(ETIQUETA, ETIQUETA_PERIODICO)
    val ETIQUETAS_MANUAL = listOf(ETIQUETA, ETIQUETA_MANUAL)
}

/**
 * Programación del OTA.
 *
 * Periódico cada 12 h con red y batería no baja; manual bajo demanda desde
 * Ajustes. Ambos son trabajos únicos por nombre, así que pulsar "Buscar" dos
 * veces no duplica descargas ni notificaciones.
 */
@Singleton
class ProgramadorOta
    @Inject
    constructor(
        @ApplicationContext private val contexto: Context,
    ) {
        /** Crea o actualiza el trabajo periódico sin reiniciar el ciclo en cada arranque. */
        fun asegurarPeriodico() {
            gestor()
                .enqueueUniquePeriodicWork(
                    TrabajosOta.PERIODICO,
                    ExistingPeriodicWorkPolicy.UPDATE,
                    peticionPeriodica(),
                )
        }

        /** Encola una comprobación inmediata; si ya hay una pendiente no la duplica. */
        fun pedirComprobacionManual() {
            gestor()
                .enqueueUniqueWork(
                    TrabajosOta.MANUAL,
                    ExistingWorkPolicy.KEEP,
                    peticionManual(),
                )
        }

        /** Estado observable del OTA completo, para la pantalla de Ajustes. */
        fun observaciones(): Flow<List<WorkInfo>> = gestor().getWorkInfosByTagFlow(TrabajosOta.ETIQUETA)

        /**
         * Deja el OTA en un estado conocido al arrancar.
         *
         * Cancela trabajos con el tag del OTA que no correspondan a los nombres
         * actuales (restos de versiones anteriores o de nombres renombrados) y
         * garantiza que el periódico exista. Es `suspend` y consulta el estado
         * por Flow: la API de WorkManager es asíncrona y no debe bloquear el
         * hilo del llamador.
         */
        suspend fun reconciliar() {
            val etiquetasConocidas = setOf(TrabajosOta.ETIQUETA_PERIODICO, TrabajosOta.ETIQUETA_MANUAL)
            val trabajo = gestor()
            trabajo.getWorkInfosByTagFlow(TrabajosOta.ETIQUETA).first()
                .filter { info -> !info.state.isFinished }
                .filter { info -> info.tags.none { tag -> tag in etiquetasConocidas } }
                .forEach { info -> trabajo.cancelWorkById(info.id) }
            asegurarPeriodico()
        }

        /** Cancela el periódico: solo lo usa el apagado explícito del OTA. */
        fun cancelarPeriodico() {
            gestor().cancelUniqueWork(TrabajosOta.PERIODICO)
        }

        private fun peticionPeriodica(): PeriodicWorkRequest {
            val constructor =
                PeriodicWorkRequestBuilder<OtaWorker>(TrabajosOta.HORAS_PERIODO, TimeUnit.HOURS)
                    .setConstraints(restricciones())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            TrabajosOta.ETIQUETAS_PERIODICO.forEach { etiqueta -> constructor.addTag(etiqueta) }
            return constructor.build()
        }

        private fun peticionManual(): OneTimeWorkRequest {
            val constructor =
                OneTimeWorkRequestBuilder<OtaWorker>()
                    .setConstraints(restricciones())
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, WorkRequest.MIN_BACKOFF_MILLIS, TimeUnit.MILLISECONDS)
            TrabajosOta.ETIQUETAS_MANUAL.forEach { etiqueta -> constructor.addTag(etiqueta) }
            return constructor.build()
        }

        private fun restricciones(): Constraints =
            Constraints.Builder()
                .setRequiredNetworkType(NetworkType.CONNECTED)
                .setRequiresBatteryNotLow(true)
                .build()

        private fun gestor(): WorkManager = WorkManager.getInstance(contexto)
    }
