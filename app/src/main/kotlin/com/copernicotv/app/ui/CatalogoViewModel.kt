package com.copernicotv.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.paging.PagingData
import androidx.paging.cachedIn
import com.copernicotv.app.data.AvanceVisible
import com.copernicotv.app.data.BusquedaCatalogoRepositorio
import com.copernicotv.app.data.CanalBusqueda
import com.copernicotv.app.data.CatalogoCrudRepositorio
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.ColeccionCanalResumen
import com.copernicotv.app.data.ColeccionRepositorio
import com.copernicotv.app.data.ColeccionResumen
import com.copernicotv.app.data.CriteriosBusqueda
import com.copernicotv.app.data.DiagnosticoImportacion
import com.copernicotv.app.data.FaseImportacion
import com.copernicotv.app.data.GrupoOpcion
import com.copernicotv.app.data.HistorialRepositorio
import com.copernicotv.app.data.HistorialResumen
import com.copernicotv.app.data.ListaResumen
import com.copernicotv.app.data.PerfilRepositorio
import com.copernicotv.app.data.PerfilResumen
import com.copernicotv.app.data.PreferenciasOrden
import com.copernicotv.app.data.ResultadoAccion
import com.copernicotv.app.data.ResumenActualizacion
import com.copernicotv.app.data.ResumenImportacion
import com.copernicotv.app.data.actualizacion.EstadoSolicitud
import com.copernicotv.app.data.actualizacion.ProgramadorActualizaciones
import com.copernicotv.app.diagnostico.detalleTecnico
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import com.copernicotv.core.orden.PreferenciaOrden
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.FlowPreview
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.debounce
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado visible del diálogo de importación por URL (E7-T7).
 *
 * `fase` lleva el código de la fase en curso; `avance` es la línea técnica de
 * progreso (entradas, canales, bytes y tiempo) que se publica cada 500 ms;
 * `canales` aparece solo al completar la persistencia; `error` con su causa
 * solo cuando falló. Mientras hay importación activa el diálogo no se cierra
 * y los campos no se pierden.
 */
data class ImportacionUrlUi(
    val fase: String? = null,
    val avance: String? = null,
    val canales: Int? = null,
    val error: String? = null,
    val diagnostico: DiagnosticoImportacion? = null,
)

/**
 * Estado observable de la pantalla de catálogo.
 *
 * `mensaje` lleva un código tipado, no texto: la capa de UI decide cómo se
 * presenta. Así el ViewModel no fija idioma ni copy.
 */
