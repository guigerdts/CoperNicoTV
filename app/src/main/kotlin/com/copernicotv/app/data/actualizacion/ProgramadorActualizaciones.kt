package com.copernicotv.app.data.actualizacion

import android.util.Log
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
import com.copernicotv.app.data.ResumenActualizacion
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.actualizacion.ResultadoIntervalo
import com.copernicotv.core.actualizacion.intervaloEfectivo
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Provider
import javax.inject.Singleton

/**
 * Estado observable de una Fuente según sus trabajos de Actualización.
 *
 * @property enCurso hay un trabajo `ENQUEUED`, `RUNNING` o `BLOCKED`.
 * @property resumenes último resultado publicado por Lista.
 */
data class EstadoSolicitud(
    val enCurso: Boolean = false,
    val resumenes: List<ResumenActualizacion> = emptyList(),
) {
    fun resumenDe(listaId: Long): ResumenActualizacion? = resumenes.firstOrNull { it.listaId == listaId }
}

/** Planificador de la Actualización automática y manual (DC-07 puntos 5 y 6). */
interface ProgramadorActualizaciones {
    /** Estado por Fuente, derivado de WorkManager. */
    fun observar(): Flow<Map<Long, EstadoSolicitud>>

    /** Estado de la Fuente a la que pertenece [listaId]. */
    fun observarLista(listaId: Long): Flow<EstadoSolicitud>

    /** Encola la Actualización manual de la Fuente de [listaId]. */
    suspend fun solicitarLista(listaId: Long)

    /** Encola la Actualización manual de [fuenteId]. */
    suspend fun solicitarFuente(fuenteId: Long)

    /** Reconciliación de arranque: crea, actualiza y cancela programaciones. */
    suspend fun reconciliar()
}

/**
 * Implementación sobre WorkManager (DC-07 puntos 1 a 6 y 8).
 *
 * El trabajo periódico y el manual son trabajos distintos y con nombre propio:
 * la actualización manual no reemplaza al periódico ni reinicia su intervalo.
 * La exclusión entre ambos la resuelve [ReclamoFuente] dentro del coordinador,
 * no `ExistingWorkPolicy`, porque `KEEP` solo actúa dentro del mismo nombre.
 */
