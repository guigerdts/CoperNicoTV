package com.copernicotv.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.copernicotv.app.data.BusquedaCatalogoRepositorio
import com.copernicotv.app.data.CanalBusqueda
import com.copernicotv.app.data.CatalogoCrudRepositorio
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.CriteriosBusqueda
import com.copernicotv.app.data.GrupoOpcion
import com.copernicotv.app.data.ListaResumen
import com.copernicotv.app.data.ResultadoAccion
import com.copernicotv.app.data.ResumenImportacion
import com.copernicotv.app.diagnostico.detalleTecnico
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado observable de la pantalla de catálogo.
 *
 * `mensaje` lleva un código tipado, no texto: la capa de UI decide cómo se
 * presenta. Así el ViewModel no fija idioma ni copy.
 */
data class CatalogoUiState(
    val canales: List<String> = emptyList(),
    val importando: Boolean = false,
    val listaId: Long? = null,
    val mensaje: String? = null,
    val mensajeAccion: String? = null,
)

/**
 * ViewModel del canario de E1-C.
 *
 * Observa el catálogo de la Lista activa desde que se construye, y no solo tras
 * importar: la Lista se resuelve en Room, así que un proceso nuevo muestra el
 * catálogo ya persistido sin depender de ningún estado en memoria. E2/E3
 * reemplazan el disparador por el flujo de alta de Fuente, pero el camino de
 * persistencia es el mismo.
 */
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogoViewModel
    @Inject
    constructor(
        private val repositorio: CatalogoRepositorio,
        private val busqueda: BusquedaCatalogoRepositorio,
        private val crud: CatalogoCrudRepositorio,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(CatalogoUiState())
        val estado: StateFlow<CatalogoUiState> = _estado.asStateFlow()
        private var observacion: Job? = null

        private val _criterios = MutableStateFlow(CriteriosBusqueda())
        val criterios: StateFlow<CriteriosBusqueda> = _criterios.asStateFlow()

        val listas: StateFlow<List<ListaResumen>> =
            crud
                .listas()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(TIEMPO_SIN_SUSCRIPTORES),
                    initialValue = emptyList(),
                )

        val grupos: StateFlow<List<GrupoOpcion>> =
            busqueda
                .gruposDeListaActiva()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(TIEMPO_SIN_SUSCRIPTORES),
                    initialValue = emptyList(),
                )

        val resultados: StateFlow<PagingData<CanalBusqueda>> =
            _criterios
                .debounce(ESPERA_TECLEO_MS)
                .distinctUntilChanged()
                .flatMapLatest { criterios -> busqueda.buscar(criterios) }
                .cachedIn(viewModelScope)
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(TIEMPO_SIN_SUSCRIPTORES),
                    initialValue = PagingData.empty<CanalBusqueda>(),
                )

        init {
            observarCatalogo()
        }

        fun actualizarTexto(texto: String) {
            _criterios.value = _criterios.value.copy(texto = texto)
        }

        fun seleccionarGrupo(grupoId: Long?) {
            _criterios.value = _criterios.value.copy(grupoId = grupoId)
        }

        fun alternarFavoritos() {
            _criterios.value = _criterios.value.copy(soloFavoritos = !_criterios.value.soloFavoritos)
        }

        fun limpiarFiltros() {
            _criterios.value = _criterios.value.copy(grupoId = null, soloFavoritos = false)
        }

        fun limpiarCriterios() {
            _criterios.value = CriteriosBusqueda()
        }

        fun alternarMostrarOcultos() {
            _criterios.value =
                _criterios.value.copy(mostrarOcultos = !_criterios.value.mostrarOcultos)
        }

        fun seleccionarLista(listaId: Long) {
            ejecutarAccion { crud.seleccionarLista(listaId) }
        }

        fun crearLista(nombre: String) {
            ejecutarAccion { crud.crearLista(nombre) }
        }

        fun renombrarLista(
            listaId: Long,
            nombre: String,
        ) {
            ejecutarAccion { crud.renombrarLista(listaId, nombre) }
        }

        fun eliminarLista(listaId: Long) {
            ejecutarAccion { crud.eliminarLista(listaId) }
        }

        fun crearCanal(
            nombre: String?,
            url: String,
        ) {
            ejecutarAccion { crud.crearCanal(nombre, url) }
        }

        fun editarNombreCanal(
            canalId: Long,
            nombre: String?,
        ) {
            ejecutarAccion { crud.editarNombreCanal(canalId, nombre) }
        }

        fun eliminarCanal(canalId: Long) {
            ejecutarAccion { crud.eliminarCanal(canalId) }
        }

        fun alternarFavorito(canalId: Long) {
            ejecutarAccion { crud.alternarFavorito(canalId) }
        }

        fun alternarOculto(canalId: Long) {
            ejecutarAccion { crud.alternarOculto(canalId) }
        }

        fun moverCanal(
            canalId: Long,
            delta: Int,
        ) {
            ejecutarAccion { crud.moverCanal(canalId, delta) }
        }

        private fun ejecutarAccion(bloque: suspend () -> ResultadoAccion) {
            viewModelScope.launch {
                when (val resultado = bloque()) {
                    ResultadoAccion.Ok ->
                        _estado.value = _estado.value.copy(mensajeAccion = null)
                    is ResultadoAccion.Error ->
                        _estado.value = _estado.value.copy(mensajeAccion = resultado.codigo)
                }
            }
        }

        fun importarFixture() {
            importar { repositorio.importarFixture() }
        }

        /**
         * Importa una Lista desde un documento SAF elegido por el usuario.
         *
         * La selección ocurre en la capa de UI con `ACTION_OPEN_DOCUMENT`, que
         * es la única que produce un permiso persistible; aquí solo se resuelve
         * el nombre de la Fuente y se delega en el repositorio.
         */
        fun importarDesdeSaf(uriTexto: String) {
            importar {
                val nombre = repositorio.nombreDeDocumento(uriTexto)
                repositorio.importarDesdeSaf(nombre, uriTexto)
            }
        }

        private fun importar(bloque: suspend () -> ResumenImportacion) {
            if (_estado.value.importando) return
            _estado.value = _estado.value.copy(importando = true, mensaje = null)
            viewModelScope.launch {
                val resumen =
                    runCatching { bloque() }
                        .getOrElse { fallo ->
                            // El código de UI no distingue causas a propósito: es
                            // texto seguro y estable. El detalle técnico va al log
                            // sin mensajes de excepción, para no filtrar la URL de
                            // la Lista ni credenciales (S57a).
                            Log.e(TAG, "Importación fallida\n${detalleTecnico(fallo)}")
                            _estado.value =
                                _estado.value.copy(
                                    importando = false,
                                    mensaje = CODIGO_FALLO,
                                )
                            return@launch
                        }
                val listaId = resumen.listaId
                if (listaId == null) {
                    _estado.value =
                        _estado.value.copy(
                            importando = false,
                            mensaje = resumen.error ?: CODIGO_SIN_LISTA,
                        )
                    return@launch
                }
                _estado.value = _estado.value.copy(importando = false, listaId = listaId, mensaje = null)
                observarCatalogo()
            }
        }

        /**
         * Observa el catálogo de la Lista activa.
         *
         * Se suscribe al construir el ViewModel, no solo después de importar: la
         * Lista se resuelve en Room, así que un proceso nuevo carga el catálogo ya
         * persistido. Antes, `listaId` solo existía en memoria y, al reiniciar, la
         * pantalla quedaba vacía aunque los datos estuvieran intactos.
         */
        private fun observarCatalogo() {
            observacion?.cancel()
            observacion =
                viewModelScope.launch {
                    repositorio.canalesDeListaActiva().collectLatest { nombres ->
                        _estado.value = _estado.value.copy(canales = nombres.mapNotNull { it })
                    }
                }
        }

        private companion object {
            const val CODIGO_FALLO = "importacion_fallida"
            const val CODIGO_SIN_LISTA = "importacion_sin_lista"
            const val ESPERA_TECLEO_MS = 150L
            const val TIEMPO_SIN_SUSCRIPTORES = 5_000L
            const val TAG = "CatalogoViewModel"
        }
    }
