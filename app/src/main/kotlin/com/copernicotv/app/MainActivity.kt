package com.copernicotv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.copernicotv.app.data.actualizacion.InicializadorActualizaciones
import com.copernicotv.app.ui.CatalogoAcciones
import com.copernicotv.app.ui.CatalogoScreen
import com.copernicotv.app.ui.CatalogoViewModel
import com.copernicotv.app.ui.ColeccionesUiState
import com.copernicotv.app.ui.CoperNicoTheme
import com.copernicotv.app.ui.FuentesScreen
import com.copernicotv.app.ui.FuentesViewModel
import com.copernicotv.app.ui.PerfilesUiState
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

/** Pantallas de la actividad única. */
private enum class Pantalla { CATALOGO, FUENTES }

/** Única actividad del canario de E1-C. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    @Inject
    lateinit var inicializador: InicializadorActualizaciones

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Reconciliación de programaciones de DC-07 punto 5. Es idempotente por
        // proceso, así que una recreación de la Activity no la repite.
        inicializador.iniciar()
        setContent {
            CoperNicoTheme {
                var pantalla by rememberSaveable { mutableStateOf(Pantalla.CATALOGO) }
                when (pantalla) {
                    Pantalla.CATALOGO ->
                        PantallaCatalogo(onAbrirFuentes = { pantalla = Pantalla.FUENTES })
                    Pantalla.FUENTES -> {
                        val fuentesViewModel: FuentesViewModel = hiltViewModel()
                        val fuentesEstado by fuentesViewModel.estado.collectAsStateWithLifecycle()
                        FuentesScreen(
                            estado = fuentesEstado,
                            onActualizar = fuentesViewModel::actualizar,
                            onVolver = { pantalla = Pantalla.CATALOGO },
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun PantallaCatalogo(onAbrirFuentes: () -> Unit) {
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
        acciones =
            CatalogoAcciones(
                onImportar = viewModel::importarFixture,
                onAbrirDocumento = { selector.launch(arrayOf("*/*")) },
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
            ),
    )
}
