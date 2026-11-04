package com.copernicotv.app.data

import androidx.paging.PagingSource
import androidx.paging.PagingState
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.orden.OrdenCatalogo
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * `PagingSource` en memoria para los modos A/B/C.
 *
 * La consulta trae todos los Canales filtrados y la ordenación se aplica una
 * sola vez por instancia. [BusquedaCatalogoRepositorio] observa la invalidación
 * de Room y llama a [invalidate] sobre la fuente vigente.
 */
internal class CatalogoOrdenadoPagingSource(
    private val db: AppDatabase,
    private val criterios: CriteriosBusqueda,
    private val texto: String,
    private val patron: String,
) : PagingSource<Int, CanalBusqueda>() {
    private val mutex = Mutex()
    private var cache: List<CanalBusqueda>? = null

    override suspend fun load(params: LoadParams<Int>): LoadResult<Int, CanalBusqueda> =
        try {
            val filas = obtener()
            val desde = (params.key ?: 0).coerceIn(0, filas.size)
            val hasta = (desde + params.loadSize).coerceAtMost(filas.size)
            LoadResult.Page(
                data = filas.subList(desde, hasta).toList(),
                prevKey = if (desde > 0) (desde - params.loadSize).coerceAtLeast(0) else null,
                nextKey = if (hasta < filas.size) hasta else null,
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            LoadResult.Error(error)
        }

    override fun getRefreshKey(state: PagingState<Int, CanalBusqueda>): Int? =
        state.anchorPosition?.let { ancla ->
            (ancla - state.config.initialLoadSize / 2).coerceAtLeast(0)
        }

    private suspend fun obtener(): List<CanalBusqueda> =
        cache ?: mutex.withLock {
            cache ?: db.canalDao()
                .buscarEnListaActivaTodos(
                    texto = texto,
                    patron = patron,
                    grupoId = criterios.grupoId,
                    soloFavoritos = criterios.soloFavoritos,
                    incluirOcultos = criterios.mostrarOcultos,
                ).let { filas ->
                    OrdenCatalogo.ordenar(filas, criterios.modo, criterios.direccion)
                }.also { cache = it }
        }
}
