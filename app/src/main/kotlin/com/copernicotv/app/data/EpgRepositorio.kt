package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AsociacionEntity
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.data.db.entities.GeneracionGuiaEntity
import com.copernicotv.app.data.db.entities.ProgramaEntity
import com.copernicotv.core.epg.ActualizacionAsociacion
import com.copernicotv.core.epg.AsociacionActual
import com.copernicotv.core.epg.CanalAsociable
import com.copernicotv.core.epg.EstabilidadAsociacion
import com.copernicotv.core.epg.EstadoAsociacion
import com.copernicotv.core.epg.HeuristicaAsociacionEpg
import com.copernicotv.core.epg.HuellaAsociacion
import com.copernicotv.core.epg.ObservacionCanalGuia
import com.copernicotv.core.epg.OrigenAsociacion
import com.copernicotv.core.epg.RetencionEpg
import com.copernicotv.core.epg.RetencionFuente
import com.copernicotv.core.epg.SenalesFuertes
import com.copernicotv.core.xmltv.CanalXmltv
import com.copernicotv.core.xmltv.ConsumidorXmltv
import com.copernicotv.core.xmltv.ErrorGlobalXmltv
import com.copernicotv.core.xmltv.ErrorXmltv
import com.copernicotv.core.xmltv.ParserXmltv
import com.copernicotv.core.xmltv.ProgramaXmltv
import com.copernicotv.core.xmltv.ResultadoParseoXmltv
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.InputStream
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject
import javax.inject.Singleton

/** Resumen de una importación de guía (E5-T1/T2/T3/T4). */
data class ResumenImportacionEpg(
    val fuenteGuiaId: Long,
    val generacionId: Long?,
    val resultado: String,
    val programas: Int = 0,
    val canales: Int = 0,
    val descartados: Int = 0,
    val recuperados: Int = 0,
    val errores: List<ErrorXmltv> = emptyList(),
    val errorGlobal: ErrorGlobalXmltv? = null,
    val detalle: String? = null,
    val causa: String,
)

/**
 * Importación y publicación de EPG XMLTV (E5-T1…E5-T4, ADR-0003).
 *
 * El parseo ocurre fuera de la transacción: un error global, un fallo de
 * lectura o una cancelación descartan la generación de staging y dejan intacta
 * la publicada. La retención se aplica sobre staging. La publicación, el puntero
 * `FuenteGuia.generacionPublicadaId` y las Asociaciones se confirman en una
 * única transacción.
 *
 * La generación anterior se elimina solo después de publicar la nueva. Si algo
 * falla, la fila `staging` y sus programas se borran en cascada.
 */