data class CatalogoUiState(
    val canales: List<String> = emptyList(),
    val importando: Boolean = false,
    val actualizando: Boolean = false,
    val listaId: Long? = null,
    val mensaje: String? = null,
    val mensajeAccion: String? = null,
    val actualizacion: ResumenActualizacion? = null,
    val importacionUrl: ImportacionUrlUi? = null,
    /** Informe copiable de la última importación; sobrevive al diálogo (S49). */
    val ultimoInformeImportacion: String? = null,
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
@Suppress("TooManyFunctions")
@OptIn(FlowPreview::class, ExperimentalCoroutinesApi::class)
@HiltViewModel
class CatalogoViewModel
    @Inject
    constructor(
        private val repositorio: CatalogoRepositorio,
        private val busqueda: BusquedaCatalogoRepositorio,
        private val crud: CatalogoCrudRepositorio,
        private val preferencias: PreferenciasOrden,
        private val coleccionesRepositorio: ColeccionRepositorio,
        private val perfilesRepositorio: PerfilRepositorio,
        private val historialRepositorio: HistorialRepositorio,
        private val programador: ProgramadorActualizaciones,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(CatalogoUiState())
        val estado: StateFlow<CatalogoUiState> = _estado.asStateFlow()
        private var observacion: Job? = null

        /** Trabajo en curso de la importación por URL; lo cancela el usuario. */
        private var trabajoImportacionUrl: Job? = null

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

        val colecciones: StateFlow<List<ColeccionResumen>> =
            coleccionesRepositorio
                .colecciones()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(TIEMPO_SIN_SUSCRIPTORES),
                    initialValue = emptyList(),
                )

        private val seleccionColeccion = MutableStateFlow<Long?>(null)
        val coleccionSeleccionadaId: StateFlow<Long?> = seleccionColeccion.asStateFlow()

        val coleccionCanales: StateFlow<List<ColeccionCanalResumen>> =
            seleccionColeccion
                .flatMapLatest { coleccionId ->
                    if (coleccionId == null) {
                        flowOf(emptyList())
                    } else {
                        coleccionesRepositorio.canalesDe(coleccionId)
                    }
                }.stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(TIEMPO_SIN_SUSCRIPTORES),
                    initialValue = emptyList(),
                )

        /**
         * Acceso rápido de S11c: últimos Canales reproducidos en el orden
         * persistido `fecha DESC, canalId DESC`, con nombre y marcas.
         */
        val recientes: StateFlow<List<HistorialResumen>> =
            historialRepositorio
                .observarUltimos()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(TIEMPO_SIN_SUSCRIPTORES),
                    initialValue = emptyList(),
                )

        val perfiles: StateFlow<List<PerfilResumen>> =
            perfilesRepositorio
                .perfiles()
                .stateIn(
                    scope = viewModelScope,
                    started = SharingStarted.WhileSubscribed(TIEMPO_SIN_SUSCRIPTORES),
                    initialValue = emptyList(),
                )

        private val seleccionPerfil = MutableStateFlow<Long?>(null)
        val perfilSeleccionadoId: StateFlow<Long?> = seleccionPerfil.asStateFlow()

        val listasDePerfil: StateFlow<List<Long>> =
            seleccionPerfil
                .flatMapLatest { perfilId ->
                    if (perfilId == null) {
                        flowOf(emptyList())
                    } else {
                        perfilesRepositorio.listasDePerfil(perfilId)
                    }
                }.stateIn(
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
            observarPreferencias()
            sincronizarPreferencias()
            observarActualizacion()
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
            _criterios.value =
                _criterios.value.copy(
                    texto = "",
                    grupoId = null,
                    soloFavoritos = false,
                    mostrarOcultos = false,
                )
        }

        /** Cambia el modo A/B/C y lo persiste para la Lista activa. */
        fun seleccionarModo(modo: ModoOrden) {
            val listaId = listas.value.firstOrNull { it.activa }?.id ?: return
            val direccion = _criterios.value.direccion
            _criterios.value = _criterios.value.copy(modo = modo)
            viewModelScope.launch {
                preferencias.guardar(listaId, PreferenciaOrden(modo, direccion))
            }
        }

        /** Invierte la dirección y la persiste para la Lista activa. */
        fun alternarDireccion() {
            val listaId = listas.value.firstOrNull { it.activa }?.id ?: return
            val modo = _criterios.value.modo
            val nueva =
                if (_criterios.value.direccion == DireccionOrden.ASCENDENTE) {
                    DireccionOrden.DESCENDENTE
                } else {
                    DireccionOrden.ASCENDENTE
                }
            _criterios.value = _criterios.value.copy(direccion = nueva)
            viewModelScope.launch {
                preferencias.guardar(listaId, PreferenciaOrden(modo, nueva))
            }
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

        /** S47a: borra todo el Historial; los Favoritos se conservan. */
        fun borrarHistorial() {
            ejecutarAccion { crud.borrarHistorial() }
        }

        /** S47b: borra todos los Favoritos; el Historial se conserva. */
        fun borrarFavoritos() {
            ejecutarAccion { crud.borrarFavoritos() }
        }

        fun moverCanal(
            canalId: Long,
            delta: Int,
        ) {
            ejecutarAccion { crud.moverCanal(canalId, delta) }
        }

        fun seleccionarColeccion(coleccionId: Long?) {
            seleccionColeccion.value = coleccionId
        }

        fun crearColeccion(nombre: String) {
            ejecutarAccion { coleccionesRepositorio.crear(nombre) }
        }

        fun renombrarColeccion(
            coleccionId: Long,
            nombre: String,
        ) {
            ejecutarAccion { coleccionesRepositorio.renombrar(coleccionId, nombre) }
        }

        fun eliminarColeccion(coleccionId: Long) {
            ejecutarAccion {
                val resultado = coleccionesRepositorio.eliminar(coleccionId)
                if (resultado is ResultadoAccion.Ok && seleccionColeccion.value == coleccionId) {
                    seleccionColeccion.value = null
                }
                resultado
            }
        }

        fun agregarCanalAColeccion(
            coleccionId: Long,
            canalId: Long,
        ) {
            ejecutarAccion { coleccionesRepositorio.agregarCanal(coleccionId, canalId) }
        }

        fun quitarCanalDeColeccion(filaId: Long) {
            ejecutarAccion { coleccionesRepositorio.quitarFila(filaId) }
        }

        fun moverCanalEnColeccion(
            filaId: Long,
            delta: Int,
        ) {
            ejecutarAccion { coleccionesRepositorio.moverFila(filaId, delta) }
        }

        fun seleccionarPerfil(perfilId: Long?) {
            seleccionPerfil.value = perfilId
        }

        fun crearPerfil(nombre: String) {
            ejecutarAccion { perfilesRepositorio.crearPerfil(nombre) }
        }

        fun renombrarPerfil(
            perfilId: Long,
            nombre: String,
        ) {
            ejecutarAccion { perfilesRepositorio.renombrarPerfil(perfilId, nombre) }
        }

        fun eliminarPerfil(perfilId: Long) {
            ejecutarAccion {
                val resultado = perfilesRepositorio.eliminarPerfil(perfilId)
                if (resultado is ResultadoAccion.Ok && seleccionPerfil.value == perfilId) {
                    seleccionPerfil.value = null
                }
                resultado
            }
        }

        fun asociarListaAPerfil(
            perfilId: Long,
            listaId: Long,
        ) {
            ejecutarAccion { perfilesRepositorio.asociarLista(perfilId, listaId) }
        }

        fun desasociarListaDePerfil(
            perfilId: Long,
            listaId: Long,
        ) {
            ejecutarAccion { perfilesRepositorio.desasociarLista(perfilId, listaId) }
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

        /**
         * Da de alta una Fuente y su Lista desde una URL HTTP/HTTPS.
         *
         * El nombre lo escribe el usuario: no se deriva de la URL para no
         * persistir en claro el host ni credenciales en la columna `Fuente.nombre`
         * (S57a). La URL se descarga y valida en el repositorio antes de escribir.
         *
         * El diálogo no se cierra: la fase en curso, el error con su causa y el
         * número de canales se exponen en [CatalogoUiState.importacionUrl].
         * En fallo el estado conserva la importación para reintentar sin
         * volver a escribir nombre ni URL (E7-T7).
         */
        fun importarDesdeUrl(
            nombreFuente: String,
            url: String,
        ) {
            val nombre = nombreFuente.trim()
            val ubicacion = url.trim()
            if (nombre.isEmpty() || ubicacion.isEmpty()) return
            if (_estado.value.importando) return
            _estado.value =
                _estado.value.copy(
                    importando = true,
                    mensaje = null,
                    importacionUrl = ImportacionUrlUi(fase = FaseImportacion.CONECTANDO.codigo),
                )
            trabajoImportacionUrl =
                viewModelScope.launch {
                    val resumen =
                        try {
                            repositorio.importarDesdeUrl(
                                nombre,
                                ubicacion,
                                alFase = { fase ->
                                    _estado.update { actual ->
                                        actual.copy(
                                            importacionUrl = actual.importacionUrl?.copy(fase = fase.codigo),
                                        )
                                    }
                                },
                                alAvance = { visible ->
                                    val linea = lineaAvance(visible)
                                    _estado.update { actual ->
                                        actual.copy(
                                            importacionUrl = actual.importacionUrl?.copy(avance = linea),
                                        )
                                    }
                                },
                            )
                        } catch (cancelacion: CancellationException) {
                            throw cancelacion
                        } catch (fallo: Exception) {
                            // El código de UI no distingue causas a propósito: es
                            // texto seguro y estable. El detalle técnico va al log
                            // sin mensajes de excepción, para no filtrar la URL de
                            // la Lista ni credenciales (S57a).
                            Log.e(TAG, "Importación fallida\n${detalleTecnico(fallo)}")
                            _estado.value =
                                _estado.value.copy(
                                    importando = false,
                                    mensaje = CODIGO_FALLO,
                                    importacionUrl = ImportacionUrlUi(error = CODIGO_FALLO),
                                )
                            return@launch
                        }
                    val listaId = resumen.listaId
                    if (listaId == null) {
                        _estado.value =
                            _estado.value.copy(
                                importando = false,
                                mensaje = resumen.error ?: CODIGO_SIN_LISTA,
                                importacionUrl =
                                    ImportacionUrlUi(
                                        error = resumen.error ?: CODIGO_SIN_LISTA,
                                        diagnostico = resumen.diagnostico,
                                    ),
                                // Ronda 7 (S49): informe sobreviviente también en fallo.
                                ultimoInformeImportacion =
                                    resumen.diagnostico?.aInforme(resumen.error ?: CODIGO_SIN_LISTA),
                            )
                        return@launch
                    }
                    _estado.value =
                        _estado.value.copy(
                            importando = false,
                            listaId = listaId,
                            mensaje = null,
                            importacionUrl =
                                ImportacionUrlUi(
                                    canales = resumen.canales,
                                    // Ronda 7 (S48): el diagnóstico de éxito llega a la
                                    // UI; el botón «Copiar informe» ya no depende del
                                    // camino de fallo.
                                    diagnostico = resumen.diagnostico,
                                ),
                            // Ronda 7 (S49): el informe sobrevive al cierre del
                            // diálogo y a la navegación; muere solo con el proceso.
                            ultimoInformeImportacion = resumen.diagnostico?.aInforme(INFORME_EXITO),
                        )
                    observarCatalogo()
                }
        }

        /**
         * Cancela la importación por URL en curso (E7-T7, S22).
         *
         * Cancelar el `Job` detiene el parseo y el procesamiento en el
         * siguiente punto de avance cooperativo: la transacción Room no llegó
         * a abrirse o revierte al suspenderse, así que el catálogo previo
         * queda intacto. También deja el diálogo como recién abierto.
         */
        fun cancelarImportacionUrl() {
            trabajoImportacionUrl?.cancel()
            trabajoImportacionUrl = null
            _estado.value =
                _estado.value.copy(importando = false, mensaje = null, importacionUrl = null)
        }

        /** Línea técnica de avance para la UI, sin datos de la lista. */
        private fun lineaAvance(avance: AvanceVisible): String =
            "entradas=${avance.entradas} agrupadas=${avance.agrupadas} canales=${avance.canales} " +
                "preparando=${avance.preparando} bytes=${avance.bytes} t=${avance.msTranscurrido / 1000}s"

        /**
         * Deja el diálogo de importación por URL como recién abierto.
         *
         * Se llama al reabrir el diálogo: borra el resultado anterior sin
         * tocar una importación en curso.
         */
        fun reiniciarImportacionUrl() {
            if (_estado.value.importando) return
            _estado.update { actual ->
                if (actual.importacionUrl == null) actual else actual.copy(importacionUrl = null)
            }
        }

        private fun importar(bloque: suspend () -> ResumenImportacion) {
            if (_estado.value.importando) return
            _estado.value = _estado.value.copy(importando = true, mensaje = null)
            viewModelScope.launch {
                val resumen =
                    try {
                        bloque()
                    } catch (cancelacion: CancellationException) {
                        throw cancelacion
                    } catch (fallo: Exception) {
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
         * Encola la Actualización manual de la Lista activa (E4-T6, DC-07 punto 6).
         *
         * El trabajo corre en WorkManager y el progreso y el resultado llegan por
         * [observarActualizacion]; aquí solo se valida el estado y se encola. Un
         * fallo al encolar no se propaga a la UI: se registra el detalle técnico y
         * se devuelve un resumen con `error` para que la pantalla muestre un
         * código estable.
         */
        fun actualizarListaActiva() {
            if (_estado.value.actualizando || _estado.value.importando) return
            val listaId = listas.value.firstOrNull { it.activa }?.id ?: return
            _estado.value =
                _estado.value.copy(actualizacion = null, mensaje = null)
            viewModelScope.launch {
                try {
                    programador.solicitarLista(listaId)
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (fallo: Exception) {
                    Log.e(TAG, "No se pudo programar la Actualización\n${detalleTecnico(fallo)}")
                    _estado.value =
                        _estado.value.copy(
                            actualizacion =
                                ResumenActualizacion(
                                    listaId = listaId,
                                    resultado = CODIGO_ACTUALIZACION_FALLIDA,
                                    error = CODIGO_ACTUALIZACION_FALLIDA,
                                ),
                        )
                }
            }
        }

        /**
         * Refleja el trabajo de la Lista activa (S7a).
         *
         * `actualizando` sale del estado del trabajo, no de un indicador
         * optimista: así también se refleja una ejecución lanzada por el
         * planificador periódico, y el botón vuelve a habilitarse tanto en éxito
         * como en fallo. El resumen visible es el de esta Lista; si el trabajo no
         * publicó ninguno (por ejemplo, se coalesció con otra ejecución), se
         * conserva el último.
         */
        private fun observarActualizacion() {
            viewModelScope.launch {
                listas
                    .map { actuales -> actuales.firstOrNull { it.activa }?.id }
                    .distinctUntilChanged()
                    .flatMapLatest { listaId ->
                        _estado.update { actual -> actual.copy(actualizacion = null) }
                        val flujo =
                            if (listaId == null) {
                                flowOf(EstadoSolicitud())
                            } else {
                                programador.observarLista(listaId)
                            }
                        flujo.map { solicitud -> listaId to solicitud }
                    }
                    .collect { (listaId, solicitud) ->
                        _estado.update { actual ->
                            actual.copy(
                                actualizando = solicitud.enCurso,
                                actualizacion = solicitud.resumenDe(listaId ?: -1L) ?: actual.actualizacion,
                            )
                        }
                    }
            }
        }

        /**
         * Observa la preferencia de la Lista activa y la refleja en los criterios.
         *
         * Al cambiar de Lista, el flujo se cambia sin perder el modo y la
         * dirección que la nueva Lista tenga persistidos.
         */
        private fun observarPreferencias() {
            viewModelScope.launch {
                listas
                    .map { actuales -> actuales.firstOrNull { it.activa }?.id }
                    .distinctUntilChanged()
                    .flatMapLatest { listaId ->
                        if (listaId == null) {
                            flowOf(PreferenciaOrden())
                        } else {
                            preferencias.preferencia(listaId)
                        }
                    }
                    .collectLatest { preferencia ->
                        _criterios.value =
                            _criterios.value.copy(
                                modo = preferencia.modo,
                                direccion = preferencia.direccion,
                            )
                    }
            }
        }

        private fun sincronizarPreferencias() {
            viewModelScope.launch { crud.sincronizarPreferenciasDeOrden() }
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

            /** Etiqueta `resultado` del informe sobreviviente en éxito (S49). */
            const val INFORME_EXITO = "completada"
            const val CODIGO_ACTUALIZACION_FALLIDA = "actualizacion_fallida"
            const val ESPERA_TECLEO_MS = 150L
            const val TIEMPO_SIN_SUSCRIPTORES = 5_000L
            const val TAG = "CatalogoViewModel"
        }
    }
