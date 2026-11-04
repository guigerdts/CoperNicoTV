package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import java.time.Instant

/** Resultado del upsert de una Apariencia por `(lista_id, canal_id, clave_presentacion)`. */
sealed interface ResultadoUpsertApariencia {
    /** Se insertó la fila o se actualizaron sus repeticiones. */
    data class Escrita(val apariencia: AparienciaEntity) : ResultadoUpsertApariencia

    /**
     * La tupla tiene una fila de migración sin resolver; no se creó ni modificó
     * ninguna Apariencia (DC-05:635).
     */
    data class Rechazada(
        val codigo: String,
        val listaId: Long,
        val canalId: Long,
        val clavePresentacion: String,
    ) : ResultadoUpsertApariencia
}

/**
 * Escritor de Apariencias del camino de persistencia.
 *
 * El upsert usa `(lista_id, canal_id, clave_presentacion)` como fuente de
 * verdad (DC-05:627) y rechaza la tupla con `apariencia_migracion_pendiente`
 * mientras exista una fila de migración sin resolver (DC-05:635), de modo que
 * nunca se crea una Apariencia v2 paralela a una fila pendiente.
 */
class AparienciaEscritor(
    private val db: AppDatabase,
) {
    suspend fun upsert(
        apariencia: AparienciaEntity,
        ahora: Instant = Instant.now(),
    ): ResultadoUpsertApariencia =
        db.withTransaction {
            val dao = db.aparienciaDao()
            val pendientes =
                dao.pendientesSinResolverDeTupla(
                    listaId = apariencia.listaId,
                    canalId = apariencia.canalId,
                    clavePresentacion = apariencia.clavePresentacion,
                )
            if (pendientes > 0) {
                return@withTransaction ResultadoUpsertApariencia.Rechazada(
                    codigo = CODIGO_PENDIENTE,
                    listaId = apariencia.listaId,
                    canalId = apariencia.canalId,
                    clavePresentacion = apariencia.clavePresentacion,
                )
            }
            val existente =
                dao.porTupla(
                    listaId = apariencia.listaId,
                    canalId = apariencia.canalId,
                    clavePresentacion = apariencia.clavePresentacion,
                )
            if (existente == null) {
                ResultadoUpsertApariencia.Escrita(apariencia.copy(id = dao.insertar(apariencia)))
            } else {
                val repeticiones = existente.repeticiones + apariencia.repeticiones
                dao.actualizarRepeticiones(existente.id, repeticiones, ahora)
                ResultadoUpsertApariencia.Escrita(
                    existente.copy(repeticiones = repeticiones, actualizadoEn = ahora),
                )
            }
        }

    companion object {
        /** Código contractual de rechazo del upsert de una tupla con migración pendiente. */
        const val CODIGO_PENDIENTE: String = "apariencia_migracion_pendiente"
    }
}
