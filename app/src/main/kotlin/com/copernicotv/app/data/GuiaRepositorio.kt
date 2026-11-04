package com.copernicotv.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.paging.map
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.CanalConGuia
import com.copernicotv.app.data.db.entities.ProgramaEntity
import com.copernicotv.core.epg.EstadoAsociacion
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Programa listo para la UI de guía. */
data class ProgramaGuia(
    val id: Long,
    val canalId: Long,
    val channelIdTexto: String,
    val titulo: String,
    val inicio: Instant,
    val fin: Instant,
    val descripcion: String?,
)

/** Canal con una Asociación confirmada y generación publicada. */
data class CanalGuia(
    val canalId: Long,
    val nombre: String?,
    val channelIdTexto: String,
    val fuenteGuiaId: Long,
    val generacionId: Long,
)

/** Actual y siguiente de un Canal; ambos pueden ser `null`. */
data class GuiaActualSiguiente(
    val actual: ProgramaGuia?,
    val siguiente: ProgramaGuia?,
)

/** Canal con guía y su programación inmediata (S25). */
data class CanalGuiaFila(
    val canal: CanalGuia,
    val actual: ProgramaGuia?,
    val siguiente: ProgramaGuia?,
)

/**
 * Consultas de lectura de la guía publicada (E5-T5).
 *
 * Solo se consultan Asociaciones `confirmada`: una `sospechosa`, `ausente` o
 * `revocada` no muestra programas hasta que el usuario resuelva la Asociación
 * (DC-06). Un Canal sin programa actual ni siguiente no se lista (S25).
 */
@Singleton
class GuiaRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        suspend fun canalesConGuia(
            fuenteGuiaId: Long? = null,
            ahora: Instant = Instant.now(),
        ): List<CanalGuiaFila> {
            val candidatos =
                db.asociacionDao().conCanalYGuia(fuenteGuiaId)
                    .filter { it.estado == EstadoAsociacion.CONFIRMADA.valor }
            return candidatos.mapNotNull { asociacion ->
                val dao = db.programaDao()
                val actual = dao.actual(asociacion.generacionId, asociacion.channelIdTexto, ahora)
                val siguiente = dao.siguiente(asociacion.generacionId, asociacion.channelIdTexto, ahora)
                if (actual == null && siguiente == null) {
                    null
                } else {
                    CanalGuiaFila(
                        canal = asociacion.aModelo(),
                        actual = actual?.aModelo(asociacion.canalId),
                        siguiente = siguiente?.aModelo(asociacion.canalId),
                    )
                }
            }
        }

        suspend fun actualYSiguiente(
            canalId: Long,
            fuenteGuiaId: Long? = null,
            ahora: Instant = Instant.now(),
        ): GuiaActualSiguiente? {
            val asociacion = elegirAsociacion(canalId, fuenteGuiaId) ?: return null
            val dao = db.programaDao()
            val actual = dao.actual(asociacion.generacionId, asociacion.channelIdTexto, ahora)
            val siguiente = dao.siguiente(asociacion.generacionId, asociacion.channelIdTexto, ahora)
            return GuiaActualSiguiente(
                actual = actual?.aModelo(canalId),
                siguiente = siguiente?.aModelo(canalId),
            )
        }

        suspend fun programas(
            canalId: Long,
            desde: Instant,
            hasta: Instant,
            fuenteGuiaId: Long? = null,
        ): List<ProgramaGuia> {
            val asociacion = elegirAsociacion(canalId, fuenteGuiaId) ?: return emptyList()
            return db
                .programaDao()
                .enIntervalo(asociacion.generacionId, asociacion.channelIdTexto, desde, hasta)
                .map { it.aModelo(canalId) }
        }

        /**
         * Vista temporal paginada de un Canal (S26).
         *
         * Devuelve `null` si el Canal no tiene Asociación confirmada con guía
         * publicada; la UI muestra el estado vacío sin lanzar.
         */
        suspend fun paginado(
            canalId: Long,
            desde: Instant,
            hasta: Instant,
            fuenteGuiaId: Long? = null,
        ): Flow<PagingData<ProgramaGuia>>? {
            val asociacion = elegirAsociacion(canalId, fuenteGuiaId) ?: return null
            return Pager(
                config =
                    PagingConfig(
                        pageSize = TAMANO_PAGINA,
                        initialLoadSize = TAMANO_PAGINA,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    db.programaDao().paginado(
                        generacionId = asociacion.generacionId,
                        channelId = asociacion.channelIdTexto,
                        desde = desde,
                        hasta = hasta,
                    )
                },
            ).flow.map { pagina -> pagina.map { it.aModelo(canalId) } }
        }

        /** Asociaciones que necesitan resolución manual (S27d/S27e). */
        suspend fun asociacionesParaResolver(fuenteGuiaId: Long? = null): List<CanalConGuia> =
            db.asociacionDao().conCanalYGuia(fuenteGuiaId).filter {
                it.estado == EstadoAsociacion.SOSPECHOSA.valor ||
                    it.estado == EstadoAsociacion.AUSENTE.valor
            }

        private suspend fun elegirAsociacion(
            canalId: Long,
            fuenteGuiaId: Long?,
        ): CanalConGuia? =
            db.asociacionDao()
                .conCanalYGuia(fuenteGuiaId)
                .filter { it.canalId == canalId && it.estado == EstadoAsociacion.CONFIRMADA.valor }
                .minByOrNull { it.fuenteGuiaId }

        private fun CanalConGuia.aModelo(): CanalGuia =
            CanalGuia(
                canalId = canalId,
                nombre = nombre,
                channelIdTexto = channelIdTexto,
                fuenteGuiaId = fuenteGuiaId,
                generacionId = generacionId,
            )

        private fun ProgramaEntity.aModelo(canalId: Long): ProgramaGuia =
            ProgramaGuia(
                id = id,
                canalId = canalId,
                channelIdTexto = channelIdTexto,
                titulo = titulo,
                inicio = inicio,
                fin = fin,
                descripcion = descripcion,
            )

        private companion object {
            const val TAMANO_PAGINA = 50
        }
    }
