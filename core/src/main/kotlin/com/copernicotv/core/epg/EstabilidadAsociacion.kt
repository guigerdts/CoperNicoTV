package com.copernicotv.core.epg

import java.time.Instant

/** Estados de Asociación (DC-06). */
enum class EstadoAsociacion(val valor: String) {
    PROPUESTA("propuesta"),
    CONFIRMADA("confirmada"),
    SOSPECHOSA("sospechosa"),
    AUSENTE("ausente"),
    REVOCADA("revocada"),
    ;

    companion object {
        fun desde(valor: String): EstadoAsociacion? = entries.firstOrNull { it.valor == valor }
    }
}

/** Origen de una Asociación (DC-06). */
enum class OrigenAsociacion(val valor: String) {
    HEURISTICA("heuristica"),
    MANUAL("manual"),
}

/** Fila `Asociacion` leída de la base. */
data class AsociacionActual(
    val id: Long,
    val canalId: Long,
    val fuenteGuiaId: Long,
    val channelIdTexto: String,
    val estado: EstadoAsociacion,
    val origen: OrigenAsociacion,
    val huellaVersion: Int,
    val huellaValor: String?,
    val senales: SenalesFuertes,
    val confirmadaEn: Instant? = null,
)

/** Observación de un canal XMLTV en la generación que se está publicando. */
data class ObservacionCanalGuia(
    val channelIdTexto: String,
    val resultadoHuella: ResultadoHuella,
)

/**
 * Cambio a persistir sobre `Asociacion`.
 *
 * [id] `null` crea una fila nueva; [eliminar] borra la propuesta existente.
 */
data class ActualizacionAsociacion(
    val id: Long?,
    val canalId: Long,
    val fuenteGuiaId: Long,
    val channelIdTexto: String,
    val estado: EstadoAsociacion,
    val origen: OrigenAsociacion,
    val huellaVersion: Int,
    val huellaValor: String?,
    val senales: SenalesFuertes,
    val motivo: String? = null,
    val confirmadaEn: Instant? = null,
    val ultimaObservacion: Instant? = null,
    val eliminar: Boolean = false,
)

/** Canal del catálogo con el `tvg-id` que usa la heurística. */
data class CanalAsociable(
    val id: Long,
    val tvgId: String?,
)

/**
 * Transiciones de estabilidad de Asociación (DC-06).
 *
 * No crea ni confirma Asociaciones: solo actualiza filas existentes. La
 * creación de propuestas por `tvg-id` vive en [HeuristicaAsociacionEpg].
 */
object EstabilidadAsociacion {
    @Suppress("CyclomaticComplexMethod", "LoopWithTooManyJumpStatements")
    fun aplicar(
        existentes: List<AsociacionActual>,
        observados: List<ObservacionCanalGuia>,
        ahora: Instant,
    ): List<ActualizacionAsociacion> {
        val porCanal = observados.associateBy { it.channelIdTexto }
        val colisiones = colisiones(existentes, observados)
        val cambios = mutableListOf<ActualizacionAsociacion>()

        for (existente in existentes.sortedBy { it.id }) {
            if (existente.estado == EstadoAsociacion.REVOCADA) continue
            val observado = porCanal[existente.channelIdTexto]
            if (observado == null) {
                when (existente.estado) {
                    EstadoAsociacion.PROPUESTA -> cambios += eliminarPropuesta(existente)
                    EstadoAsociacion.CONFIRMADA -> cambios += transicion(existente, EstadoAsociacion.AUSENTE, motivo = "canal_ausente")
                    EstadoAsociacion.AUSENTE, EstadoAsociacion.SOSPECHOSA -> Unit
                    EstadoAsociacion.REVOCADA -> Unit
                }
                continue
            }

            val resultado = observado.resultadoHuella
            val colision = existente.channelIdTexto in colisiones
            val cambio =
                when (existente.estado) {
                    EstadoAsociacion.PROPUESTA -> propuestaObservada(existente, observado, ahora)
                    EstadoAsociacion.CONFIRMADA -> confirmadaObservada(existente, observado, ahora, colision)
                    EstadoAsociacion.AUSENTE -> ausenteObservada(existente, observado, ahora, colision)
                    EstadoAsociacion.SOSPECHOSA -> null
                    EstadoAsociacion.REVOCADA -> null
                }
            if (cambio != null) {
                cambios += cambio
            } else if (existente.estado == EstadoAsociacion.SOSPECHOSA && resultado.huella != null) {
                // La observación no reconfirma: se conserva la fila y su huella anterior.
                cambios += transicion(existente, EstadoAsociacion.SOSPECHOSA, motivo = "requiere_reconfirmacion", ultimaObservacion = ahora)
            }
        }
        return cambios
    }