@Singleton
class EpgRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val indice: IndiceHmacRepositorio,
        private val preferenciasRetencion: PreferenciasRetencion,
        private val parser: ParserXmltv = ParserXmltv(),
    ) {
        private val io = Dispatchers.IO
        private val mutex = Mutex()

        /** Reloj inyectable para pruebas de ventana y publicación. */
        internal var reloj: () -> Instant = Instant::now

        /** Punto de fallo inyectable para probar el rollback de S29a. */
        internal var puntoDeFallo: ((EtapaPublicacionEpg) -> Unit)? = null

        suspend fun importar(
            fuenteGuiaId: Long,
            entrada: InputStream,
            causa: String = CAUSA_MANUAL,
            cancelacion: () -> Boolean = { false },
        ): ResumenImportacionEpg =
            mutex.withLock {
                withContext(io) {
                    val fuente =
                        db.fuenteGuiaDao().porId(fuenteGuiaId)
                            ?: return@withContext fallo(
                                fuenteGuiaId = fuenteGuiaId,
                                generacionId = null,
                                causa = causa,
                                codigo = ERROR_FUENTE_INEXISTENTE,
                                version = null,
                            )
                    val ahora = reloj()
                    val version = ahora.toEpochMilli().toString()
                    val versionImport = versionDe(fuenteGuiaId, version)
                    val generacionId =
                        db.generacionGuiaDao().insertar(
                            GeneracionGuiaEntity(
                                fuenteGuiaId = fuenteGuiaId,
                                version = version,
                                estado = ESTADO_STAGING,
                                creadoEn = ahora,
                            ),
                        )
                    var publicada = false

                    try {
                        val canales = linkedMapOf<String, CanalXmltv>()
                        var lote = ArrayList<ProgramaEntity>(TAMANO_LOTE)
                        val consumidor =
                            object : ConsumidorXmltv {
                                override suspend fun alCanal(canal: CanalXmltv) {
                                    canales.putIfAbsent(canal.id, canal)
                                }

                                override suspend fun alPrograma(programa: ProgramaXmltv) {
                                    lote += programa.aEntidad(generacionId, fuenteGuiaId)
                                    if (lote.size >= TAMANO_LOTE) {
                                        db.programaDao().insertarTodos(lote)
                                        lote = ArrayList(TAMANO_LOTE)
                                    }
                                }
                            }

                        val resultado = entrada.use { parser.parsear(it, consumidor, cancelacion) }
                        if (lote.isNotEmpty()) {
                            db.programaDao().insertarTodos(lote)
                            lote = ArrayList(0)
                        }

                        if (resultado.cancelado) {
                            limpiarStaging(generacionId)
                            return@withContext fallo(
                                fuenteGuiaId = fuenteGuiaId,
                                generacionId = null,
                                causa = causa,
                                codigo = ERROR_CANCELADO,
                                version = version,
                                resultado = resultado,
                            )
                        }
                        val errorGlobal = resultado.errorGlobal
                        if (errorGlobal != null) {
                            limpiarStaging(generacionId)
                            return@withContext fallo(
                                fuenteGuiaId = fuenteGuiaId,
                                generacionId = null,
                                causa = causa,
                                codigo = errorGlobal.codigo,
                                version = version,
                                resultado = resultado,
                            )
                        }
                        if (resultado.programas == 0) {
                            limpiarStaging(generacionId)
                            return@withContext fallo(
                                fuenteGuiaId = fuenteGuiaId,
                                generacionId = null,
                                causa = causa,
                                codigo = ERROR_SIN_PROGRAMAS,
                                version = version,
                                resultado = resultado,
                            )
                        }

                        aplicarRetencion(generacionId, fuente, ahora)
                        publicar(fuenteGuiaId, generacionId, canales, ahora)
                        publicada = true

                        // El estado es informativo: un fallo al registrarlo no
                        // revierte una publicación ya confirmada.
                        val estadoExito =
                            "exitosa:programas=${resultado.programas}:" +
                                "descartados=${resultado.descartados}:canales=${resultado.canales}"
                        try {
                            registrarEstado(
                                causa = causa,
                                versionImport = versionImport,
                                ahora = ahora,
                                resultado = estadoExito,
                            )
                        } catch (cancelado: CancellationException) {
                            throw cancelado
                        } catch (_: Exception) {
                            // La guía ya está publicada; el estado es best-effort.
                        }
                        ResumenImportacionEpg(
                            fuenteGuiaId = fuenteGuiaId,
                            generacionId = generacionId,
                            resultado = RESULTADO_EXITOSA,
                            programas = resultado.programas,
                            canales = resultado.canales,
                            descartados = resultado.descartados,
                            recuperados = resultado.programas + resultado.canales,
                            errores = resultado.errores,
                            errorGlobal = null,
                            detalle = resultado.detalleGlobal,
                            causa = causa,
                        )
                    } catch (cancelado: CancellationException) {
                        if (!publicada) limpiarStaging(generacionId)
                        throw cancelado
                    } catch (error: Exception) {
                        if (!publicada) limpiarStaging(generacionId)
                        fallo(
                            fuenteGuiaId = fuenteGuiaId,
                            generacionId = null,
                            causa = causa,
                            codigo = ERROR_PUBLICACION,
                            version = version,
                            detalle = error.message,
                        )
                    }
                }
            }

        private suspend fun aplicarRetencion(
            generacionId: Long,
            fuente: FuenteGuiaEntity,
            ahora: Instant,
        ) {
            val limites =
                RetencionEpg.resolver(
                    preferenciasRetencion.actuales(),
                    RetencionFuente(
                        usarGlobal = fuente.usarRetencionGlobal,
                        ventanaPasadoDias = fuente.retencionVentanaPasadoDias,
                        ventanaFuturoDias = fuente.retencionVentanaFuturoDias,
                        maxProgramasPorCanal = fuente.retencionMaxProgramas,
                        presupuestoBytes = fuente.retencionPresupuestoBytes,
                    ),
                )
            val desde = ahora.minus(limites.ventanaPasadoDias, ChronoUnit.DAYS)
            val hasta = ahora.plus(limites.ventanaFuturoDias, ChronoUnit.DAYS)
            val dao = db.programaDao()
            dao.eliminarFueraDeVentana(generacionId, desde, hasta)
            for (canal in dao.canales(generacionId)) {
                dao.recortarCanal(generacionId, canal, limites.maxProgramasPorCanal)
            }

            val tamanos = dao.tamanosOrdenados(generacionId)
            var total = tamanos.sumOf { it.tamano }
            if (total <= limites.presupuestoBytes) return
            val aEliminar = mutableListOf<Long>()
            var posicion = 0
            while (total > limites.presupuestoBytes && posicion < tamanos.size) {
                total -= tamanos[posicion].tamano
                aEliminar += tamanos[posicion].id
                posicion++
            }
            for (bloque in aEliminar.chunked(TAMANO_BORRADO)) {
                dao.eliminarPorIds(bloque)
            }
        }

        private suspend fun publicar(
            fuenteGuiaId: Long,
            generacionId: Long,
            canales: Map<String, CanalXmltv>,
            ahora: Instant,
        ) {
            // Clave del índice en su generación activa, sin regenerarla
            // (DC-04 §5): si el índice no está operativo o la clave activa
            // falta, las huellas se calculan sin clave (ruta grácil de E3,
            // con `requiereClaveHmac`); nunca se crea una clave sustituta.
            val claveHuella = (indice.selloHmac() as? SelloIndiceHmac.Disponible)?.clave
            val observados =
                canales.values.map { canal ->
                    ObservacionCanalGuia(
                        channelIdTexto = canal.id,
                        resultadoHuella = HuellaAsociacion.calcular(canal.nombres, canal.iconos, claveHuella),
                    )
                }
            db.withTransaction {
                reconciliarAsociaciones(fuenteGuiaId, observados, ahora)
                puntoDeFallo?.invoke(EtapaPublicacionEpg.ANTES_DE_PUBLICAR)
                db.generacionGuiaDao().actualizarEstado(generacionId, ESTADO_PUBLICADA, ahora)
                db.fuenteGuiaDao().fijarPublicada(fuenteGuiaId, generacionId, ahora)
                db.generacionGuiaDao().eliminarOtrasDeFuente(fuenteGuiaId, generacionId)
            }
        }

        private suspend fun reconciliarAsociaciones(
            fuenteGuiaId: Long,
            observados: List<ObservacionCanalGuia>,
            ahora: Instant,
        ) {
            val dao = db.asociacionDao()
            val existentes = dao.porFuente(fuenteGuiaId).map { it.aModelo() }
            val actualizaciones = EstabilidadAsociacion.aplicar(existentes, observados, ahora)
            val propuestas =
                HeuristicaAsociacionEpg.proponer(
                    fuenteGuiaId = fuenteGuiaId,
                    canales = db.canalDao().todosConTvgId().map { CanalAsociable(it.id, it.tvgId) },
                    observados = observados,
                    existentes = existentes,
                    ahora = ahora,
                )

            for (actualizacion in actualizaciones + propuestas) {
                if (actualizacion.eliminar) {
                    actualizacion.id?.let { dao.eliminar(it) }
                    continue
                }
                val fila = actualizacion.aEntidad(ahora)
                if (actualizacion.id == null) {
                    dao.insertar(fila)
                } else {
                    dao.actualizar(fila)
                }
            }
        }

        private suspend fun registrarEstado(
            causa: String,
            versionImport: String,
            ahora: Instant,
            resultado: String,
        ) {
            db.estadoActualizacionDao().insertar(
                EstadoActualizacionEntity(
                    fuenteId = null,
                    listaId = null,
                    causa = causa,
                    versionImport = versionImport,
                    inicio = ahora,
                    fin = ahora,
                    resultado = resultado,
                ),
            )
        }

        private suspend fun limpiarStaging(generacionId: Long) {
            db.generacionGuiaDao().eliminar(generacionId)
        }

        private suspend fun fallo(
            fuenteGuiaId: Long,
            generacionId: Long?,
            causa: String,
            codigo: String,
            version: String?,
            detalle: String? = null,
            resultado: ResultadoParseoXmltv? = null,
        ): ResumenImportacionEpg {
            val ahora = reloj()
            if (version != null) {
                registrarEstado(
                    causa = causa,
                    versionImport = versionDe(fuenteGuiaId, version),
                    ahora = ahora,
                    resultado = "fallida:$codigo",
                )
            }
            return ResumenImportacionEpg(
                fuenteGuiaId = fuenteGuiaId,
                generacionId = generacionId,
                resultado = RESULTADO_FALLIDA,
                programas = resultado?.programas ?: 0,
                canales = resultado?.canales ?: 0,
                descartados = resultado?.descartados ?: 0,
                recuperados = (resultado?.programas ?: 0) + (resultado?.canales ?: 0),
                errores = resultado?.errores ?: emptyList(),
                errorGlobal = resultado?.errorGlobal,
                detalle = detalle ?: resultado?.detalleGlobal ?: codigo,
                causa = causa,
            )
        }

        private fun versionDe(
            fuenteGuiaId: Long,
            version: String,
        ): String = "xmltv:$fuenteGuiaId:$version"

        private fun ProgramaXmltv.aEntidad(
            generacionId: Long,
            fuenteGuiaId: Long,
        ): ProgramaEntity =
            ProgramaEntity(
                generacionId = generacionId,
                fuenteGuiaId = fuenteGuiaId,
                channelIdTexto = channelIdTexto,
                titulo = titulo,
                inicio = inicio,
                fin = fin,
                descripcion = descripcion,
            )

        private fun AsociacionEntity.aModelo(): AsociacionActual =
            AsociacionActual(
                id = id,
                canalId = canalId,
                fuenteGuiaId = fuenteGuiaId,
                channelIdTexto = channelIdTexto,
                estado = EstadoAsociacion.desde(estado) ?: EstadoAsociacion.SOSPECHOSA,
                origen = OrigenAsociacion.entries.firstOrNull { it.valor == origen } ?: OrigenAsociacion.MANUAL,
                huellaVersion = huellaVersion,
                huellaValor = huellaValor,
                senales = SenalesFuertes.desdeJson(senalesFuertes),
                confirmadaEn = confirmadaEn,
            )

        private fun ActualizacionAsociacion.aEntidad(ahora: Instant): AsociacionEntity =
            AsociacionEntity(
                id = id ?: 0,
                canalId = canalId,
                fuenteGuiaId = fuenteGuiaId,
                channelIdTexto = channelIdTexto,
                estado = estado.valor,
                origen = origen.valor,
                huellaVersion = huellaVersion,
                huellaValor = huellaValor,
                senalesFuertes = senales.aJson(),
                ultimaObservacion = ultimaObservacion ?: ahora,
                confirmadaEn = confirmadaEn,
                actualizadaEn = ahora,
            )

        companion object {
            const val ESTADO_STAGING = "staging"
            const val ESTADO_PUBLICADA = "publicada"
            const val RESULTADO_EXITOSA = "exitosa"
            const val RESULTADO_FALLIDA = "fallida"
            const val CAUSA_MANUAL = "guia_manual"
            const val CAUSA_AUTOMATICA = "guia_automatica"
            const val ERROR_FUENTE_INEXISTENTE = "fuente_guia_inexistente"
            const val ERROR_SIN_PROGRAMAS = "sin_programas"
            const val ERROR_CANCELADO = "cancelado"
            const val ERROR_PUBLICACION = "publicacion_fallida"
            const val TAMANO_LOTE = 500
            const val TAMANO_BORRADO = 500
        }
    }

/** Etapas de publicación con punto de fallo inyectable en pruebas. */
enum class EtapaPublicacionEpg {
    ANTES_DE_PUBLICAR,
}