@Singleton
class ProgramadorWorkManager
    @Inject
    constructor(
        private val db: AppDatabase,
        private val preferencias: PreferenciasActualizacion,
        private val reclamo: ReclamoFuente,
        private val trabajo: Provider<WorkManager>,
    ) : ProgramadorActualizaciones {
        override fun observar(): Flow<Map<Long, EstadoSolicitud>> =
            trabajo
                .get()
                .getWorkInfosByTagFlow(TAG_ACTUALIZACION)
                .map(::agrupar)
                .distinctUntilChanged()

        override fun observarLista(listaId: Long): Flow<EstadoSolicitud> =
            flow {
                val fuenteId = db.listaDao().porId(listaId)?.fuenteId ?: return@flow
                emitAll(observar().map { estados -> estados[fuenteId] ?: EstadoSolicitud() })
            }

        override suspend fun solicitarLista(listaId: Long) {
            val fuenteId = db.listaDao().porId(listaId)?.fuenteId ?: return
            solicitarFuente(fuenteId)
        }

        override suspend fun solicitarFuente(fuenteId: Long) {
            trabajo
                .get()
                .enqueueUniqueWork(nombreManual(fuenteId), ExistingWorkPolicy.KEEP, manual(fuenteId))
                .await()
        }

        override suspend fun reconciliar() {
            val global = preferencias.intervaloGlobal().first()
            val fuentes = db.fuenteDao().todas()
            val vigentes = fuentes.map { it.id }.toSet()
            val programadas = preferencias.programadas()

            programadas.keys.filterNot { it in vigentes }.forEach { fuenteId ->
                trabajo.get().cancelUniqueWork(nombrePeriodico(fuenteId)).await()
                preferencias.olvidarProgramada(fuenteId)
                Log.i(TAG, "Programación cancelada: la Fuente $fuenteId ya no existe")
            }

            fuentes.forEach { fuente ->
                reconciliarFuente(
                    fuenteId = fuente.id,
                    intervalo = intervaloEfectivo(global, fuente.intervaloMinutos, fuente.usarIntervaloGlobal),
                    programado = programadas[fuente.id],
                )
            }

            limpiarReclamosHuerfanos()
        }

        private suspend fun reconciliarFuente(
            fuenteId: Long,
            intervalo: ResultadoIntervalo,
            programado: Long?,
        ) {
            when (intervalo) {
                is ResultadoIntervalo.Rechazado -> {
                    trabajo.get().cancelUniqueWork(nombrePeriodico(fuenteId)).await()
                    preferencias.olvidarProgramada(fuenteId)
                    Log.w(TAG, "Intervalo rechazado para la Fuente $fuenteId: ${intervalo.motivo}")
                }
                is ResultadoIntervalo.Aceptado -> {
                    val minutos = intervalo.intervalo.minutos
                    val politica =
                        if (programado == minutos) {
                            ExistingPeriodicWorkPolicy.KEEP
                        } else {
                            ExistingPeriodicWorkPolicy.UPDATE
                        }
                    trabajo
                        .get()
                        .enqueueUniquePeriodicWork(nombrePeriodico(fuenteId), politica, periodico(fuenteId, minutos))
                        .await()
                    preferencias.registrarProgramada(fuenteId, minutos)
                }
            }
        }

        /**
         * Limpia reclamos que WorkManager ya no respalda (DC-07 punto 6).
         *
         * Se consulta cada nombre único en lugar de leer solo los trabajos del
         * tag: un reclamo puede haber quedado de una ejecución cuyo trabajo ya
         * terminó o fue cancelado, y en ese caso WorkManager no lo reporta como
         * abierto.
         */
        private suspend fun limpiarReclamosHuerfanos() {
            reclamo.enCurso().keys.forEach { fuenteId ->
                if (!abierto(fuenteId)) {
                    reclamo.liberarHuerfano(fuenteId)
                    Log.i(TAG, "Reclamo huérfano liberado para la Fuente $fuenteId")
                }
            }
        }

        private suspend fun abierto(fuenteId: Long): Boolean =
            trabajo
                .get()
                .getWorkInfosForUniqueWorkFlow(nombrePeriodico(fuenteId))
                .first()
                .any { it.state in ESTADOS_ABIERTOS } ||
                trabajo
                    .get()
                    .getWorkInfosForUniqueWorkFlow(nombreManual(fuenteId))
                    .first()
                    .any { it.state in ESTADOS_ABIERTOS }

        private fun agrupar(infos: List<WorkInfo>): Map<Long, EstadoSolicitud> =
            infos
                .mapNotNull { info -> fuenteIdDe(info)?.let { it to info } }
                .groupBy({ (fuenteId, _) -> fuenteId }, { (_, info) -> info })
                .mapValues { (_, trabajos) -> estadoDe(trabajos) }

        private fun estadoDe(trabajos: List<WorkInfo>): EstadoSolicitud =
            EstadoSolicitud(
                enCurso = trabajos.any { it.state in ESTADOS_ABIERTOS },
                resumenes =
                    trabajos
                        .flatMap { DatosActualizacion.resumenes(it.outputData) }
                        .associateBy { it.listaId }
                        .values
                        .toList(),
            )

        private fun fuenteIdDe(info: WorkInfo): Long? =
            info.tags
                .firstOrNull { it.startsWith(PREFIJO_TAG_FUENTE) }
                ?.removePrefix(PREFIJO_TAG_FUENTE)
                ?.toLongOrNull()

        private fun manual(fuenteId: Long): OneTimeWorkRequest =
            OneTimeWorkRequestBuilder<ActualizacionWorker>()
                .setConstraints(RESTRICCIONES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETARDO_INICIAL_SEGUNDOS, TimeUnit.SECONDS)
                .setInputData(DatosActualizacion.de(fuenteId, CausasActualizacion.MANUAL, emptyList()))
                .addTag(TAG_ACTUALIZACION)
                .addTag(tagFuente(fuenteId))
                .build()

        private fun periodico(
            fuenteId: Long,
            minutos: Long,
        ): PeriodicWorkRequest =
            PeriodicWorkRequestBuilder<ActualizacionWorker>(minutos, TimeUnit.MINUTES)
                .setConstraints(RESTRICCIONES)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, RETARDO_INICIAL_SEGUNDOS, TimeUnit.SECONDS)
                .setInputData(DatosActualizacion.de(fuenteId, CausasActualizacion.AUTOMATICA, emptyList()))
                .addTag(TAG_ACTUALIZACION)
                .addTag(tagFuente(fuenteId))
                .build()

        companion object {
            /** Tag común a todo trabajo de Actualización. */
            const val TAG_ACTUALIZACION: String = "actualizacion"

            private const val PREFIJO_TAG_FUENTE = "fuente:"

            /** DC-07 punto 4: retardo inicial del backoff exponencial. */
            const val RETARDO_INICIAL_SEGUNDOS: Long = 30L

            private val ESTADOS_ABIERTOS =
                setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)

            /** DC-07 punto 3: red conectada y batería no baja, nada más. */
            private val RESTRICCIONES: Constraints =
                Constraints
                    .Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .setRequiresBatteryNotLow(true)
                    .build()

            private const val TAG = "ProgramadorActualizaciones"

            /** DC-07 punto 5: nombre único del trabajo periódico. */
            fun nombrePeriodico(fuenteId: Long): String = "actualizar-fuente-$fuenteId"

            /** DC-07 punto 6: nombre único del trabajo manual. */
            fun nombreManual(fuenteId: Long): String = "actualizar-fuente-manual-$fuenteId"

            fun tagFuente(fuenteId: Long): String = "$PREFIJO_TAG_FUENTE$fuenteId"
        }
    }
