package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AsociacionEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.core.identity.EstadoCanal
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Lado de la fusión al que pertenece un hecho del usuario. */
enum class LadoFusion {
    SUPERVIVIENTE,
    PERDEDORA,
}

/** Conflicto con pérdida: una fila está oculta y la otra visible. */
data class ConflictoOcultacion(
    val oculta: LadoFusion,
)

/** Conflicto con pérdida: dos identificadores EPG distintos para la misma guía. */
data class ConflictoAsociacion(
    val fuenteGuiaId: Long,
    val superviviente: List<String>,
    val perdedora: List<String>,
)

/** Conflicto con pérdida: dos posiciones distintas en la misma Colección. */
data class ConflictoColeccion(
    val coleccionId: Long,
    val posicionSuperviviente: Int,
    val posicionPerdedora: Int,
)

/** Historial que la fusión consolida sin pérdida. */
sealed interface HistorialFusion {
    data object SinFilas : HistorialFusion

    data class SoloSuperviviente(
        val fecha: Instant,
    ) : HistorialFusion

    data class SoloPerdedora(
        val fecha: Instant,
    ) : HistorialFusion

    data class Ambas(
        val fechaMaxima: Instant,
        val origen: LadoFusion,
    ) : HistorialFusion
}

/**
 * Plan de fusión calculado antes de confirmar.
 *
 * Enumera los conflictos con pérdida (que exigen decisión) y los duplicados sin
 * pérdida. El commit vuelve a calcular el plan dentro de la transacción y
 * rechaza si difiere: la confirmación nunca aplica un plan obsoleto.
 */
data class PlanFusion(
    val supervivienteId: Long,
    val perdedoraId: Long,
    val claveFinal: String,
    val favoritoDuplicado: Boolean,
    val ocultacion: ConflictoOcultacion?,
    val historial: HistorialFusion,
    val asociaciones: List<ConflictoAsociacion>,
    val colecciones: List<ConflictoColeccion>,
    val coleccionesDuplicadas: List<Long>,
    val aparienciasDuplicadas: Int,
)

/** Decisión explícita para una Ocultación en conflicto. */
enum class DecisionOcultacion {
    CONSERVAR_OCULTA,
    CONSERVAR_VISIBLE,
}

/** Decisión explícita para una Asociación EPG en conflicto. */
enum class DecisionAsociacion {
    CONSERVAR_SUPERVIVIENTE,
    CONSERVAR_PERDEDORA,
}

/** Decisión explícita para una posición de Colección en conflicto. */
enum class DecisionPosicionColeccion {
    CONSERVAR_SUPERVIVIENTE,
    CONSERVAR_PERDEDORA,
}

/** Decisiones del usuario para los conflictos que el plan presenta. */
data class DecisionesFusion(
    val ocultacion: DecisionOcultacion? = null,
    val asociaciones: Map<Long, DecisionAsociacion> = emptyMap(),
    val colecciones: Map<Long, DecisionPosicionColeccion> = emptyMap(),
)

/** Resultado de calcular un plan de fusión. */
sealed interface ResultadoPlanFusion {
    data class Listo(
        val plan: PlanFusion,
    ) : ResultadoPlanFusion

    data class Rechazado(
        val codigo: String,
    ) : ResultadoPlanFusion
}

/** Resumen de una fusión confirmada. */
data class ResumenFusion(
    val supervivienteId: Long,
    val perdedoraId: Long,
    val claveFinal: String,
    val relacionesTrasladadas: Int,
    val duplicadosConsolidados: Int,
    val historialFecha: Instant?,
)

/** Resultado de confirmar una fusión. */
sealed interface ResultadoConfirmacionFusion {
    data class Confirmada(
        val resumen: ResumenFusion,
    ) : ResultadoConfirmacionFusion

    data class Rechazada(
        val codigo: String,
    ) : ResultadoConfirmacionFusion
}

