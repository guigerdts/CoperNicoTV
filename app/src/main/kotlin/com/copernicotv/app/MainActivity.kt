package com.copernicotv.app

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.paging.compose.collectAsLazyPagingItems
import com.copernicotv.app.ui.CatalogoAcciones
import com.copernicotv.app.ui.CatalogoScreen
import com.copernicotv.app.ui.CatalogoViewModel
import com.copernicotv.app.ui.CoperNicoTheme
import dagger.hilt.android.AndroidEntryPoint

/** Única actividad del canario de E1-C. */
@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            CoperNicoTheme {
                val viewModel: CatalogoViewModel = hiltViewModel()
                val estado by viewModel.estado.collectAsStateWithLifecycle()
                val criterios by viewModel.criterios.collectAsStateWithLifecycle()
                val grupos by viewModel.grupos.collectAsStateWithLifecycle()
                val listas by viewModel.listas.collectAsStateWithLifecycle()
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
                    resultados = resultados,
                    acciones =
                        CatalogoAcciones(
                            onImportar = viewModel::importarFixture,
                            onAbrirDocumento = { selector.launch(arrayOf("*/*")) },
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
                        ),
                )
            }
        }
    }
}
