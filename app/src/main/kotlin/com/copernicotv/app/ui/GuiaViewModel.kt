package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import com.copernicotv.app.data.AsociacionRepositorio
import com.copernicotv.app.data.AsociacionVista
import com.copernicotv.app.data.CanalGuia
import com.copernicotv.app.data.CanalGuiaFila
import com.copernicotv.app.data.CanalOpcion
import com.copernicotv.app.data.GuiaRepositorio
import com.copernicotv.app.data.ProgramaGuia
import com.copernicotv.app.data.db.dao.FuenteGuiaDao
import com.copernicotv.core.epg.EstadoAsociacion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.Instant
import java.time.temporal.ChronoUnit
import javax.inject.Inject

/** Fuente de guía expuesta a la UI sin sus columnas cifradas. */
data class FuenteGuiaOpcion(
    val id: Long,
    val nombre: String,
)

/** Estado de la pantalla de Guía (E5-T5). */
data class GuiaUiState(
    val cargando: Boolean = true,
    val fuentes: List<FuenteGuiaOpcion> = emptyList(),
    val fuenteSeleccionadaId: Long? = null,
    val canales: List<CanalGuiaFila> = emptyList(),
    val pendientes: List<AsociacionVista> = emptyList(),
    val canalesCatalogo: List<CanalOpcion> = emptyList(),
    val canalSeleccionado: CanalGuia? = null,
    val detalle: ProgramaGuia? = null,
    val error: String? = null,
)

/**
 * Pantalla de Guía (E5-T5).
 *
 * La lista principal muestra actual/siguiente por Canal (S25); abrir un Canal
 * crea la vista temporal paginada de 24 h (S26); el detalle muestra título,
 * inicio, fin y descripción en hora local (S28). Las Asociaciones `sospechosa`
 * o `ausente` se ofrecen para resolución manual (S27d/S27e).
 */
@HiltViewModel
class GuiaViewModel
    @Inject
    constructor(
        private val guia: GuiaRepositorio,
        private val asociaciones: AsociacionRepositorio,
        private val fuenteGuiaDao: FuenteGuiaDao,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(GuiaUiState())
        val estado: StateFlow<GuiaUiState> = _estado.asStateFlow()

        private val _programas: MutableStateFlow<Flow<PagingData<ProgramaGuia>>> =
            MutableStateFlow(flowOf(PagingData.empty()))
        val programas: StateFlow<Flow<PagingData<ProgramaGuia>>> = _programas.asStateFlow()

        /** Reloj inyectable para pruebas de ventana temporal. */
        internal var reloj: () -> Instant = Instant::now

        init {
            cargar()
        }

        fun recargar() {
            cargar()
        }

        fun seleccionarFuente(fuenteGuiaId: Long) {
            _estado.update { it.copy(fuenteSeleccionadaId = fuenteGuiaId, canalSeleccionado = null) }
            _programas.value = flowOf(PagingData.empty())
            cargar()
        }

        fun abrirCanal(canal: CanalGuia) {
            _estado.update { it.copy(canalSeleccionado = canal, detalle = null) }
            viewModelScope.launch {
                val ahora = reloj()
                val hasta = ahora.plus(VENTANA_HORAS, ChronoUnit.HOURS)
                val flujo =
                    guia.paginado(
                        canalId = canal.canalId,
                        desde = ahora,
                        hasta = hasta,
                        fuenteGuiaId = _estado.value.fuenteSeleccionadaId,
                    )
                _programas.value = flujo ?: flowOf(PagingData.empty())
            }
        }

        fun volverAlListado() {
            _estado.update { it.copy(canalSeleccionado = null, detalle = null) }
            _programas.value = flowOf(PagingData.empty())
        }

        fun abrirDetalle(programa: ProgramaGuia) {
            _estado.update { it.copy(detalle = programa) }
        }

        fun cerrarDetalle() {
            _estado.update { it.copy(detalle = null) }
        }

        fun confirmarAsociacion(id: Long) {
            mutarAsociacion { asociaciones.confirmar(id) }
        }

        fun editarAsociacion(
            id: Long,
            nuevoCanalId: Long,
        ) {
            mutarAsociacion { asociaciones.editar(id, nuevoCanalId) }
        }

        fun revocarAsociacion(id: Long) {
            mutarAsociacion { asociaciones.revocar(id) }
        }

        private fun mutarAsociacion(operacion: suspend () -> AsociacionVista?) {
            viewModelScope.launch {
                try {
                    operacion()
                    cargar()
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_ASOCIACION) }
                }
            }
        }

        private fun cargar() {
            viewModelScope.launch {
                _estado.update { it.copy(cargando = true, error = null) }
                try {
                    val fuentes =
                        fuenteGuiaDao.todas().map { FuenteGuiaOpcion(it.id, it.nombre) }
                    val seleccionada =
                        _estado.value.fuenteSeleccionadaId
                            ?.takeIf { id -> fuentes.any { it.id == id } }
                            ?: fuentes.firstOrNull()?.id
                    val ahora = reloj()
                    val canales =
                        if (seleccionada != null) {
                            guia.canalesConGuia(seleccionada, ahora)
                        } else {
                            emptyList()
                        }
                    val pendientes =
                        if (seleccionada != null) {
                            asociaciones.listar(seleccionada).filter {
                                it.estado == EstadoAsociacion.SOSPECHOSA ||
                                    it.estado == EstadoAsociacion.AUSENTE
                            }
                        } else {
                            emptyList()
                        }
                    val catalogo = asociaciones.canalesCatalogo()
                    _estado.update {
                        it.copy(
                            cargando = false,
                            fuentes = fuentes,
                            fuenteSeleccionadaId = seleccionada,
                            canales = canales,
                            pendientes = pendientes,
                            canalesCatalogo = catalogo,
                            error = null,
                        )
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(cargando = false, error = ERROR_CARGA) }
                }
            }
        }

        private companion object {
            const val VENTANA_HORAS = 24L
            const val ERROR_CARGA = "guia_carga_fallida"
            const val ERROR_ASOCIACION = "guia_asociacion_fallida"
        }
    }