/**
 * Fusión confirmada de dos Canales ambiguos (ADR-0004:60, E4-T4/T5).
 *
 * La fusión es la única vía por la que una fila B/C vuelve a A junto con otra:
 * nunca ocurre de forma automática. `planear` calcula el plan sin escribir y
 * presenta los conflictos con pérdida; `confirmar` recalcula el plan dentro de
 * una transacción única, exige las decisiones y aplica el traslado de
 * relaciones, la consolidación de duplicados y la retirada de la fila perdedora.
 *
 * Invariantes:
 * - ambas filas pertenecen a la misma Fuente y están en estado ambiguo
 *   (no-A y sin `claveUnicidad`);
 * - la superviviente conserva su PK y sus relaciones; la perdedora se retira;
 * - la clave final es la de la superviviente (o la de la perdedora si la
 *   superviviente no tiene ninguna) y no puede colisionar con una fila ajena:
 *   si colisiona, la transacción se revierte sin cambiar estado ni relaciones;
 * - ninguna relación se fusiona sin decisión explícita cuando la pérdida es real.
 *
 * El borrado en cascada de `DetalleAusencia` al retirar la perdedora es
 * deliberado: es auditoría de Actualizaciones, no estado del usuario, y su FK
 * exige un Canal vivo.
 */
