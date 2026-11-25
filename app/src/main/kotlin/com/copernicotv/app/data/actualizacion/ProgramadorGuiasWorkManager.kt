package com.copernicotv.app.data.actualizacion

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
import androidx.work.await
import com.copernicotv.app.data.db.AppDatabase
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Planificación de la actualización automática y manual de guías EPG (S7).
 *
 * La pasada automática es un único trabajo periódico que revisa qué guías
 * vencieron; el intervalo real vive en cada `FuenteGuia`, así que añadir o
 * cambiar una guía no requiere recrear el trabajo. La actualización manual es
 * un trabajo único por guía con `KEEP`: pulsar dos veces no duplica descargas.
 */
@Singleton
class ProgramadorGuiasWorkManager
    @Inject
    constructor(
        private val db: AppDatabase,
        private val trabajo: Provider<WorkManager>,
    ) : ProgramadorGuias {
        override suspend fun reconciliar() {
            if (db.fuenteGuiaDao().automaticas().isEmpty()) {
                trabajo.get().cancelUniqueWork(NOMBRE_AUTOMATICA).await()
                return
            }
            trabajo
                .get()
                .enqueueUniquePeriodicWork(NOMBRE_AUTOMATICA, ExistingPeriodicWorkPolicy.UPDATE, periodico())
                .await()
        }

        override suspend fun solicitar(fuenteGuiaId: Long) {
            trabajo
                .get()
                .enqueueUniqueWork(nombreFuente(fuenteGuiaId), ExistingWorkPolicy.KEEP, manual(fuenteGuiaId))
                .await()
        }

        override fun observarEnCurso(): Flow<Set<Long>> =
            trabajo
                .get()
                .getWorkInfosByTagFlow(TAG_GUIA)
                .map { infos ->
                    infos
                        .filter { it.state == WorkInfo.State.RUNNING || it.state == WorkInfo.State.ENQUEUED }
                        .mapNotNull(::fuenteGuiaIdDe)
                        .toSet()
                }.distinctUntilChanged()

        private fun fuenteGuiaIdDe(info: WorkInfo): Long? =
            info.tags
                .firstOrNull { it.startsWith(PREFIJO_TAG_FUENTE) }
                ?.removePrefix(PREFIJO_TAG_FUENTE)
                ?.toLongOrNull()

        private fun manual(fuenteGuiaId: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<GuiaFuenteWorker>()
                .setConstraints(RESTRICCIONES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETARDO_INICIAL_SEGUNDOS, TimeUnit.SECONDS)
                .setInputData(DatosGuia.de(fuenteGuiaId))
                .addTag(TAG_GUIA)
                .addTag(tagFuente(fuenteGuiaId))
                .build()

        private fun periodico(): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<GuiaAutomaticaWorker>(INTERVALO_REVISION_HORAS, TimeUnit.HOURS)
                .setConstraints(RESTRICCIONES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETARDO_INICIAL_SEGUNDOS, TimeUnit.SECONDS)
                .addTag(TAG_GUIA)
                .build()

        companion object {
            /** Tag común a todo trabajo de guía. */
            const val TAG_GUIA: String = "guia-epg"

            /** Cada cuánto se revisa qué guías vencieron. */
            const val INTERVALO_REVISION_HORAS: Long = 6L

            const val RETARDO_INICIAL_SEGUNDOS: Long = 30L

            private const val PREFIJO_TAG_FUENTE = "guia:"

            private val RESTRICCIONES: Constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            /** Nombre único de la pasada automática. */
            const val NOMBRE_AUTOMATICA: String = "actualizar-guia-automatica"

            /** Nombre único de la actualización manual de una guía. */
            fun nombreFuente(fuenteGuiaId: Long): String = "actualizar-guia-$fuenteGuiaId"

            fun tagFuente(fuenteGuiaId: Long): String = "$PREFIJO_TAG_FUENTE$fuenteGuiaId"
        }
    }