    private fun propuestaObservada(
        existente: AsociacionActual,
        observado: ObservacionCanalGuia,
        ahora: Instant,
    ): ActualizacionAsociacion =
        transicion(
            existente = existente,
            estado = EstadoAsociacion.PROPUESTA,
            huellaValor = observado.resultadoHuella.huella,
            senales = observado.resultadoHuella.senales,
            motivo = null,
            ultimaObservacion = ahora,
        )

    @Suppress("ReturnCount")
    private fun confirmadaObservada(
        existente: AsociacionActual,
        observado: ObservacionCanalGuia,
        ahora: Instant,
        colision: Boolean,
    ): ActualizacionAsociacion {
        val resultado = observado.resultadoHuella
        if (colision) {
            return transicion(existente, EstadoAsociacion.SOSPECHOSA, motivo = "colision_huella", ultimaObservacion = ahora)
        }
        if (resultado.requiereClaveHmac) {
            return transicion(existente, EstadoAsociacion.SOSPECHOSA, motivo = "hmac_no_disponible", ultimaObservacion = ahora)
        }
        if (resultado.huella == null) {
            return transicion(
                existente,
                EstadoAsociacion.SOSPECHOSA,
                motivo = "sin_senales",
                ultimaObservacion = ahora,
            )
        }
        if (existente.huellaVersion == 1) {
            return transicion(
                existente,
                EstadoAsociacion.SOSPECHOSA,
                motivo = "huella_v1_requiere_reconfirmacion",
                ultimaObservacion = ahora,
            )
        }
        if (existente.huellaValor == null) {
            return transicion(existente, EstadoAsociacion.SOSPECHOSA, motivo = "sin_huella_previa", ultimaObservacion = ahora)
        }
        if (existente.huellaValor == resultado.huella) {
            return transicion(
                existente = existente,
                estado = EstadoAsociacion.CONFIRMADA,
                huellaValor = resultado.huella,
                senales = resultado.senales,
                motivo = "sin_cambios",
                ultimaObservacion = ahora,
            )
        }

        val mantieneNombres = mantieneComponente(existente.senales.nombres, resultado.senales.nombres)
        val mantieneIconos = mantieneComponente(existente.senales.iconos, resultado.senales.iconos)
        val cambioNombres = existente.senales.nombres.toSet() != resultado.senales.nombres.toSet()
        val cambioIconos = existente.senales.iconos.toSet() != resultado.senales.iconos.toSet()
        val parcial = (mantieneNombres && cambioIconos) || (mantieneIconos && cambioNombres)
        return if (parcial) {
            transicion(
                existente = existente,
                estado = EstadoAsociacion.CONFIRMADA,
                huellaValor = resultado.huella,
                senales = resultado.senales,
                motivo = "cambio_parcial_informativo",
                ultimaObservacion = ahora,
            )
        } else {
            transicion(existente, EstadoAsociacion.SOSPECHOSA, motivo = "huella_contradictoria", ultimaObservacion = ahora)
        }
    }