@Singleton
class FusionRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        /**
         * Etapa observable por la prueba de rollback (m2).
         *
         * En producción queda en `null`; la prueba lo usa para forzar una
         * excepción después de trasladar relaciones y comprobar que no queda nada.
         */
        internal var puntoDeFallo: ((EtapaFusion) -> Unit)? = null

        /** Calcula el plan de fusión sin escribir nada. */
        suspend fun planear(
            supervivienteId: Long,
            perdedoraId: Long,
        ): ResultadoPlanFusion =
            when (val par = validarPar(supervivienteId, perdedoraId)) {
                is ParValido.Rechazo -> ResultadoPlanFusion.Rechazado(par.codigo)
                is ParValido.Ok -> ResultadoPlanFusion.Listo(construirPlan(par))
            }

        /**
         * Aplica el plan confirmado en una transacción única.
         *
         * El plan se recalcula dentro de la transacción y debe coincidir con el
         * presentado al usuario; si cambió, no se escribe nada. Cualquier fallo
         * revierte el traslado de relaciones y la retirada de la perdedora.
         */
        suspend fun confirmar(
            plan: PlanFusion,
            decisiones: DecisionesFusion = DecisionesFusion(),
        ): ResultadoConfirmacionFusion =
            db.withTransaction {
                val rechazo = validarConfirmacion(plan, decisiones)
                if (rechazo != null) {
                    ResultadoConfirmacionFusion.Rechazada(rechazo)
                } else {
                    aplicar(plan, decisiones)
                }
            }

        private suspend fun validarPar(
            supervivienteId: Long,
            perdedoraId: Long,
        ): ParValido {
            if (supervivienteId == perdedoraId) {
                return ParValido.Rechazo(CODIGO_MISMA_FILA)
            }
            val superviviente = db.canalDao().porId(supervivienteId)
            val perdedora = db.canalDao().porId(perdedoraId)
            if (superviviente == null || perdedora == null) {
                return ParValido.Rechazo(CODIGO_CANAL_NO_EXISTE)
            }
            if (superviviente.fuenteId != perdedora.fuenteId) {
                return ParValido.Rechazo(CODIGO_FUENTES_DISTINTAS)
            }
            if (!esAmbigua(superviviente) || !esAmbigua(perdedora)) {
                return ParValido.Rechazo(CODIGO_FILA_NO_AMBIGUA)
            }
            val claveFinal = superviviente.claveCanonica ?: perdedora.claveCanonica
            if (claveFinal == null) {
                return ParValido.Rechazo(CODIGO_CLAVE_FINAL_AUSENTE)
            }
            return ParValido.Ok(superviviente, perdedora, claveFinal)
        }

        private fun esAmbigua(canal: CanalEntity): Boolean = canal.estado != EstadoCanal.A && canal.claveUnicidad == null

        private suspend fun construirPlan(par: ParValido.Ok): PlanFusion {
            val colecciones = planColecciones(par.superviviente.id, par.perdedora.id)
            return PlanFusion(
                supervivienteId = par.superviviente.id,
                perdedoraId = par.perdedora.id,
                claveFinal = par.claveFinal,
                favoritoDuplicado =
                    db.favoritoDao().porCanal(par.superviviente.id) != null &&
                        db.favoritoDao().porCanal(par.perdedora.id) != null,
                ocultacion = conflictoOcultacion(par.superviviente.id, par.perdedora.id),
                historial = planHistorial(par.superviviente.id, par.perdedora.id),
                asociaciones = conflictosAsociacion(par.superviviente.id, par.perdedora.id),
                colecciones = colecciones.conflictos,
                coleccionesDuplicadas = colecciones.duplicadas,
                aparienciasDuplicadas = aparienciasDuplicadas(par.superviviente.id, par.perdedora.id),
            )
        }

        private suspend fun conflictoOcultacion(
            supervivienteId: Long,
            perdedoraId: Long,
        ): ConflictoOcultacion? {
            val superviviente = db.ocultacionCanalDao().porCanal(supervivienteId)
            val perdedora = db.ocultacionCanalDao().porCanal(perdedoraId)
            return when {
                superviviente == null && perdedora != null -> ConflictoOcultacion(LadoFusion.PERDEDORA)
                superviviente != null && perdedora == null -> ConflictoOcultacion(LadoFusion.SUPERVIVIENTE)
                else -> null
            }
        }

        private suspend fun planHistorial(
            supervivienteId: Long,
            perdedoraId: Long,
        ): HistorialFusion {
            val superviviente = db.historialDao().porCanal(supervivienteId)
            val perdedora = db.historialDao().porCanal(perdedoraId)
            if (superviviente == null) {
                return if (perdedora == null) HistorialFusion.SinFilas else HistorialFusion.SoloPerdedora(perdedora.fecha)
            }
            if (perdedora == null) {
                return HistorialFusion.SoloSuperviviente(superviviente.fecha)
            }
            return if (superviviente.fecha >= perdedora.fecha) {
                HistorialFusion.Ambas(superviviente.fecha, LadoFusion.SUPERVIVIENTE)
            } else {
                HistorialFusion.Ambas(perdedora.fecha, LadoFusion.PERDEDORA)
            }
        }

        private suspend fun conflictosAsociacion(
            supervivienteId: Long,
            perdedoraId: Long,
        ): List<ConflictoAsociacion> {
            val superviviente = db.asociacionDao().porCanal(supervivienteId).groupBy { it.fuenteGuiaId }
            val perdedora = db.asociacionDao().porCanal(perdedoraId).groupBy { it.fuenteGuiaId }
            return perdedora.keys.intersect(superviviente.keys).sorted().map { fuenteGuiaId ->
                ConflictoAsociacion(
                    fuenteGuiaId = fuenteGuiaId,
                    superviviente = superviviente.getValue(fuenteGuiaId).map { it.channelIdTexto }.sorted(),
                    perdedora = perdedora.getValue(fuenteGuiaId).map { it.channelIdTexto }.sorted(),
                )
            }
        }

        private suspend fun planColecciones(
            supervivienteId: Long,
            perdedoraId: Long,
        ): PlanColecciones {
            val superviviente = db.coleccionCanalDao().porCanal(supervivienteId).groupBy { it.coleccionId }
            val perdedora = db.coleccionCanalDao().porCanal(perdedoraId).groupBy { it.coleccionId }
            val conflictos = mutableListOf<ConflictoColeccion>()
            val duplicadas = mutableListOf<Long>()
            for (coleccionId in perdedora.keys.intersect(superviviente.keys).sorted()) {
                val filaSuperviviente = superviviente.getValue(coleccionId).first()
                val filaPerdedora = perdedora.getValue(coleccionId).first()
                if (filaSuperviviente.posicion == filaPerdedora.posicion) {
                    duplicadas += coleccionId
                } else {
                    conflictos +=
                        ConflictoColeccion(
                            coleccionId = coleccionId,
                            posicionSuperviviente = filaSuperviviente.posicion,
                            posicionPerdedora = filaPerdedora.posicion,
                        )
                }
            }
            return PlanColecciones(conflictos = conflictos, duplicadas = duplicadas)
        }

        private suspend fun aparienciasDuplicadas(
            supervivienteId: Long,
            perdedoraId: Long,
        ): Int {
            val superviviente = db.aparienciaDao().porCanal(supervivienteId)
            return db.aparienciaDao().porCanal(perdedoraId).count { filaPerdedora ->
                superviviente.any {
                    it.listaId == filaPerdedora.listaId && it.clavePresentacion == filaPerdedora.clavePresentacion
                }
            }
        }

        private suspend fun validarConfirmacion(
            plan: PlanFusion,
            decisiones: DecisionesFusion,
        ): String? {
            val actual = planear(plan.supervivienteId, plan.perdedoraId)
            if (actual !is ResultadoPlanFusion.Listo || actual.plan != plan) {
                return CODIGO_PLAN_DESACTUALIZADO
            }
            val superviviente = db.canalDao().porId(plan.supervivienteId)
            if (superviviente == null) {
                return CODIGO_PLAN_DESACTUALIZADO
            }
            val ajena = db.canalDao().porClaveUnicidad(superviviente.fuenteId, plan.claveFinal)
            if (ajena != null && ajena.id != plan.supervivienteId) {
                return CODIGO_CLAVE_EN_CONFLICTO
            }
            val faltanDecisiones =
                (plan.ocultacion != null && decisiones.ocultacion == null) ||
                    plan.asociaciones.any { decisiones.asociaciones[it.fuenteGuiaId] == null } ||
                    plan.colecciones.any { decisiones.colecciones[it.coleccionId] == null }
            if (faltanDecisiones) {
                return CODIGO_CONFLICTO_SIN_RESOLVER
            }
            return null
        }

        private suspend fun aplicar(
            plan: PlanFusion,
            decisiones: DecisionesFusion,
        ): ResultadoConfirmacionFusion {
            val contadores = Contadores()
            aplicarFavorito(plan, contadores)
            aplicarOcultacion(plan, decisiones, contadores)
            val fechaHistorial = aplicarHistorial(plan, contadores)
            aplicarColecciones(plan, decisiones, contadores)
            aplicarAsociaciones(plan, decisiones, contadores)
            aplicarApariencias(plan, contadores)
            puntoDeFallo?.invoke(EtapaFusion.TRAS_TRASLADAR_RELACIONES)
            db.canalDao().eliminar(plan.perdedoraId)
            puntoDeFallo?.invoke(EtapaFusion.TRAS_RETIRAR_PERDEDORA)
            fijarSuperviviente(plan)
            return ResultadoConfirmacionFusion.Confirmada(
                ResumenFusion(
                    supervivienteId = plan.supervivienteId,
                    perdedoraId = plan.perdedoraId,
                    claveFinal = plan.claveFinal,
                    relacionesTrasladadas = contadores.trasladadas,
                    duplicadosConsolidados = contadores.duplicados,
                    historialFecha = fechaHistorial,
                ),
            )
        }

        private suspend fun aplicarFavorito(
            plan: PlanFusion,
            contadores: Contadores,
        ) {
            db.favoritoDao().porCanal(plan.perdedoraId) ?: return
            if (db.favoritoDao().porCanal(plan.supervivienteId) != null) {
                db.favoritoDao().eliminarPorCanal(plan.perdedoraId)
                contadores.duplicados += 1
            } else {
                db.favoritoDao().moverCanal(plan.perdedoraId, plan.supervivienteId)
                contadores.trasladadas += 1
            }
        }

        private suspend fun aplicarOcultacion(
            plan: PlanFusion,
            decisiones: DecisionesFusion,
            contadores: Contadores,
        ) {
            val ocultacionSuperviviente = db.ocultacionCanalDao().porCanal(plan.supervivienteId)
            val ocultacionPerdedora = db.ocultacionCanalDao().porCanal(plan.perdedoraId)
            if (ocultacionSuperviviente != null && ocultacionPerdedora != null) {
                db.ocultacionCanalDao().eliminarPorCanal(plan.perdedoraId)
                contadores.duplicados += 1
                return
            }
            val conflicto = plan.ocultacion ?: return
            when (checkNotNull(decisiones.ocultacion)) {
                DecisionOcultacion.CONSERVAR_OCULTA -> {
                    if (conflicto.oculta == LadoFusion.PERDEDORA) {
                        db.ocultacionCanalDao().moverCanal(plan.perdedoraId, plan.supervivienteId)
                        contadores.trasladadas += 1
                    }
                }
                DecisionOcultacion.CONSERVAR_VISIBLE -> {
                    db.ocultacionCanalDao().eliminarPorCanal(plan.supervivienteId)
                    db.ocultacionCanalDao().eliminarPorCanal(plan.perdedoraId)
                }
            }
        }

        private suspend fun aplicarHistorial(
            plan: PlanFusion,
            contadores: Contadores,
        ): Instant? {
            val superviviente = db.historialDao().porCanal(plan.supervivienteId)
            val perdedora = db.historialDao().porCanal(plan.perdedoraId)
            if (perdedora == null) {
                return superviviente?.fecha
            }
            if (superviviente == null) {
                db.historialDao().moverCanal(plan.perdedoraId, plan.supervivienteId)
                contadores.trasladadas += 1
                return perdedora.fecha
            }
            val maxima = if (superviviente.fecha >= perdedora.fecha) superviviente.fecha else perdedora.fecha
            db.historialDao().actualizarFecha(plan.supervivienteId, maxima)
            db.historialDao().eliminarPorCanal(plan.perdedoraId)
            contadores.duplicados += 1
            return maxima
        }

        private suspend fun aplicarColecciones(
            plan: PlanFusion,
            decisiones: DecisionesFusion,
            contadores: Contadores,
        ) {
            val superviviente = db.coleccionCanalDao().porCanal(plan.supervivienteId).groupBy { it.coleccionId }
            val perdedora = db.coleccionCanalDao().porCanal(plan.perdedoraId).groupBy { it.coleccionId }
            val afectadas = mutableSetOf<Long>()
            for ((coleccionId, filasPerdedora) in perdedora) {
                val filasSuperviviente = superviviente[coleccionId]
                if (filasSuperviviente == null) {
                    for (fila in filasPerdedora) {
                        db.coleccionCanalDao().actualizarCanal(fila.id, plan.supervivienteId)
                        contadores.trasladadas += 1
                    }
                } else {
                    val conflicto = plan.colecciones.firstOrNull { it.coleccionId == coleccionId }
                    if (conflicto != null) {
                        val posicion = posicionElegida(conflicto, checkNotNull(decisiones.colecciones[coleccionId]))
                        db.coleccionCanalDao().actualizarPosicion(filasSuperviviente.first().id, posicion)
                    }
                    for (fila in filasPerdedora) {
                        db.coleccionCanalDao().eliminarFila(fila.id)
                        contadores.duplicados += 1
                    }
                }
                afectadas += coleccionId
            }
            for (coleccionId in afectadas) {
                reindexar(coleccionId)
            }
        }

        private fun posicionElegida(
            conflicto: ConflictoColeccion,
            decision: DecisionPosicionColeccion,
        ): Int =
            when (decision) {
                DecisionPosicionColeccion.CONSERVAR_SUPERVIVIENTE -> conflicto.posicionSuperviviente
                DecisionPosicionColeccion.CONSERVAR_PERDEDORA -> conflicto.posicionPerdedora
            }

        private suspend fun reindexar(coleccionId: Long) {
            db.coleccionCanalDao().porColeccion(coleccionId).forEachIndexed { posicion, fila ->
                db.coleccionCanalDao().actualizarPosicion(fila.id, posicion)
            }
        }

        private suspend fun aplicarAsociaciones(
            plan: PlanFusion,
            decisiones: DecisionesFusion,
            contadores: Contadores,
        ) {
            val superviviente = db.asociacionDao().porCanal(plan.supervivienteId)
            val perdedora = db.asociacionDao().porCanal(plan.perdedoraId).groupBy { it.fuenteGuiaId }
            for ((fuenteGuiaId, filasPerdedora) in perdedora) {
                val filasSuperviviente = superviviente.filter { it.fuenteGuiaId == fuenteGuiaId }
                if (filasSuperviviente.isEmpty()) {
                    trasladarAsociaciones(filasPerdedora, plan.supervivienteId, contadores)
                } else {
                    resolverAsociacionEnConflicto(fuenteGuiaId, filasPerdedora, decisiones, plan, contadores)
                }
            }
        }

        private suspend fun trasladarAsociaciones(
            filas: List<AsociacionEntity>,
            canalId: Long,
            contadores: Contadores,
        ) {
            for (fila in filas) {
                db.asociacionDao().actualizarCanal(fila.id, canalId)
                contadores.trasladadas += 1
            }
        }

        private suspend fun resolverAsociacionEnConflicto(
            fuenteGuiaId: Long,
            filasPerdedora: List<AsociacionEntity>,
            decisiones: DecisionesFusion,
            plan: PlanFusion,
            contadores: Contadores,
        ) {
            when (checkNotNull(decisiones.asociaciones[fuenteGuiaId])) {
                DecisionAsociacion.CONSERVAR_SUPERVIVIENTE -> {
                    for (fila in filasPerdedora) {
                        db.asociacionDao().eliminar(fila.id)
                        contadores.duplicados += 1
                    }
                }
                DecisionAsociacion.CONSERVAR_PERDEDORA -> {
                    db.asociacionDao().eliminarPorCanalYFuente(plan.supervivienteId, fuenteGuiaId)
                    trasladarAsociaciones(filasPerdedora, plan.supervivienteId, contadores)
                }
            }
        }

        private suspend fun aplicarApariencias(
            plan: PlanFusion,
            contadores: Contadores,
        ) {
            val superviviente = db.aparienciaDao().porCanal(plan.supervivienteId)
            for (fila in db.aparienciaDao().porCanal(plan.perdedoraId)) {
                val duplicada =
                    superviviente.any {
                        it.listaId == fila.listaId && it.clavePresentacion == fila.clavePresentacion
                    }
                if (duplicada) {
                    db.aparienciaDao().eliminar(fila.id)
                    contadores.duplicados += 1
                } else {
                    db.aparienciaDao().actualizarCanal(fila.id, plan.supervivienteId)
                    contadores.trasladadas += 1
                }
            }
        }

        private suspend fun fijarSuperviviente(plan: PlanFusion) {
            val superviviente = checkNotNull(db.canalDao().porId(plan.supervivienteId))
            val ahora = Instant.now()
            db.canalDao().actualizar(
                superviviente.copy(
                    claveCanonica = superviviente.claveCanonica ?: plan.claveFinal,
                    claveUnicidad = plan.claveFinal,
                    claveDerivable = true,
                    estado = EstadoCanal.A,
                    decision = DECISION_FUSION,
                    fechaDecision = ahora,
                    evidencia = null,
                    actualizadoEn = ahora,
                ),
            )
        }

        /** Par de filas validado, con la clave final ya resuelta. */
        private sealed interface ParValido {
            data class Ok(
                val superviviente: CanalEntity,
                val perdedora: CanalEntity,
                val claveFinal: String,
            ) : ParValido

            data class Rechazo(
                val codigo: String,
            ) : ParValido
        }

        /** Plan de Colecciones, calculado una sola vez. */
        private data class PlanColecciones(
            val conflictos: List<ConflictoColeccion>,
            val duplicadas: List<Long>,
        )

        /** Contadores del resumen de confirmación. */
        private class Contadores {
            var trasladadas: Int = 0
            var duplicados: Int = 0
        }

        /** Etapa observable por la prueba de rollback. */
        internal enum class EtapaFusion {
            TRAS_TRASLADAR_RELACIONES,
            TRAS_RETIRAR_PERDEDORA,
        }

        companion object {
            const val CODIGO_MISMA_FILA: String = "misma_fila"
            const val CODIGO_CANAL_NO_EXISTE: String = "canal_no_existe"
            const val CODIGO_FUENTES_DISTINTAS: String = "fuentes_distintas"
            const val CODIGO_FILA_NO_AMBIGUA: String = "fila_no_ambigua"
            const val CODIGO_CLAVE_FINAL_AUSENTE: String = "clave_final_ausente"
            const val CODIGO_PLAN_DESACTUALIZADO: String = "plan_desactualizado"
            const val CODIGO_CLAVE_EN_CONFLICTO: String = "clave_en_conflicto"
            const val CODIGO_CONFLICTO_SIN_RESOLVER: String = "conflicto_sin_resolver"
            const val DECISION_FUSION: String = "fusion_confirmada"
        }
    }
