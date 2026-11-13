package com.copernicotv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.material3.windowsizeclass.ExperimentalMaterial3WindowSizeClassApi
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.material3.windowsizeclass.calculateWindowSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.copernicotv.app.data.PreferenciasTema
import com.copernicotv.app.data.actualizacion.InicializadorActualizaciones
import com.copernicotv.app.ui.CatalogoAcciones
import com.copernicotv.app.ui.CatalogoScreen
import com.copernicotv.app.ui.CatalogoViewModel
import com.copernicotv.app.ui.ColeccionesUiState
import com.copernicotv.app.ui.CoperNicoTheme
import com.copernicotv.app.ui.FuentesScreen
import com.copernicotv.app.ui.FuentesViewModel
import com.copernicotv.app.ui.GuiaScreen
import com.copernicotv.app.ui.GuiaViewModel
import com.copernicotv.app.ui.PerfilesUiState
import com.copernicotv.app.ui.ReproductorScreen
import com.copernicotv.app.ui.recordarEsTv
import com.copernicotv.core.tema.TemaUi
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Pantallas de la actividad única. */
private enum class Pantalla { CATALOGO, FUENTES, GUIA, REPRODUCTOR }

/** Única actividad del canario de E1-C. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var inicializador: InicializadorActualizaciones

    @Inject
    lateinit var preferenciasTema: PreferenciasTema

    @OptIn(ExperimentalMaterial3WindowSizeClassApi::class)
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reconciliación de programaciones de DC-07 punto 5. Es idempotente por
        // proceso, así que una recreación de la Activity no la repite.
        inicializador.iniciar()
        // E7-T3: dibujo edge-to-edge; cada pantalla raíz consume los insets de
        // barras del sistema con windowInsetsPadding (el reproductor ya los
        // maneja en sus overlays).
        enableEdgeToEdge()
        setContent {
            // S43/E7-T4: el tema vive en DataStore y alimenta tanto el esquema
            // de color como la etiqueta del toggle del catálogo. El initial
            // value es el oscuro, el default previo a E7-T4.
            val tema by preferenciasTema.tema().collectAsStateWithLifecycle(initialValue = TemaUi.OSCURO)
            val alcance = rememberCoroutineScope()
            CoperNicoTheme(esTv = recordarEsTv(), tema = tema) {
                // E7-T3: el layout se elige por window size class de la ventana
                // (breakpoints de Material), no por orientación de la pantalla.
                val tamanosVentana = calculateWindowSizeClass(this)
                val anchoVentana = tamanosVentana.widthSizeClass
                var pantalla by rememberSaveable { mutableStateOf(Pantalla.CATALOGO) }
                var canalReproduciendo by rememberSaveable { mutableStateOf(-1L) }
                when (pantalla) {
                    Pantalla.CATALOGO ->
                        PantallaCatalogo(
                            anchoVentana = anchoVentana,
                            tema = tema,
                            onAlternarTema = { alcance.launch { preferenciasTema.alternar() } },
                            onAbrirFuentes = { pantalla = Pantalla.FUENTES },
                            onAbrirGuia = { pantalla = Pantalla.GUIA },
                            onAbrirReproductor = { canalId ->
                                canalReproduciendo = canalId
                                pantalla = Pantalla.REPRODUCTOR
                            },
                        )
                    Pantalla.FUENTES -> {
                        val fuentesViewModel: FuentesViewModel = hiltViewModel()
                        val fuentesEstado by fuentesViewModel.estado.collectAsStateWithLifecycle()
                        FuentesScreen(
                            estado = fuentesEstado,
                            onActualizar = fuentesViewModel::actualizar,
                            onVolver = { pantalla = Pantalla.CATALOGO },
                        )
                    }
                    Pantalla.GUIA -> {
                        val guiaViewModel: GuiaViewModel = hiltViewModel()
                        val guiaEstado by guiaViewModel.estado.collectAsStateWithLifecycle()
                        val programasFlow by guiaViewModel.programas.collectAsStateWithLifecycle()
                        key(programasFlow) {
                            val programas = programasFlow.collectAsLazyPagingItems()
                            GuiaScreen(
                                estado = guiaEstado,
                                programas = programas,
                                onSeleccionarFuente = guiaViewModel::seleccionarFuente,
                                onAbrirCanal = guiaViewModel::abrirCanal,
                                onVolverAlListado = guiaViewModel::volverAlListado,
                                onAbrirDetalle = guiaViewModel::abrirDetalle,
                                onCerrarDetalle = guiaViewModel::cerrarDetalle,
                                onConfirmarAsociacion = guiaViewModel::confirmarAsociacion,
                                onEditarAsociacion = guiaViewModel::editarAsociacion,
                                onRevocarAsociacion = guiaViewModel::revocarAsociacion,
                                onVolver = { pantalla = Pantalla.CATALOGO },
                            )
                        }
                    }
                    Pantalla.REPRODUCTOR ->
                        ReproductorScreen(
                            canalId = canalReproduciendo,
                            onVolver = { pantalla = Pantalla.CATALOGO },
                        )
                }
            }
        }
    }
}

@Composable
private fun PantallaCatalogo(
    anchoVentana: WindowWidthSizeClass,
    tema: TemaUi,
    onAlternarTema: () -> Unit,
    onAbrirFuentes: () -> Unit,
    onAbrirGuia: () -> Unit,
    onAbrirReproductor: (Long) -> Unit,
) {
    val viewModel: CatalogoViewModel = hiltViewModel()
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val criterios by viewModel.criterios.collectAsStateWithLifecycle()
    val grupos by viewModel.grupos.collectAsStateWithLifecycle()
    val listas by viewModel.listas.collectAsStateWithLifecycle()
    val colecciones by viewModel.colecciones.collectAsStateWithLifecycle()
    val coleccionSeleccionadaId by viewModel.coleccionSeleccionadaId.collectAsStateWithLifecycle()
    val coleccionCanales by viewModel.coleccionCanales.collectAsStateWithLifecycle()
    val perfiles by viewModel.perfiles.collectAsStateWithLifecycle()
    val perfilSeleccionadoId by viewModel.perfilSeleccionadoId.collectAsStateWithLifecycle()
    val listasDePerfil by viewModel.listasDePerfil.collectAsStateWithLifecycle()
    val recientes by viewModel.recientes.collectAsStateWithLifecycle()
    val resultados = viewModel.resultados.collectAsLazyPagingItems()
    // `ACTION_OPEN_DOCUMENT` es el único mecanismo que produce un
    // grant persistible; `GET_CONTENT` no lo hace y rompería la
    // reapertura de la Lista tras reiniciar el proceso.
    val selector =
        rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
            if (uri != null) viewModel.importarDesdeSaf(uri.toString())
        }
    CatalogoScreen(
        estado = estado,
        anchoVentana = anchoVentana,
        criterios = criterios,
        grupos = grupos,
        listas = listas,
        coleccionesUi =
            ColeccionesUiState(
                colecciones = colecciones,
                seleccionadaId = coleccionSeleccionadaId,
                canales = coleccionCanales,
            ),
        perfilesUi =
            PerfilesUiState(
                perfiles = perfiles,
                seleccionadoId = perfilSeleccionadoId,
                listas = listasDePerfil,
            ),
        recientes = recientes,
        resultados = resultados,
        tema = tema,
        acciones =
            CatalogoAcciones(
                onImportar = viewModel::importarFixture,
                onAbrirDocumento = { selector.launch(arrayOf("*/*")) },
                onImportarUrl = viewModel::importarDesdeUrl,
                onImportarUrlReiniciar = viewModel::reiniciarImportacionUrl,
                onImportarUrlCancelar = viewModel::cancelarImportacionUrl,
                onActualizar = viewModel::actualizarListaActiva,
                onTexto = viewModel::actualizarTexto,
                onGrupo = viewModel::seleccionarGrupo,
                onFavoritos = viewModel::alternarFavoritos,
                onTodos = viewModel::limpiarFiltros,
                onLimpiar = viewModel::limpiarCriterios,
                onMostrarOcultos = viewModel::alternarMostrarOcultos,
                onSeleccionarLista = viewModel::seleccionarLista,
                onCrearLista = viewModel::crearLista,
                onRenombrarLista = viewModel::renombrarLista,
                onEliminarLista = viewModel::eliminarLista,
                onCrearCanal = viewModel::crearCanal,
                onEditarNombreCanal = viewModel::editarNombreCanal,
                onEliminarCanal = viewModel::eliminarCanal,
                onReproducir = onAbrirReproductor,
                onAlternarFavorito = viewModel::alternarFavorito,
                onAlternarOculto = viewModel::alternarOculto,
                onMoverCanal = viewModel::moverCanal,
                onModo = viewModel::seleccionarModo,
                onDireccion = viewModel::alternarDireccion,
                onSeleccionarColeccion = viewModel::seleccionarColeccion,
                onCrearColeccion = viewModel::crearColeccion,
                onRenombrarColeccion = viewModel::renombrarColeccion,
                onEliminarColeccion = viewModel::eliminarColeccion,
                onAgregarCanalAColeccion = viewModel::agregarCanalAColeccion,
                onQuitarCanalDeColeccion = viewModel::quitarCanalDeColeccion,
                onMoverCanalEnColeccion = viewModel::moverCanalEnColeccion,
                onSeleccionarPerfil = viewModel::seleccionarPerfil,
                onCrearPerfil = viewModel::crearPerfil,
                onRenombrarPerfil = viewModel::renombrarPerfil,
                onEliminarPerfil = viewModel::eliminarPerfil,
                onAsociarListaAPerfil = viewModel::asociarListaAPerfil,
                onDesasociarListaDePerfil = viewModel::desasociarListaDePerfil,
                onAbrirFuentes = onAbrirFuentes,
                onAbrirGuia = onAbrirGuia,
                onAlternarTema = onAlternarTema,
                onBorrarHistorial = viewModel::borrarHistorial,
                onBorrarFavoritos = viewModel::borrarFavoritos,
            ),
    )
}