    private fun ausenteObservada(
        existente: AsociacionActual,
        observado: ObservacionCanalGuia,
        ahora: Instant,
        colision: Boolean,
    ): ActualizacionAsociacion {
        val resultado = observado.resultadoHuella
        if (colision) {
            return transicion(existente, EstadoAsociacion.SOSPECHOSA, motivo = "colision_huella", ultimaObservacion = ahora)
        }
        if (resultado.requiereClaveHmac || resultado.huella == null) {
            return transicion(
                existente,
                EstadoAsociacion.SOSPECHOSA,
                motivo = "reaparece_sin_senales",
                ultimaObservacion = ahora,
            )
        }
        if (existente.huellaVersion == 1 || existente.huellaValor == null) {
            return transicion(
                existente,
                EstadoAsociacion.SOSPECHOSA,
                motivo = "huella_v1_requiere_reconfirmacion",
                ultimaObservacion = ahora,
            )
        }
        val exacta = existente.huellaValor == resultado.huella
        val parcial =
            mantieneComponente(existente.senales.nombres, resultado.senales.nombres) ||
                mantieneComponente(existente.senales.iconos, resultado.senales.iconos)
        return if (exacta || parcial) {
            transicion(
                existente = existente,
                estado = EstadoAsociacion.CONFIRMADA,
                huellaValor = resultado.huella,
                senales = resultado.senales,
                motivo = if (exacta) "reaparece_compatible" else "reaparece_compatible_parcial",
                ultimaObservacion = ahora,
            )
        } else {
            transicion(existente, EstadoAsociacion.SOSPECHOSA, motivo = "reaparece_contradictoria", ultimaObservacion = ahora)
        }
    }

    private fun mantieneComponente(
        anterior: List<String>,
        actual: List<String>,
    ): Boolean = anterior.isNotEmpty() && anterior.toSet() == actual.toSet()

    private fun transicion(
        existente: AsociacionActual,
        estado: EstadoAsociacion,
        huellaValor: String? = existente.huellaValor,
        senales: SenalesFuertes = existente.senales,
        motivo: String?,
        ultimaObservacion: Instant? = null,
    ): ActualizacionAsociacion =
        ActualizacionAsociacion(
            id = existente.id,
            canalId = existente.canalId,
            fuenteGuiaId = existente.fuenteGuiaId,
            channelIdTexto = existente.channelIdTexto,
            estado = estado,
            origen = existente.origen,
            huellaVersion = existente.huellaVersion,
            huellaValor = huellaValor,
            senales = senales,
            motivo = motivo,
            confirmadaEn = existente.confirmadaEn,
            ultimaObservacion = ultimaObservacion,
        )

    private fun eliminarPropuesta(existente: AsociacionActual): ActualizacionAsociacion =
        ActualizacionAsociacion(
            id = existente.id,
            canalId = existente.canalId,
            fuenteGuiaId = existente.fuenteGuiaId,
            channelIdTexto = existente.channelIdTexto,
            estado = existente.estado,
            origen = existente.origen,
            huellaVersion = existente.huellaVersion,
            huellaValor = existente.huellaValor,
            senales = existente.senales,
            eliminar = true,
        )

    /**
     * Canales cuya huella observada o persistida colisiona con otro
     * `channel_id_texto`. Una coincidencia nunca fusiona filas.
     */
    @Suppress("LoopWithTooManyJumpStatements")
    private fun colisiones(
        existentes: List<AsociacionActual>,
        observados: List<ObservacionCanalGuia>,
    ): Set<String> {
        val porHuella = mutableMapOf<String, MutableSet<String>>()
        for (existente in existentes) {
            val huella = existente.huellaValor ?: continue
            if (existente.estado == EstadoAsociacion.REVOCADA) continue
            porHuella.getOrPut(huella) { mutableSetOf() } += existente.channelIdTexto
        }
        for (observado in observados) {
            val huella = observado.resultadoHuella.huella ?: continue
            porHuella.getOrPut(huella) { mutableSetOf() } += observado.channelIdTexto
        }
        return porHuella.values.filter { it.size > 1 }.flatten().toSet()
    }
}

