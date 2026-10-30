package com.copernicotv.app.data

import androidx.paging.Pager
import androidx.paging.PagingConfig
import androidx.paging.PagingData
import androidx.room.InvalidationTracker
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.orden.CanalOrdenable
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/** Criterios combinables de búsqueda sobre la Lista activa. */
data class CriteriosBusqueda(
    val texto: String = "",
    val grupoId: Long? = null,
    val soloFavoritos: Boolean = false,
    val mostrarOcultos: Boolean = false,
    val modo: ModoOrden = ModoOrden.MANUAL,
    val direccion: DireccionOrden = DireccionOrden.ASCENDENTE,
)

/** Opción de filtro por Grupo, limitada a la Lista activa. */
data class GrupoOpcion(
    val id: Long,
    val nombre: String,
)

/** Resultado paginado de la búsqueda; un Canal aparece una sola vez. */
data class CanalBusqueda(
    val id: Long,
    override val nombre: String?,
    val esFavorito: Boolean,
    val estaOculto: Boolean,
    val grupoId: Long? = null,
    override val grupoNombre: String? = null,
    override val ultimaReproduccionEpochMs: Long? = null,
    override val aparienciaId: Long = 0,
    override val ordenManual: Int = 0,
) : CanalOrdenable

/**
 * Búsqueda paginada del catálogo de la Lista activa.
 *
 * La consulta resuelve la Lista en SQL, igual que el catálogo existente, para
 * que un proceso nuevo no dependa de un `listaId` en memoria. Los filtros de
 * Grupo y Favorito se combinan con el texto; las Ocultaciones se excluyen porque
 * el Glosario las define como Canales o Grupos fuera del Catálogo.
 */
@Singleton
class BusquedaCatalogoRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        @Volatile
        private var fuenteOrdenada: CatalogoOrdenadoPagingSource? = null

        private val observador =
            object : InvalidationTracker.Observer(
                "Canal",
                "Apariencia",
                "Grupo",
                "Historial",
                "Favorito",
                "OcultacionCanal",
                "OcultacionGrupo",
                "Lista",
            ) {
                override fun onInvalidated(tables: Set<String>) {
                    fuenteOrdenada?.invalidate()
                }
            }

        init {
            db.invalidationTracker.addObserver(observador)
        }

        fun gruposDeListaActiva(): Flow<List<GrupoOpcion>> =
            db.grupoDao().gruposDeListaActiva().map { grupos ->
                grupos.map { grupo -> GrupoOpcion(id = grupo.id, nombre = grupo.nombre) }
            }

        fun buscar(criterios: CriteriosBusqueda): Flow<PagingData<CanalBusqueda>> {
            val texto = criterios.texto.trim()
            return Pager(
                config =
                    PagingConfig(
                        pageSize = TAMANO_PAGINA,
                        initialLoadSize = TAMANO_PAGINA,
                        enablePlaceholders = false,
                    ),
                pagingSourceFactory = {
                    if (criterios.modo == ModoOrden.MANUAL &&
                        criterios.direccion == DireccionOrden.ASCENDENTE
                    ) {
                        db.canalDao().buscarEnListaActiva(
                            texto = texto,
                            patron = patronBusqueda(texto),
                            grupoId = criterios.grupoId,
                            soloFavoritos = criterios.soloFavoritos,
                            incluirOcultos = criterios.mostrarOcultos,
                        )
                    } else {
                        CatalogoOrdenadoPagingSource(
                            db = db,
                            criterios = criterios,
                            texto = texto,
                            patron = patronBusqueda(texto),
                        ).also { fuente -> fuenteOrdenada = fuente }
                    }
                },
            ).flow
        }

        private companion object {
            const val TAMANO_PAGINA = 50

            /**
             * Patrón `LIKE` literal: `!` escapa `%` y `_` para que el texto del
             * usuario no se interprete como comodín. El texto vacío conserva
             * todos los Canales, incluidos los que no tienen nombre.
             */
            fun patronBusqueda(texto: String): String {
                if (texto.isEmpty()) return "%"
                val escapado =
                    texto
                        .replace("!", "!!")
                        .replace("%", "!%")
                        .replace("_", "!_")
                return "%$escapado%"
            }
        }
    }
