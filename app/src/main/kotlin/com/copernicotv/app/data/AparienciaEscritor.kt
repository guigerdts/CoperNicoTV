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
 *
 * Las variantes `Interno` no abren transacción: están pensadas para el camino
 * de Actualización, que ya envuelve todo el plan en una sola transacción. Abrir
 * una transacción anidada por Apariencia multiplica el coste por fila sin
 * aportar atomicidad (Room ya las anida dentro de la exterior).
 */
class AparienciaEscritor(
    private val db: AppDatabase,
) {
    suspend fun upsert(
        apariencia: AparienciaEntity,
        ahora: Instant = Instant.now(),
    ): ResultadoUpsertApariencia = db.withTransaction { upsertInterno(apariencia, ahora) }

    internal suspend fun upsertInterno(
        apariencia: AparienciaEntity,
        ahora: Instant,
    ): ResultadoUpsertApariencia {
        val dao = db.aparienciaDao()
        val pendientes =
            dao.pendientesSinResolverDeTupla(
                listaId = apariencia.listaId,
                canalId = apariencia.canalId,
                clavePresentacion = apariencia.clavePresentacion,
            )
        if (pendientes > 0) {
            return ResultadoUpsertApariencia.Rechazada(
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
            return ResultadoUpsertApariencia.Escrita(apariencia.copy(id = dao.insertar(apariencia)))
        }
        val repeticiones = existente.repeticiones + apariencia.repeticiones
        dao.actualizarRepeticiones(existente.id, repeticiones, ahora)
        return ResultadoUpsertApariencia.Escrita(
            existente.copy(repeticiones = repeticiones, actualizadoEn = ahora),
        )
    }

    /**
     * Reconciliación de una Apariencia declarada por una Actualización (E4-T1/T2).
     *
     * A diferencia de [upsert], que suma repeticiones, aquí la declaración es la
     * fuente de verdad: se actualizan `grupo`, `group_title`, `tvg_name` y
     * `repeticiones` conservando el `orden` manual de la fila existente. Una
     * tupla con migración pendiente se rechaza igual que en [upsert].
     */
    suspend fun reconciliar(
        apariencia: AparienciaEntity,
        ahora: Instant = Instant.now(),
    ): ResultadoUpsertApariencia = db.withTransaction { reconciliarInterno(apariencia, ahora) }

    internal suspend fun reconciliarInterno(
        apariencia: AparienciaEntity,
        ahora: Instant,
    ): ResultadoUpsertApariencia {
        val dao = db.aparienciaDao()
        val pendientes =
            dao.pendientesSinResolverDeTupla(
                listaId = apariencia.listaId,
                canalId = apariencia.canalId,
                clavePresentacion = apariencia.clavePresentacion,
            )
        if (pendientes > 0) {
            return ResultadoUpsertApariencia.Rechazada(
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
            return ResultadoUpsertApariencia.Escrita(apariencia.copy(id = dao.insertar(apariencia)))
        }
        dao.actualizarDatos(
            id = existente.id,
            grupoId = apariencia.grupoId,
            groupTitle = apariencia.groupTitle,
            tvgName = apariencia.tvgName,
            repeticiones = apariencia.repeticiones,
            actualizadoEn = ahora,
        )
        return ResultadoUpsertApariencia.Escrita(
            existente.copy(
                grupoId = apariencia.grupoId,
                groupTitle = apariencia.groupTitle,
                tvgName = apariencia.tvgName,
                repeticiones = apariencia.repeticiones,
                actualizadoEn = ahora,
            ),
        )
    }

    companion object {
        /** Código contractual de rechazo del upsert de una tupla con migración pendiente. */
        const val CODIGO_PENDIENTE: String = "apariencia_migracion_pendiente"
    }
}