/**
 * Heurística de propuesta por `tvg-id` (S27a).
 *
 * Solo propone cuando exactamente un Canal del catálogo declara el mismo
 * `tvg-id` que el `channel id` XMLTV. Una coincidencia múltiple es ambigua y no
 * crea propuesta. Nunca recrea una Asociación revocada.
 */
object HeuristicaAsociacionEpg {
    fun proponer(
        fuenteGuiaId: Long,
        canales: List<CanalAsociable>,
        observados: List<ObservacionCanalGuia>,
        existentes: List<AsociacionActual>,
        ahora: Instant,
    ): List<ActualizacionAsociacion> {
        val yaAsociados = existentes.map { it.channelIdTexto }.toSet()
        val porTvgId =
            canales
                .mapNotNull { canal -> canal.tvgId?.takeIf { it.isNotBlank() }?.let { it to canal } }
                .groupBy({ it.first }, { it.second })
        return observados
            .filter { it.channelIdTexto !in yaAsociados }
            .mapNotNull { observado ->
                val candidatos = porTvgId[observado.channelIdTexto].orEmpty()
                if (candidatos.size != 1) return@mapNotNull null
                val canal = candidatos.single()
                ActualizacionAsociacion(
                    id = null,
                    canalId = canal.id,
                    fuenteGuiaId = fuenteGuiaId,
                    channelIdTexto = observado.channelIdTexto,
                    estado = EstadoAsociacion.PROPUESTA,
                    origen = OrigenAsociacion.HEURISTICA,
                    huellaVersion = HuellaAsociacion.VERSION,
                    huellaValor = observado.resultadoHuella.huella,
                    senales = observado.resultadoHuella.senales,
                    ultimaObservacion = ahora,
                )
            }
    }
}

/** Operaciones manuales de reconfirmación, edición y revocación (DC-06). */
object OperacionesAsociacion {
    fun confirmar(
        existente: AsociacionActual,
        observado: ObservacionCanalGuia,
        ahora: Instant,
    ): ActualizacionAsociacion =
        manual(
            existente = existente,
            canalId = existente.canalId,
            observado = observado,
            ahora = ahora,
            origen = existente.origen,
            motivo = "confirmada_manual",
        )

    fun editar(
        existente: AsociacionActual,
        nuevoCanalId: Long,
        observado: ObservacionCanalGuia,
        ahora: Instant,
    ): ActualizacionAsociacion =
        manual(
            existente = existente,
            canalId = nuevoCanalId,
            observado = observado,
            ahora = ahora,
            origen = OrigenAsociacion.MANUAL,
            motivo = "editada_manual",
        )

    fun revocar(
        existente: AsociacionActual,
        ahora: Instant,
    ): ActualizacionAsociacion =
        ActualizacionAsociacion(
            id = existente.id,
            canalId = existente.canalId,
            fuenteGuiaId = existente.fuenteGuiaId,
            channelIdTexto = existente.channelIdTexto,
            estado = EstadoAsociacion.REVOCADA,
            origen = existente.origen,
            huellaVersion = existente.huellaVersion,
            huellaValor = existente.huellaValor,
            senales = existente.senales,
            motivo = "revocada_manual",
            confirmadaEn = existente.confirmadaEn,
            ultimaObservacion = ahora,
        )

    private fun manual(
        existente: AsociacionActual,
        canalId: Long,
        observado: ObservacionCanalGuia,
        ahora: Instant,
        origen: OrigenAsociacion,
        motivo: String,
    ): ActualizacionAsociacion =
        ActualizacionAsociacion(
            id = existente.id,
            canalId = canalId,
            fuenteGuiaId = existente.fuenteGuiaId,
            channelIdTexto = existente.channelIdTexto,
            estado = EstadoAsociacion.CONFIRMADA,
            origen = origen,
            huellaVersion = HuellaAsociacion.VERSION,
            huellaValor = observado.resultadoHuella.huella,
            senales = observado.resultadoHuella.senales,
            motivo = motivo,
            confirmadaEn = ahora,
            ultimaObservacion = ahora,
        )
}
