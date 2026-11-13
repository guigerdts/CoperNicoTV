package com.copernicotv.app.ui

import androidx.compose.foundation.border
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.windowsizeclass.WindowWidthSizeClass
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusDirection
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusProperties
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onKeyEvent
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.copernicotv.app.R
import com.copernicotv.app.data.CanalBusqueda
import com.copernicotv.app.data.ColeccionCanalResumen
import com.copernicotv.app.data.ColeccionResumen
import com.copernicotv.app.data.CriteriosBusqueda
import com.copernicotv.app.data.GrupoOpcion
import com.copernicotv.app.data.HistorialResumen
import com.copernicotv.app.data.ListaResumen
import com.copernicotv.app.data.PerfilResumen
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import com.copernicotv.core.tema.TemaUi

/** Etiqueta del disparador de importación del canario. */
const val TAG_IMPORTAR = "importar-fixture"

/** Etiqueta del disparador de selección de documento SAF. */
const val TAG_ABRIR_DOCUMENTO = "abrir-documento"

/** Etiqueta del disparador de alta de Fuente por URL. */
const val TAG_IMPORTAR_URL = "importar-url"

/** Fase visible dentro del diálogo de importación por URL (E7-T7). */
const val TAG_IMPORTAR_URL_FASE = "importar-url-fase"

/** Error con causa dentro del diálogo de importación por URL (E7-T7). */
const val TAG_IMPORTAR_URL_ERROR = "importar-url-error"

/** Éxito con número de canales del diálogo de importación por URL (E7-T7). */
const val TAG_IMPORTAR_URL_EXITO = "importar-url-exito"

/** Etiqueta del disparador de Actualización manual de la Lista activa. */
const val TAG_ACTUALIZAR = "actualizar-lista"

/** Etiqueta del resumen de la última Actualización manual. */
const val TAG_RESULTADO_ACTUALIZACION = "resultado-actualizacion"

/** Etiqueta del botón que abre la pantalla de Fuentes. */
const val TAG_FUENTES = "abrir-fuentes"

/** Etiqueta del botón que abre la Guía. */
const val TAG_GUIA = "abrir-guia"

/** Prefijo de las etiquetas de canal del catálogo. */
const val PREFIJO_TAG_CANAL = "canal-"

/** Etiqueta del campo de búsqueda por nombre. */
const val TAG_BUSCAR = "buscar-catalogo"

/** Etiqueta del botón que elimina todos los criterios. */
const val TAG_LIMPIAR = "limpiar-criterios"

/** Etiqueta del filtro que quita Grupo y Favorito. */
const val TAG_FILTRO_TODOS = "filtro-todos"

/** Etiqueta del filtro de Favoritos. */
const val TAG_FILTRO_FAVORITOS = "filtro-favoritos"

/** Etiqueta del filtro que incluye Canales ocultos. */
const val TAG_FILTRO_OCULTOS = "filtro-ocultos"

/** Prefijo de las etiquetas de filtro por Grupo. */
const val PREFIJO_TAG_GRUPO = "filtro-grupo-"

/** Etiqueta del estado sin resultados. */
const val TAG_SIN_RESULTADOS = "sin-resultados"

/** Etiqueta del estado de catálogo vacío. */
const val TAG_CATALOGO_VACIO = "catalogo-vacio"

/** Etiqueta del error de carga paginada. */
const val TAG_ERROR_CARGA = "error-carga-catalogo"

/** Etiqueta del botón de reintento de carga. */
const val TAG_REINTENTAR = "reintentar-catalogo"

/** Etiqueta del indicador de carga. */
const val TAG_CARGANDO = "cargando-catalogo"

/** Etiqueta de la lista de resultados del catálogo. */
const val TAG_RESULTADOS = "resultados-catalogo"

/** Altura mínima (dp) que conserva la lista de resultados en pantallas bajas. */
private const val ALTO_MINIMO_RESULTADOS_DP = 96

/** Etiqueta del mensaje de error de una acción CRUD. */
const val TAG_ERROR_ACCION = "error-accion"

/** Etiqueta del selector de Lista activa. */
const val TAG_LISTA_ACTIVA = "lista-activa"

/** Prefijo de las etiquetas de Lista en el selector. */
const val PREFIJO_TAG_LISTA = "lista-"

/** Etiqueta del botón para crear una Lista. */
const val TAG_NUEVA_LISTA = "nueva-lista"

/** Etiqueta del botón para renombrar la Lista activa. */
const val TAG_RENOMBRAR_LISTA = "renombrar-lista"

/** Etiqueta del botón para eliminar la Lista activa. */
const val TAG_ELIMINAR_LISTA = "eliminar-lista"

/** Etiqueta del botón para crear un Canal. */
const val TAG_NUEVO_CANAL = "nuevo-canal"

/** Etiqueta del diálogo de acciones de un Canal. */
const val TAG_ACCIONES_CANAL = "acciones-canal"

/** Etiqueta de la acción Favorito. */
const val TAG_ACCION_FAVORITO = "accion-favorito"

/** Etiqueta de la acción Reproducir (E6-T1). */
const val TAG_ACCION_REPRODUCIR = "accion-reproducir"

/** Etiqueta de la acción Ocultar/Mostrar. */
const val TAG_ACCION_OCULTO = "accion-oculto"

/** Etiqueta de la acción Editar. */
const val TAG_ACCION_EDITAR = "accion-editar"

/** Etiqueta de la acción Subir. */
const val TAG_ACCION_SUBIR = "accion-subir"

/** Etiqueta de la acción Bajar. */
const val TAG_ACCION_BAJAR = "accion-bajar"

/** Etiqueta de la acción Eliminar. */
const val TAG_ACCION_ELIMINAR = "accion-eliminar"

/** Etiqueta de la acción Cerrar. */
const val TAG_ACCION_CERRAR = "accion-cerrar"

/** Etiqueta del campo de nombre de los diálogos. */
const val TAG_DIALOGO_NOMBRE = "dialogo-nombre"

/** Etiqueta del campo de URL del diálogo de Canal. */
const val TAG_DIALOGO_URL = "dialogo-url"

/** Etiqueta del botón Guardar de los diálogos. */
const val TAG_DIALOGO_GUARDAR = "dialogo-guardar"

/** Etiqueta del botón Cancelar de los diálogos. */
const val TAG_DIALOGO_CANCELAR = "dialogo-cancelar"

/** Etiqueta del botón de confirmación de borrado. */
const val TAG_CONFIRMAR_ELIMINAR = "confirmar-eliminar"

/** Etiqueta del control de Privacidad de S47 (borrado de Historial/Favoritos). */
const val TAG_PRIVACIDAD = "privacidad"

/** Etiqueta de la acción de borrado del Historial de S47a. */
const val TAG_BORRAR_HISTORIAL = "borrar-historial"

/** Etiqueta de la acción de borrado de Favoritos de S47b. */
const val TAG_BORRAR_FAVORITOS = "borrar-favoritos"

/** Etiqueta del botón que confirma un borrado de S47a/S47b. */
const val TAG_CONFIRMAR_BORRAR = "confirmar-borrar"

/** Etiqueta del selector de modo de ordenación A/B/C. */
const val TAG_MODO = "orden-modo"

/** Etiqueta del botón que invierte la dirección de ordenación. */
const val TAG_DIRECCION = "orden-direccion"

/** Prefijo de las etiquetas de cada opción de modo de ordenación. */
const val PREFIJO_TAG_MODO_OPCION = "orden-modo-"

/** Prefijo de las etiquetas de las cabeceras de Grupo. */
const val PREFIJO_TAG_SECCION_GRUPO = "seccion-grupo-"

/** Etiqueta de la sección de acceso rápido de Historial. */
const val TAG_ACCESO_RAPIDO = "acceso-rapido"

/** Prefijo de las etiquetas de cada Canal reciente. */
const val PREFIJO_TAG_RECIENTE = "reciente-"

/** Etiqueta del botón que abre la gestión de Colecciones. */
const val TAG_COLECCIONES = "abrir-colecciones"

/** Etiqueta del botón que abre la gestión de Perfiles. */
const val TAG_PERFILES = "abrir-perfiles"

/** Etiqueta del botón que alterna el tema claro/oscuro (S43, E7-T4). */
const val TAG_TEMA = "alternar-tema"

/** Etiqueta del botón para crear una Colección. */
const val TAG_NUEVA_COLECCION = "nueva-coleccion"

/** Etiqueta del botón para renombrar la Colección seleccionada. */
const val TAG_RENOMBRAR_COLECCION = "renombrar-coleccion"

/** Etiqueta del botón para eliminar la Colección seleccionada. */
const val TAG_ELIMINAR_COLECCION = "eliminar-coleccion"

/** Etiqueta del selector de Colección activa. */
const val TAG_COLECCION_ACTIVA = "coleccion-activa"

/** Prefijo de las etiquetas de Colección en el selector. */
const val PREFIJO_TAG_COLECCION = "coleccion-"

/** Prefijo de las etiquetas de pertenencia Canal↔Colección. */
const val PREFIJO_TAG_COLECCION_CANAL = "coleccion-canal-"

/** Prefijo de la acción Subir dentro de una Colección. */
const val TAG_COLECCION_CANAL_SUBIR = "coleccion-canal-subir-"

/** Prefijo de la acción Bajar dentro de una Colección. */
const val TAG_COLECCION_CANAL_BAJAR = "coleccion-canal-bajar-"

/** Prefijo de la acción Quitar de una Colección. */
const val TAG_COLECCION_CANAL_QUITAR = "coleccion-canal-quitar-"

/** Etiqueta de la acción Agregar a Colección en el diálogo de Canal. */
const val TAG_ACCION_AGREGAR_COLECCION = "accion-agregar-coleccion"

/** Prefijo de las etiquetas de Colección en el diálogo Agregar. */
const val TAG_AGREGAR_A_COLECCION = "agregar-a-coleccion-"

/** Etiqueta del botón para crear un Perfil. */
const val TAG_NUEVO_PERFIL = "nuevo-perfil"

/** Etiqueta del botón para renombrar el Perfil seleccionado. */
const val TAG_RENOMBRAR_PERFIL = "renombrar-perfil"

/** Etiqueta del botón para eliminar el Perfil seleccionado. */
const val TAG_ELIMINAR_PERFIL = "eliminar-perfil"

/** Etiqueta del selector de Perfil activo. */
const val TAG_PERFIL_ACTIVO = "perfil-activo"

/** Prefijo de las etiquetas de Perfil en el selector. */
const val PREFIJO_TAG_PERFIL = "perfil-"

/** Prefijo de las etiquetas de Lista asociada a un Perfil. */
const val PREFIJO_TAG_PERFIL_LISTA = "perfil-lista-"

/** Estado de la gestión de Colecciones que la UI recibe ya resuelto. */
data class ColeccionesUiState(
    val colecciones: List<ColeccionResumen> = emptyList(),
    val seleccionadaId: Long? = null,
    val canales: List<ColeccionCanalResumen> = emptyList(),
)

/** Estado de la gestión de Perfiles que la UI recibe ya resuelto. */
data class PerfilesUiState(
    val perfiles: List<PerfilResumen> = emptyList(),
    val seleccionadoId: Long? = null,
    val listas: List<Long> = emptyList(),
)

/** Acciones que la pantalla de catálogo delega en el ViewModel. */
data class CatalogoAcciones(
    val onImportar: () -> Unit,
    val onAbrirDocumento: () -> Unit,
    val onImportarUrl: (String, String) -> Unit,
    val onImportarUrlReiniciar: () -> Unit,
    val onActualizar: () -> Unit,
    val onTexto: (String) -> Unit,
    val onGrupo: (Long?) -> Unit,
    val onFavoritos: () -> Unit,
    val onTodos: () -> Unit,
    val onLimpiar: () -> Unit,
    val onMostrarOcultos: () -> Unit,
    val onSeleccionarLista: (Long) -> Unit,
    val onCrearLista: (String) -> Unit,
    val onRenombrarLista: (Long, String) -> Unit,
    val onEliminarLista: (Long) -> Unit,
    val onCrearCanal: (String?, String) -> Unit,
    val onEditarNombreCanal: (Long, String?) -> Unit,
    val onEliminarCanal: (Long) -> Unit,
    val onReproducir: (Long) -> Unit,
    val onAlternarFavorito: (Long) -> Unit,
    val onAlternarOculto: (Long) -> Unit,
    val onMoverCanal: (Long, Int) -> Unit,
    val onModo: (ModoOrden) -> Unit,
    val onDireccion: () -> Unit,
    val onSeleccionarColeccion: (Long?) -> Unit,
    val onCrearColeccion: (String) -> Unit,
    val onRenombrarColeccion: (Long, String) -> Unit,
    val onEliminarColeccion: (Long) -> Unit,
    val onAgregarCanalAColeccion: (Long, Long) -> Unit,
    val onQuitarCanalDeColeccion: (Long) -> Unit,
    val onMoverCanalEnColeccion: (Long, Int) -> Unit,
    val onSeleccionarPerfil: (Long?) -> Unit,
    val onCrearPerfil: (String) -> Unit,
    val onRenombrarPerfil: (Long, String) -> Unit,
    val onEliminarPerfil: (Long) -> Unit,
    val onAsociarListaAPerfil: (Long, Long) -> Unit,
    val onDesasociarListaDePerfil: (Long, Long) -> Unit,
    val onAbrirFuentes: () -> Unit,
    val onAbrirGuia: () -> Unit,
    val onAlternarTema: () -> Unit,
    val onBorrarHistorial: () -> Unit,
    val onBorrarFavoritos: () -> Unit,
)

/**
 * Pantalla del catálogo: importar, buscar, filtrar, gestionar Listas y Canales
 * y reordenar Apariencias con D-pad o toque.
 *
 * Cada resultado es un único nodo enfocable para que el recorrido con D-pad sea
 * predecible: `Arriba`/`Abajo` mueven entre Canales y `Centro` abre las acciones.
 */
@Composable
fun CatalogoScreen(
    estado: CatalogoUiState,
    anchoVentana: WindowWidthSizeClass,
    criterios: CriteriosBusqueda,
    grupos: List<GrupoOpcion>,
    listas: List<ListaResumen>,
    coleccionesUi: ColeccionesUiState,
    perfilesUi: PerfilesUiState,
    recientes: List<HistorialResumen>,
    resultados: LazyPagingItems<CanalBusqueda>,
    tema: TemaUi,
    acciones: CatalogoAcciones,
) {
    var dialogo by remember { mutableStateOf<DialogoCatalogo?>(null) }
    val listaActiva = listas.firstOrNull { it.activa }
    val focoInicial = remember { FocusRequester() }
    val focoPrimerCanal = remember { FocusRequester() }
    LaunchedEffect(Unit) { focoInicial.requestFocus() }
    val hayFiltros = hayFiltrosActivos(criterios)
    // E7-T3: la rama de layout sigue los breakpoints de ancho de Material en
    // vez de comparar ancho y alto de la pantalla.
    val horizontal = anchoVentana != WindowWidthSizeClass.Compact
    val configuracion = LocalConfiguration.current

    Surface(
        modifier =
            Modifier
                // E7-T3 (edge-to-edge): el contenido respeta las barras del
                // sistema pero no consume el teclado: descontarlo colapsaría
                // el catálogo y superpondría controles con la lista; el campo
                // de búsqueda ya queda visible sobre el teclado.
                .fillMaxSize()
                .windowInsetsPadding(
                    WindowInsets.safeDrawing.only(
                        WindowInsetsSides.Horizontal + WindowInsetsSides.Top,
                    ),
                )
                .windowInsetsPadding(WindowInsets.navigationBars.only(WindowInsetsSides.Bottom)),
    ) {
        if (horizontal) {
            Row(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                horizontalArrangement = Arrangement.spacedBy(24.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .width(420.dp)
                            .fillMaxHeight()
                            .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    Controles(
                        estado = estado,
                        criterios = criterios,
                        grupos = grupos,
                        listas = listas,
                        listaActiva = listaActiva,
                        recientes = recientes,
                        acciones = acciones,
                        tema = tema,
                        focoInicial = focoInicial,
                        focoPrimerCanal = focoPrimerCanal,
                        onDialogo = { dialogo = it },
                    )
                }
                Resultados(
                    modo = criterios.modo,
                    resultados = resultados,
                    hayFiltros = hayFiltros,
                    focoPrimerCanal = focoPrimerCanal,
                    onCanal = { canal -> dialogo = DialogoCatalogo.AccionesCanal(canal) },
                    onReintentar = { resultados.retry() },
                    modifier = Modifier.weight(1f).fillMaxHeight(),
                )
            }
        } else {
            // En pantallas bajas los controles no deben dejar sin altura a los
            // resultados: se acotan y pasan a hacer scroll, y la lista conserva
            // un mínimo visible para seguir siendo usable. El tope descuenta
            // solo las barras del sistema: el padding raíz tampoco consume el
            // teclado, y descontarlo aquí colapsaría la columna de controles
            // y superpondría los chips con la lista (observado en CI).
            val densidad = LocalDensity.current
            val insetsVerticales =
                with(densidad) {
                    (
                        WindowInsets.systemBars.getTop(densidad) +
                            WindowInsets.systemBars.getBottom(densidad)
                    ).toDp()
                }
            val altoMaximoControles =
                (
                    configuracion.screenHeightDp.dp - insetsVerticales - 48.dp -
                        ALTO_MINIMO_RESULTADOS_DP.dp - 12.dp
                ).coerceAtLeast(0.dp)
            Column(
                modifier = Modifier.fillMaxSize().padding(24.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                Column(
                    modifier =
                        Modifier
                            .heightIn(max = altoMaximoControles)
                            .verticalScroll(rememberScrollState()),
                ) {
                    Controles(
                        estado = estado,
                        criterios = criterios,
                        grupos = grupos,
                        listas = listas,
                        listaActiva = listaActiva,
                        recientes = recientes,
                        acciones = acciones,
                        tema = tema,
                        focoInicial = focoInicial,
                        focoPrimerCanal = null,
                        onDialogo = { dialogo = it },
                    )
                }
                Resultados(
                    modo = criterios.modo,
                    resultados = resultados,
                    hayFiltros = hayFiltros,
                    focoPrimerCanal = null,
                    onCanal = { canal -> dialogo = DialogoCatalogo.AccionesCanal(canal) },
                    onReintentar = { resultados.retry() },
                    modifier = Modifier.weight(1f),
                )
            }
        }
    }

    DialogoCatalogoActivo(
        dialogo = dialogo,
        acciones = acciones,
        listas = listas,
        coleccionesUi = coleccionesUi,
        perfilesUi = perfilesUi,
        importacionUrl = estado.importacionUrl,
        importando = estado.importando,
        onDialogo = { dialogo = it },
    )
}

/** True si hay algún criterio de búsqueda activo. */
private fun hayFiltrosActivos(criterios: CriteriosBusqueda): Boolean =
    criterios.texto.isNotEmpty() ||
        criterios.grupoId != null ||
        criterios.soloFavoritos ||
        criterios.mostrarOcultos

/** Fila de accesos del catálogo y del toggle de tema (S43, E7-T4). */
@Composable
private fun FilaControles(
    acciones: CatalogoAcciones,
    tema: TemaUi,
    onDialogo: (DialogoCatalogo) -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        TextButton(
            onClick = { onDialogo(DialogoCatalogo.Colecciones) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_COLECCIONES),
        ) {
            Text(stringResource(R.string.catalogo_colecciones))
        }
        TextButton(
            onClick = { onDialogo(DialogoCatalogo.Perfiles) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_PERFILES),
        ) {
            Text(stringResource(R.string.catalogo_perfiles))
        }
        TextButton(
            onClick = acciones.onAbrirFuentes,
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_FUENTES),
        ) {
            Text(stringResource(R.string.catalogo_fuentes))
        }
        TextButton(
            onClick = acciones.onAbrirGuia,
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_GUIA),
        ) {
            Text(stringResource(R.string.catalogo_guia))
        }
        // S43: la etiqueta muestra el tema actual; un toque lo invierte.
        val etiquetaTema =
            if (tema == TemaUi.CLARO) {
                R.string.catalogo_tema_claro
            } else {
                R.string.catalogo_tema_oscuro
            }
        TextButton(
            onClick = acciones.onAlternarTema,
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_TEMA),
        ) {
            Text(stringResource(etiquetaTema))
        }
        // S47: el borrado de Historial y Favoritos vive aquí, con diálogo y
        // confirmación; cada acción es independiente y conserva la otra.
        TextButton(
            onClick = { onDialogo(DialogoCatalogo.Privacidad()) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_PRIVACIDAD),
        ) {
            Text(stringResource(R.string.catalogo_privacidad))
        }
    }
}

@Composable
private fun Controles(
    estado: CatalogoUiState,
    criterios: CriteriosBusqueda,
    grupos: List<GrupoOpcion>,
    listas: List<ListaResumen>,
    listaActiva: ListaResumen?,
    recientes: List<HistorialResumen>,
    acciones: CatalogoAcciones,
    tema: TemaUi,
    focoInicial: FocusRequester,
    focoPrimerCanal: FocusRequester?,
    onDialogo: (DialogoCatalogo) -> Unit,
) {
    Text(
        text = stringResource(R.string.catalogo_titulo),
        style = MaterialTheme.typography.headlineMedium,
    )
    AccionesImportacion(
        estado = estado,
        acciones = acciones,
        focoInicial = focoInicial,
        onDialogo = onDialogo,
    )
    SelectorLista(
        listas = listas,
        listaActiva = listaActiva,
        onSeleccionar = acciones.onSeleccionarLista,
        onNueva = { onDialogo(DialogoCatalogo.NuevaLista) },
        onRenombrar = {
            listaActiva?.let { onDialogo(DialogoCatalogo.RenombrarLista(it)) }
        },
        onEliminar = {
            listaActiva?.let { onDialogo(DialogoCatalogo.EliminarLista(it)) }
        },
    )
    AccionActualizar(estado, listaActiva, acciones)
    AccesoRapido(
        recientes = recientes,
        onCanal = { reciente -> onDialogo(DialogoCatalogo.AccionesCanal(reciente.aCanalBusqueda())) },
    )
    FilaControles(acciones = acciones, tema = tema, onDialogo = onDialogo)
    estado.mensaje?.let { codigo ->
        Text(
            text = stringResource(R.string.catalogo_error, codigo),
            style = MaterialTheme.typography.bodyMedium,
        )
    }
    estado.mensajeAccion?.let { codigo ->
        Text(
            text = stringResource(R.string.catalogo_accion_error, codigo),
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TAG_ERROR_ACCION),
        )
    }
    // En TV el campo de una línea consume D-pad Abajo/Arriba y atrapa el
    // foco: la intercepción en preview mueve el foco a destino antes de que
    // el campo trate la tecla como edición de texto.
    val manejadorFoco = LocalFocusManager.current
    OutlinedTextField(
        value = criterios.texto,
        onValueChange = acciones.onTexto,
        label = { Text(stringResource(R.string.catalogo_buscar)) },
        singleLine = true,
        modifier =
            Modifier
                .fillMaxWidth()
                .onPreviewKeyEvent { evento ->
                    evento.type == KeyEventType.KeyUp &&
                        when (evento.key) {
                            Key.DirectionDown -> manejadorFoco.moveFocus(FocusDirection.Down)
                            Key.DirectionUp -> manejadorFoco.moveFocus(FocusDirection.Up)
                            else -> false
                        }
                }
                .testTag(TAG_BUSCAR),
    )
    if (criterios.texto.isNotEmpty() || criterios.grupoId != null || criterios.soloFavoritos) {
        TextButton(
            onClick = acciones.onLimpiar,
            modifier = Modifier.testTag(TAG_LIMPIAR),
        ) {
            Text(stringResource(R.string.catalogo_limpiar))
        }
    }
    FiltrosYOrden(
        criterios = criterios,
        grupos = grupos,
        onGrupo = acciones.onGrupo,
        onFavoritos = acciones.onFavoritos,
        onTodos = acciones.onTodos,
        onMostrarOcultos = acciones.onMostrarOcultos,
        onModo = acciones.onModo,
        onDireccion = acciones.onDireccion,
    )
    BotonNuevoCanal(
        listaActiva = listaActiva,
        focoPrimerCanal = focoPrimerCanal,
        onDialogo = onDialogo,
    )
}

/** Botón de alta de canal; en TV de dos paneles baja a la lista de canales. */
@Composable
private fun BotonNuevoCanal(
    listaActiva: ListaResumen?,
    focoPrimerCanal: FocusRequester?,
    onDialogo: (DialogoCatalogo) -> Unit,
) {
    Button(
        onClick = { onDialogo(DialogoCatalogo.NuevoCanal) },
        enabled = listaActiva != null,
        modifier =
            Modifier
                // En TV de dos paneles el Abajo desde el último control debe
                // entrar a la lista de canales: la búsqueda direccional no
                // cruza al panel derecho porque sus elementos no quedan
                // debajo, así que el destino se declara explícito.
                .then(
                    if (focoPrimerCanal == null) {
                        Modifier
                    } else {
                        Modifier.focusProperties { down = focoPrimerCanal }
                    },
                )
                // DC-11: en material3 1.3 la enforcement del mínimo táctil de
                // 48 dp está desactivada por defecto; el Button queda en 40 dp
                // si no se declara explícito.
                .heightIn(min = 48.dp)
                .testTag(TAG_NUEVO_CANAL),
    ) {
        Text(stringResource(R.string.catalogo_nuevo_canal))
    }
}

/** Alta de Fuente por documento SAF, URL o fixtures de prueba. */
@Composable
private fun AccionesImportacion(
    estado: CatalogoUiState,
    acciones: CatalogoAcciones,
    focoInicial: FocusRequester,
    onDialogo: (DialogoCatalogo) -> Unit,
) {
    val habilitado = !estado.importando && !estado.actualizando
    Button(
        onClick = acciones.onAbrirDocumento,
        enabled = habilitado,
        modifier =
            Modifier
                .focusRequester(focoInicial)
                .testTag(TAG_ABRIR_DOCUMENTO),
    ) {
        Text(stringResource(R.string.catalogo_abrir_documento))
    }
    Button(
        onClick = {
            // Reabrir el diálogo tras un resultado anterior lo deja recién
            // abierto; una importación en curso no se reinicia (E7-T7).
            acciones.onImportarUrlReiniciar()
            onDialogo(DialogoCatalogo.ImportarUrl)
        },
        enabled = habilitado,
        modifier = Modifier.testTag(TAG_IMPORTAR_URL),
    ) {
        Text(stringResource(R.string.catalogo_importar_url))
    }
    Button(
        onClick = acciones.onImportar,
        enabled = habilitado,
        modifier = Modifier.testTag(TAG_IMPORTAR),
    ) {
        Text(stringResource(R.string.catalogo_importar))
    }
}

/**
 * Acción de Actualización manual de la Lista activa (E4-T6).
 *
 * Se separa de `Controles` para mantener esa composición dentro del límite de
 * longitud y para que el resumen de la última Actualización viva junto al
 * disparador que lo produce.
 */
@Composable
private fun AccionActualizar(
    estado: CatalogoUiState,
    listaActiva: ListaResumen?,
    acciones: CatalogoAcciones,
) {
    Button(
        onClick = acciones.onActualizar,
        enabled = listaActiva != null && !estado.importando && !estado.actualizando,
        modifier = Modifier.testTag(TAG_ACTUALIZAR),
    ) {
        Text(stringResource(R.string.catalogo_actualizar))
    }
    estado.actualizacion?.let { resumen ->
        val error = resumen.error
        val texto =
            if (error != null) {
                stringResource(R.string.catalogo_actualizacion_error, error)
            } else {
                stringResource(
                    R.string.catalogo_actualizacion_ok,
                    resumen.nuevas,
                    resumen.actualizadas,
                    resumen.ausentes,
                    resumen.ambiguas,
                    resumen.observaciones,
                )
            }
        Text(
            text = texto,
            style = MaterialTheme.typography.bodyMedium,
            modifier = Modifier.testTag(TAG_RESULTADO_ACTUALIZACION),
        )
    }
}

/**
 * Acceso rápido de S11c: los últimos Canales reproducidos, en el orden
 * persistido `fecha DESC, canalId DESC`.
 *
 * Cada entrada abre el mismo diálogo de acciones que una fila del Catálogo, de
 * modo que el acceso rápido no crea una segunda ruta de interacción. Un Canal
 * sin nombre se presenta como «Sin nombre» (S14).
 */
@Composable
private fun AccesoRapido(
    recientes: List<HistorialResumen>,
    onCanal: (HistorialResumen) -> Unit,
) {
    if (recientes.isEmpty()) return
    Text(
        text = stringResource(R.string.catalogo_acceso_rapido),
        style = MaterialTheme.typography.titleSmall,
    )
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState())
                .testTag(TAG_ACCESO_RAPIDO),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        recientes.forEachIndexed { indice, reciente ->
            TextButton(
                onClick = { onCanal(reciente) },
                modifier = Modifier.testTag(PREFIJO_TAG_RECIENTE + indice),
            ) {
                Text(reciente.nombre ?: stringResource(R.string.catalogo_sin_nombre))
            }
        }
    }
}

/** Adapta una entrada del Historial al modelo que consume el diálogo de acciones. */
private fun HistorialResumen.aCanalBusqueda(): CanalBusqueda =
    CanalBusqueda(
        id = canalId,
        nombre = nombre,
        esFavorito = esFavorito,
        estaOculto = estaOculto,
    )

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun DialogoCatalogoActivo(
    dialogo: DialogoCatalogo?,
    acciones: CatalogoAcciones,
    listas: List<ListaResumen>,
    coleccionesUi: ColeccionesUiState,
    perfilesUi: PerfilesUiState,
    importacionUrl: ImportacionUrlUi?,
    importando: Boolean,
    onDialogo: (DialogoCatalogo?) -> Unit,
) {
    when (val actual = dialogo) {
        null -> Unit
        DialogoCatalogo.NuevaLista ->
            DialogoTexto(
                titulo = stringResource(R.string.catalogo_nueva_lista),
                etiqueta = stringResource(R.string.catalogo_nombre_lista),
                valorInicial = "",
                onGuardar = { nombre ->
                    acciones.onCrearLista(nombre)
                    onDialogo(null)
                },
                onCancelar = { onDialogo(null) },
            )
        is DialogoCatalogo.RenombrarLista ->
            DialogoTexto(
                titulo = stringResource(R.string.catalogo_renombrar_lista),
                etiqueta = stringResource(R.string.catalogo_nombre_lista),
                valorInicial = actual.lista.nombre,
                onGuardar = { nombre ->
                    acciones.onRenombrarLista(actual.lista.id, nombre)
                    onDialogo(null)
                },
                onCancelar = { onDialogo(null) },
            )
        is DialogoCatalogo.EliminarLista ->
            DialogoConfirmar(
                titulo = stringResource(R.string.catalogo_eliminar_lista),
                texto = stringResource(R.string.catalogo_confirmar_eliminar_lista, actual.lista.nombre),
                onConfirmar = {
                    acciones.onEliminarLista(actual.lista.id)
                    onDialogo(null)
                },
                onCancelar = { onDialogo(null) },
            )
        DialogoCatalogo.Colecciones ->
            DialogoColecciones(
                colecciones = coleccionesUi.colecciones,
                seleccionadaId = coleccionesUi.seleccionadaId,
                canales = coleccionesUi.canales,
                onSeleccionar = acciones.onSeleccionarColeccion,
                onNueva = { nombre ->
                    acciones.onCrearColeccion(nombre)
                },
                onRenombrar = { id, nombre ->
                    acciones.onRenombrarColeccion(id, nombre)
                },
                onEliminar = { id ->
                    acciones.onEliminarColeccion(id)
                },
                onSubir = { filaId -> acciones.onMoverCanalEnColeccion(filaId, -1) },
                onBajar = { filaId -> acciones.onMoverCanalEnColeccion(filaId, 1) },
                onQuitar = acciones.onQuitarCanalDeColeccion,
                onCerrar = { onDialogo(null) },
            )
        is DialogoCatalogo.AgregarAColeccion ->
            DialogoAgregarAColeccion(
                canal = actual.canal,
                colecciones = coleccionesUi.colecciones,
                onElegir = { coleccionId ->
                    acciones.onAgregarCanalAColeccion(coleccionId, actual.canal.id)
                    onDialogo(null)
                },
                onCancelar = { onDialogo(null) },
            )
        DialogoCatalogo.Perfiles ->
            DialogoPerfiles(
                perfiles = perfilesUi.perfiles,
                seleccionadoId = perfilesUi.seleccionadoId,
                listas = listas,
                listasDePerfil = perfilesUi.listas,
                onSeleccionar = acciones.onSeleccionarPerfil,
                onNueva = acciones.onCrearPerfil,
                onRenombrar = acciones.onRenombrarPerfil,
                onEliminar = acciones.onEliminarPerfil,
                onAsociar = acciones.onAsociarListaAPerfil,
                onDesasociar = acciones.onDesasociarListaDePerfil,
                onCerrar = { onDialogo(null) },
            )
        DialogoCatalogo.ImportarUrl ->
            DialogoImportarUrl(
                importacion = importacionUrl,
                importando = importando,
                onGuardar = { nombre, url -> acciones.onImportarUrl(nombre, url) },
                onCancelar = { onDialogo(null) },
            )
        DialogoCatalogo.NuevoCanal ->
            DialogoCanal(
                titulo = stringResource(R.string.catalogo_nuevo_canal),
                nombreInicial = "",
                urlInicial = "",
                onGuardar = { nombre, url ->
                    acciones.onCrearCanal(nombre, url)
                    onDialogo(null)
                },
                onCancelar = { onDialogo(null) },
            )
        is DialogoCatalogo.AccionesCanal ->
            DialogoAccionesCanal(
                canal = actual.canal,
                onReproducir = {
                    acciones.onReproducir(actual.canal.id)
                    onDialogo(null)
                },
                onFavorito = {
                    acciones.onAlternarFavorito(actual.canal.id)
                    onDialogo(null)
                },
                onOculto = {
                    acciones.onAlternarOculto(actual.canal.id)
                    onDialogo(null)
                },
                onEditar = { onDialogo(DialogoCatalogo.EditarCanal(actual.canal)) },
                onAgregarColeccion = { onDialogo(DialogoCatalogo.AgregarAColeccion(actual.canal)) },
                onSubir = {
                    acciones.onMoverCanal(actual.canal.id, -1)
                    onDialogo(null)
                },
                onBajar = {
                    acciones.onMoverCanal(actual.canal.id, 1)
                    onDialogo(null)
                },
                onEliminar = { onDialogo(DialogoCatalogo.EliminarCanal(actual.canal)) },
                onCerrar = { onDialogo(null) },
            )
        is DialogoCatalogo.EditarCanal ->
            DialogoTexto(
                titulo = stringResource(R.string.catalogo_editar),
                etiqueta = stringResource(R.string.catalogo_nombre_canal),
                valorInicial = actual.canal.nombre.orEmpty(),
                onGuardar = { nombre ->
                    acciones.onEditarNombreCanal(actual.canal.id, nombre.takeIf { it.isNotBlank() })
                    onDialogo(null)
                },
                onCancelar = { onDialogo(null) },
            )
        is DialogoCatalogo.EliminarCanal ->
            DialogoEliminarCanal(
                canal = actual.canal,
                acciones = acciones,
                onDialogo = onDialogo,
            )
        is DialogoCatalogo.Privacidad ->
            DialogoPrivacidadRama(
                dialogo = actual,
                acciones = acciones,
                onDialogo = onDialogo,
            )
    }
}

@Composable
private fun SelectorLista(
    listas: List<ListaResumen>,
    listaActiva: ListaResumen?,
    onSeleccionar: (Long) -> Unit,
    onNueva: () -> Unit,
    onRenombrar: () -> Unit,
    onEliminar: () -> Unit,
) {
    var expandido by remember { mutableStateOf(false) }
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Box {
            TextButton(
                onClick = { expandido = true },
                modifier = Modifier.testTag(TAG_LISTA_ACTIVA),
            ) {
                Text(
                    text =
                        listaActiva?.let {
                            stringResource(R.string.catalogo_lista_activa, it.nombre)
                        } ?: stringResource(R.string.catalogo_sin_lista),
                )
            }
            DropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
                listas.forEach { lista ->
                    DropdownMenuItem(
                        text = { Text(lista.nombre) },
                        onClick = {
                            onSeleccionar(lista.id)
                            expandido = false
                        },
                        modifier = Modifier.testTag(PREFIJO_TAG_LISTA + lista.id),
                    )
                }
            }
        }
        TextButton(
            onClick = onNueva,
            modifier = Modifier.testTag(TAG_NUEVA_LISTA),
        ) {
            Text(stringResource(R.string.catalogo_nueva_lista))
        }
        TextButton(
            onClick = onRenombrar,
            enabled = listaActiva != null,
            modifier = Modifier.testTag(TAG_RENOMBRAR_LISTA),
        ) {
            Text(stringResource(R.string.catalogo_renombrar_lista))
        }
        TextButton(
            onClick = onEliminar,
            enabled = listaActiva != null,
            modifier = Modifier.testTag(TAG_ELIMINAR_LISTA),
        ) {
            Text(stringResource(R.string.catalogo_eliminar_lista))
        }
    }
}

@Composable
private fun SelectorOrden(
    modo: ModoOrden,
    direccion: DireccionOrden,
    onModo: (ModoOrden) -> Unit,
    onDireccion: () -> Unit,
) {
    Row(
        modifier = Modifier.testTag(TAG_MODO),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        ModoOrden.entries.forEach { opcion ->
            FilterChip(
                selected = modo == opcion,
                onClick = { onModo(opcion) },
                label = { Text(stringResource(textoModo(opcion))) },
                modifier = Modifier.testTag(PREFIJO_TAG_MODO_OPCION + opcion.codigo),
            )
        }
        TextButton(
            onClick = onDireccion,
            modifier = Modifier.testTag(TAG_DIRECCION),
        ) {
            Text(
                text =
                    stringResource(
                        if (direccion == DireccionOrden.ASCENDENTE) {
                            R.string.catalogo_ascendente
                        } else {
                            R.string.catalogo_descendente
                        },
                    ),
            )
        }
    }
}

private fun textoModo(modo: ModoOrden): Int =
    when (modo) {
        ModoOrden.MANUAL -> R.string.catalogo_modo_manual
        ModoOrden.NOMBRE -> R.string.catalogo_modo_nombre
        ModoOrden.GRUPO -> R.string.catalogo_modo_grupo
        ModoOrden.RECIENTES -> R.string.catalogo_modo_recientes
    }

@Composable
private fun FiltrosYOrden(
    criterios: CriteriosBusqueda,
    grupos: List<GrupoOpcion>,
    onGrupo: (Long?) -> Unit,
    onFavoritos: () -> Unit,
    onTodos: () -> Unit,
    onMostrarOcultos: () -> Unit,
    onModo: (ModoOrden) -> Unit,
    onDireccion: () -> Unit,
) {
    Row(
        modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        FilterChip(
            selected = criterios.grupoId == null && !criterios.soloFavoritos,
            onClick = onTodos,
            label = { Text(stringResource(R.string.catalogo_todos)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_FILTRO_TODOS),
        )
        FilterChip(
            selected = criterios.soloFavoritos,
            onClick = onFavoritos,
            label = { Text(stringResource(R.string.catalogo_favoritos)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_FILTRO_FAVORITOS),
        )
        FilterChip(
            selected = criterios.mostrarOcultos,
            onClick = onMostrarOcultos,
            label = { Text(stringResource(R.string.catalogo_mostrar_ocultos)) },
            modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_FILTRO_OCULTOS),
        )
        grupos.forEach { grupo ->
            FilterChip(
                selected = criterios.grupoId == grupo.id,
                onClick = { onGrupo(if (criterios.grupoId == grupo.id) null else grupo.id) },
                label = { Text(grupo.nombre) },
                modifier = Modifier.heightIn(min = 48.dp).testTag(PREFIJO_TAG_GRUPO + grupo.id),
            )
        }
        SelectorOrden(
            modo = criterios.modo,
            direccion = criterios.direccion,
            onModo = onModo,
            onDireccion = onDireccion,
        )
    }
}

@Composable
private fun Resultados(
    modo: ModoOrden,
    resultados: LazyPagingItems<CanalBusqueda>,
    hayFiltros: Boolean,
    focoPrimerCanal: FocusRequester?,
    onCanal: (CanalBusqueda) -> Unit,
    onReintentar: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val refresco = resultados.loadState.refresh
    when {
        refresco is LoadState.Loading && resultados.itemCount == 0 -> {
            LinearProgressIndicator(
                modifier = modifier.fillMaxWidth().testTag(TAG_CARGANDO),
            )
        }
        refresco is LoadState.Error && resultados.itemCount == 0 -> {
            Column(modifier = modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    text = stringResource(R.string.catalogo_error_carga),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_ERROR_CARGA),
                )
                TextButton(
                    onClick = onReintentar,
                    modifier = Modifier.testTag(TAG_REINTENTAR),
                ) {
                    Text(stringResource(R.string.catalogo_reintentar))
                }
            }
        }
        resultados.itemCount == 0 -> {
            Text(
                text =
                    stringResource(
                        if (hayFiltros) R.string.catalogo_sin_resultados else R.string.catalogo_vacio,
                    ),
                style = MaterialTheme.typography.bodyMedium,
                modifier =
                    modifier.testTag(
                        if (hayFiltros) TAG_SIN_RESULTADOS else TAG_CATALOGO_VACIO,
                    ),
            )
        }
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxWidth().testTag(TAG_RESULTADOS),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = resultados.itemCount,
                    key = { indice -> resultados.peek(indice)?.id ?: "indice-$indice" },
                ) { indice ->
                    val canal = resultados[indice]
                    if (canal != null) {
                        CabeceraGrupoSiCorresponde(
                            modo = modo,
                            indice = indice,
                            canal = canal,
                            anterior = resultados.peek((indice - 1).coerceAtLeast(0)),
                        )
                        CanalFila(
                            canal = canal,
                            indice = indice,
                            focoSolicitado = if (indice == 0) focoPrimerCanal else null,
                            alSeleccionar = { onCanal(canal) },
                        )
                    }
                }
                if (resultados.loadState.append is LoadState.Loading) {
                    item {
                        LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                    }
                }
            }
        }
    }
}

@Composable
private fun CabeceraGrupoSiCorresponde(
    modo: ModoOrden,
    indice: Int,
    canal: CanalBusqueda,
    anterior: CanalBusqueda?,
) {
    if (modo == ModoOrden.GRUPO &&
        (indice == 0 || anterior?.grupoNombre != canal.grupoNombre)
    ) {
        CabeceraGrupo(canal.grupoNombre)
    }
}

@Composable
private fun CabeceraGrupo(grupoNombre: String?) {
    val texto = grupoNombre ?: stringResource(R.string.catalogo_sin_grupo)
    Text(
        text = texto,
        style = MaterialTheme.typography.titleSmall,
        modifier =
            Modifier
                .fillMaxWidth()
                .padding(top = 8.dp)
                .testTag(PREFIJO_TAG_SECCION_GRUPO + (grupoNombre ?: "sin-grupo")),
    )
}

@Composable
private fun CanalFila(
    canal: CanalBusqueda,
    indice: Int,
    focoSolicitado: FocusRequester?,
    alSeleccionar: () -> Unit,
) {
    var enfocado by remember { mutableStateOf(false) }
    val nombre = canal.nombre ?: stringResource(R.string.catalogo_sin_nombre)
    Row(
        modifier =
            Modifier
                .fillMaxWidth()
                // DC-11: el área de foco en TV mide al menos 56 × 56 dp.
                .heightIn(min = 56.dp)
                .then(
                    if (focoSolicitado == null) {
                        Modifier
                    } else {
                        Modifier.focusRequester(focoSolicitado)
                    },
                )
                .onFocusChanged { enfocado = it.isFocused }
                .onKeyEvent { evento ->
                    if (evento.type == KeyEventType.KeyUp &&
                        (evento.key == Key.DirectionCenter || evento.key == Key.Enter)
                    ) {
                        alSeleccionar()
                        true
                    } else {
                        false
                    }
                }
                .focusable()
                .pointerInput(alSeleccionar) {
                    detectTapGestures { alSeleccionar() }
                }
                .border(
                    width = if (enfocado) 2.dp else 1.dp,
                    color =
                        if (enfocado) {
                            MaterialTheme.colorScheme.primary
                        } else {
                            MaterialTheme.colorScheme.outlineVariant
                        },
                    shape = RoundedCornerShape(8.dp),
                )
                .padding(horizontal = 12.dp, vertical = 4.dp)
                .testTag(PREFIJO_TAG_CANAL + indice)
                .semantics(mergeDescendants = true) {
                    contentDescription =
                        if (canal.esFavorito) {
                            "$nombre, favorito"
                        } else {
                            nombre
                        }
                    onClick(label = null) {
                        alSeleccionar()
                        true
                    }
                },
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            text = nombre,
            style = MaterialTheme.typography.titleMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // DC-11: el relleno vertical lleva la altura natural de la fila
            // por encima del mínimo de 56 dp sin depender de la coerción de
            // heightIn en el emulador.
            modifier = Modifier.weight(1f).padding(vertical = 16.dp),
        )
        if (canal.esFavorito) {
            Text(
                text = "★",
                style = MaterialTheme.typography.titleMedium,
                modifier = Modifier.padding(start = 8.dp).clearAndSetSemantics {},
            )
        }
        if (canal.estaOculto) {
            Text(
                text = stringResource(R.string.catalogo_oculto),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.padding(start = 8.dp),
            )
        }
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun DialogoColecciones(
    colecciones: List<ColeccionResumen>,
    seleccionadaId: Long?,
    canales: List<ColeccionCanalResumen>,
    onSeleccionar: (Long?) -> Unit,
    onNueva: (String) -> Unit,
    onRenombrar: (Long, String) -> Unit,
    onEliminar: (Long) -> Unit,
    onSubir: (Long) -> Unit,
    onBajar: (Long) -> Unit,
    onQuitar: (Long) -> Unit,
    onCerrar: () -> Unit,
) {
    val seleccionada = colecciones.firstOrNull { it.id == seleccionadaId }
    var mostrarNueva by remember { mutableStateOf(false) }
    var mostrarRenombrar by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.catalogo_colecciones)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .width(380.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { mostrarNueva = true },
                        modifier = Modifier.testTag(TAG_NUEVA_COLECCION),
                    ) {
                        Text(stringResource(R.string.catalogo_nueva_coleccion))
                    }
                    TextButton(
                        onClick = { mostrarRenombrar = true },
                        enabled = seleccionada != null,
                        modifier = Modifier.testTag(TAG_RENOMBRAR_COLECCION),
                    ) {
                        Text(stringResource(R.string.catalogo_renombrar_coleccion))
                    }
                    TextButton(
                        onClick = { seleccionada?.let { onEliminar(it.id) } },
                        enabled = seleccionada != null,
                        modifier = Modifier.testTag(TAG_ELIMINAR_COLECCION),
                    ) {
                        Text(stringResource(R.string.catalogo_eliminar_coleccion))
                    }
                }
                Box {
                    var expandido by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { expandido = true },
                        modifier = Modifier.testTag(TAG_COLECCION_ACTIVA),
                    ) {
                        Text(
                            seleccionada?.nombre
                                ?: stringResource(R.string.catalogo_sin_coleccion),
                        )
                    }
                    DropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
                        colecciones.forEach { coleccion ->
                            DropdownMenuItem(
                                text = { Text(coleccion.nombre) },
                                onClick = {
                                    onSeleccionar(coleccion.id)
                                    expandido = false
                                },
                                modifier = Modifier.testTag(PREFIJO_TAG_COLECCION + coleccion.id),
                            )
                        }
                    }
                }
                if (colecciones.isEmpty()) {
                    Text(stringResource(R.string.catalogo_sin_colecciones))
                } else if (seleccionada == null) {
                    Text(stringResource(R.string.catalogo_seleccionar_coleccion))
                } else {
                    Text(
                        text = stringResource(R.string.catalogo_canales_coleccion),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (canales.isEmpty()) {
                        Text(stringResource(R.string.catalogo_coleccion_vacia))
                    }
                    canales.forEach { fila ->
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Text(
                                text = fila.nombre ?: stringResource(R.string.catalogo_sin_nombre),
                                modifier =
                                    Modifier
                                        .weight(1f)
                                        .testTag(PREFIJO_TAG_COLECCION_CANAL + fila.id),
                            )
                            TextButton(
                                onClick = { onSubir(fila.id) },
                                modifier = Modifier.testTag(TAG_COLECCION_CANAL_SUBIR + fila.id),
                            ) {
                                Text(stringResource(R.string.catalogo_subir))
                            }
                            TextButton(
                                onClick = { onBajar(fila.id) },
                                modifier = Modifier.testTag(TAG_COLECCION_CANAL_BAJAR + fila.id),
                            ) {
                                Text(stringResource(R.string.catalogo_bajar))
                            }
                            TextButton(
                                onClick = { onQuitar(fila.id) },
                                modifier = Modifier.testTag(TAG_COLECCION_CANAL_QUITAR + fila.id),
                            ) {
                                Text(stringResource(R.string.catalogo_quitar))
                            }
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCerrar,
                modifier = Modifier.testTag(TAG_ACCION_CERRAR),
            ) {
                Text(stringResource(R.string.catalogo_cerrar))
            }
        },
    )
    if (mostrarNueva) {
        DialogoTexto(
            titulo = stringResource(R.string.catalogo_nueva_coleccion),
            etiqueta = stringResource(R.string.catalogo_nombre_coleccion),
            valorInicial = "",
            onGuardar = { nombre ->
                onNueva(nombre)
                mostrarNueva = false
            },
            onCancelar = { mostrarNueva = false },
        )
    }
    if (mostrarRenombrar && seleccionada != null) {
        DialogoTexto(
            titulo = stringResource(R.string.catalogo_renombrar_coleccion),
            etiqueta = stringResource(R.string.catalogo_nombre_coleccion),
            valorInicial = seleccionada.nombre,
            onGuardar = { nombre ->
                onRenombrar(seleccionada.id, nombre)
                mostrarRenombrar = false
            },
            onCancelar = { mostrarRenombrar = false },
        )
    }
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun DialogoAgregarAColeccion(
    canal: CanalBusqueda,
    colecciones: List<ColeccionResumen>,
    onElegir: (Long) -> Unit,
    onCancelar: () -> Unit,
) {
    val nombre = canal.nombre ?: stringResource(R.string.catalogo_sin_nombre)
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(stringResource(R.string.catalogo_agregar_coleccion_titulo, nombre)) },
        text = {
            Column(
                modifier = Modifier.width(320.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                if (colecciones.isEmpty()) {
                    Text(stringResource(R.string.catalogo_sin_colecciones))
                }
                colecciones.forEach { coleccion ->
                    TextButton(
                        onClick = { onElegir(coleccion.id) },
                        modifier =
                            Modifier
                                .fillMaxWidth()
                                .testTag(TAG_AGREGAR_A_COLECCION + coleccion.id),
                    ) {
                        Text(coleccion.nombre)
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                modifier = Modifier.testTag(TAG_DIALOGO_CANCELAR),
            ) {
                Text(stringResource(R.string.catalogo_cancelar))
            }
        },
    )
}

@Suppress("LongMethod", "LongParameterList")
@Composable
private fun DialogoPerfiles(
    perfiles: List<PerfilResumen>,
    seleccionadoId: Long?,
    listas: List<ListaResumen>,
    listasDePerfil: List<Long>,
    onSeleccionar: (Long?) -> Unit,
    onNueva: (String) -> Unit,
    onRenombrar: (Long, String) -> Unit,
    onEliminar: (Long) -> Unit,
    onAsociar: (Long, Long) -> Unit,
    onDesasociar: (Long, Long) -> Unit,
    onCerrar: () -> Unit,
) {
    val seleccionado = perfiles.firstOrNull { it.id == seleccionadoId }
    var mostrarNuevo by remember { mutableStateOf(false) }
    var mostrarRenombrar by remember { mutableStateOf(false) }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.catalogo_perfiles)) },
        text = {
            Column(
                modifier =
                    Modifier
                        .width(380.dp)
                        .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    TextButton(
                        onClick = { mostrarNuevo = true },
                        modifier = Modifier.testTag(TAG_NUEVO_PERFIL),
                    ) {
                        Text(stringResource(R.string.catalogo_nuevo_perfil))
                    }
                    TextButton(
                        onClick = { mostrarRenombrar = true },
                        enabled = seleccionado != null,
                        modifier = Modifier.testTag(TAG_RENOMBRAR_PERFIL),
                    ) {
                        Text(stringResource(R.string.catalogo_renombrar_perfil))
                    }
                    TextButton(
                        onClick = { seleccionado?.let { onEliminar(it.id) } },
                        enabled = seleccionado != null,
                        modifier = Modifier.testTag(TAG_ELIMINAR_PERFIL),
                    ) {
                        Text(stringResource(R.string.catalogo_eliminar_perfil))
                    }
                }
                Box {
                    var expandido by remember { mutableStateOf(false) }
                    TextButton(
                        onClick = { expandido = true },
                        modifier = Modifier.testTag(TAG_PERFIL_ACTIVO),
                    ) {
                        Text(
                            seleccionado?.nombre
                                ?: stringResource(R.string.catalogo_sin_perfil),
                        )
                    }
                    DropdownMenu(expanded = expandido, onDismissRequest = { expandido = false }) {
                        perfiles.forEach { perfil ->
                            DropdownMenuItem(
                                text = { Text(perfil.nombre) },
                                onClick = {
                                    onSeleccionar(perfil.id)
                                    expandido = false
                                },
                                modifier = Modifier.testTag(PREFIJO_TAG_PERFIL + perfil.id),
                            )
                        }
                    }
                }
                if (perfiles.isEmpty()) {
                    Text(stringResource(R.string.catalogo_sin_perfiles))
                } else if (seleccionado == null) {
                    Text(stringResource(R.string.catalogo_seleccionar_perfil))
                } else {
                    Text(
                        text = stringResource(R.string.catalogo_listas_perfil),
                        style = MaterialTheme.typography.titleSmall,
                    )
                    if (listas.isEmpty()) {
                        Text(stringResource(R.string.catalogo_sin_listas))
                    }
                    listas.forEach { lista ->
                        val asociada = listasDePerfil.contains(lista.id)
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically,
                        ) {
                            Checkbox(
                                checked = asociada,
                                onCheckedChange = { marcada ->
                                    if (marcada) {
                                        onAsociar(seleccionado.id, lista.id)
                                    } else {
                                        onDesasociar(seleccionado.id, lista.id)
                                    }
                                },
                                modifier = Modifier.testTag(PREFIJO_TAG_PERFIL_LISTA + lista.id),
                            )
                            Text(lista.nombre)
                        }
                    }
                }
            }
        },
        confirmButton = {
            TextButton(
                onClick = onCerrar,
                modifier = Modifier.testTag(TAG_ACCION_CERRAR),
            ) {
                Text(stringResource(R.string.catalogo_cerrar))
            }
        },
    )
    if (mostrarNuevo) {
        DialogoTexto(
            titulo = stringResource(R.string.catalogo_nuevo_perfil),
            etiqueta = stringResource(R.string.catalogo_nombre_perfil),
            valorInicial = "",
            onGuardar = { nombre ->
                onNueva(nombre)
                mostrarNuevo = false
            },
            onCancelar = { mostrarNuevo = false },
        )
    }
    if (mostrarRenombrar && seleccionado != null) {
        DialogoTexto(
            titulo = stringResource(R.string.catalogo_renombrar_perfil),
            etiqueta = stringResource(R.string.catalogo_nombre_perfil),
            valorInicial = seleccionado.nombre,
            onGuardar = { nombre ->
                onRenombrar(seleccionado.id, nombre)
                mostrarRenombrar = false
            },
            onCancelar = { mostrarRenombrar = false },
        )
    }
}

@Composable
private fun DialogoTexto(
    titulo: String,
    etiqueta: String,
    valorInicial: String,
    onGuardar: (String) -> Unit,
    onCancelar: () -> Unit,
) {
    var valor by remember(valorInicial) { mutableStateOf(valorInicial) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo) },
        text = {
            OutlinedTextField(
                value = valor,
                onValueChange = { valor = it },
                label = { Text(etiqueta) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TAG_DIALOGO_NOMBRE),
            )
        },
        confirmButton = {
            TextButton(
                onClick = { onGuardar(valor) },
                enabled = valor.isNotBlank(),
                modifier = Modifier.testTag(TAG_DIALOGO_GUARDAR),
            ) {
                Text(stringResource(R.string.catalogo_guardar))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                modifier = Modifier.testTag(TAG_DIALOGO_CANCELAR),
            ) {
                Text(stringResource(R.string.catalogo_cancelar))
            }
        },
    )
}

@Composable
private fun DialogoCanal(
    titulo: String,
    nombreInicial: String,
    urlInicial: String,
    onGuardar: (String?, String) -> Unit,
    onCancelar: () -> Unit,
) {
    var nombre by remember(nombreInicial) { mutableStateOf(nombreInicial) }
    var url by remember(urlInicial) { mutableStateOf(urlInicial) }
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text(stringResource(R.string.catalogo_nombre_canal)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_DIALOGO_NOMBRE),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.catalogo_url_canal)) },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_DIALOGO_URL),
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = { onGuardar(nombre.takeIf { it.isNotBlank() }, url) },
                enabled = url.isNotBlank(),
                modifier = Modifier.testTag(TAG_DIALOGO_GUARDAR),
            ) {
                Text(stringResource(R.string.catalogo_guardar))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                modifier = Modifier.testTag(TAG_DIALOGO_CANCELAR),
            ) {
                Text(stringResource(R.string.catalogo_cancelar))
            }
        },
    )
}

@Composable
private fun DialogoConfirmar(
    titulo: String,
    texto: String,
    etiquetaConfirmar: String = stringResource(R.string.catalogo_eliminar),
    tagConfirmar: String = TAG_CONFIRMAR_ELIMINAR,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(titulo) },
        text = { Text(texto) },
        confirmButton = {
            TextButton(
                onClick = onConfirmar,
                modifier = Modifier.testTag(tagConfirmar),
            ) {
                Text(etiquetaConfirmar)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                modifier = Modifier.testTag(TAG_DIALOGO_CANCELAR),
            ) {
                Text(stringResource(R.string.catalogo_cancelar))
            }
        },
    )
}

/**
 * Confirmación de borrado de un Canal: el nombre sin texto se presenta como
 * «Sin nombre» (S14).
 */
@Composable
private fun DialogoEliminarCanal(
    canal: CanalBusqueda,
    acciones: CatalogoAcciones,
    onDialogo: (DialogoCatalogo?) -> Unit,
) {
    DialogoConfirmar(
        titulo = stringResource(R.string.catalogo_eliminar),
        texto =
            stringResource(
                R.string.catalogo_confirmar_eliminar_canal,
                canal.nombre ?: stringResource(R.string.catalogo_sin_nombre),
            ),
        onConfirmar = {
            acciones.onEliminarCanal(canal.id)
            onDialogo(null)
        },
        onCancelar = { onDialogo(null) },
    )
}

/**
 * Rama de Privacidad de S47: sin confirmación muestra el menú con los dos
 * borrados; con confirmación pide la confirmación del borrado elegido. Cada
 * acción ejecuta su borrado y conserva la otra tabla (S47a/S47b).
 */
@Composable
private fun DialogoPrivacidadRama(
    dialogo: DialogoCatalogo.Privacidad,
    acciones: CatalogoAcciones,
    onDialogo: (DialogoCatalogo?) -> Unit,
) {
    val confirmar = dialogo.confirmarHistorial
    if (confirmar == null) {
        DialogoPrivacidad(
            onBorrarHistorial = { onDialogo(DialogoCatalogo.Privacidad(confirmarHistorial = true)) },
            onBorrarFavoritos = { onDialogo(DialogoCatalogo.Privacidad(confirmarHistorial = false)) },
            onCancelar = { onDialogo(null) },
        )
    } else {
        val titulo =
            if (confirmar) {
                R.string.catalogo_confirmar_historial_titulo
            } else {
                R.string.catalogo_confirmar_favoritos_titulo
            }
        val texto =
            if (confirmar) {
                R.string.catalogo_confirmar_historial_texto
            } else {
                R.string.catalogo_confirmar_favoritos_texto
            }
        DialogoConfirmar(
            titulo = stringResource(titulo),
            texto = stringResource(texto),
            etiquetaConfirmar = stringResource(R.string.catalogo_borrar),
            tagConfirmar = TAG_CONFIRMAR_BORRAR,
            onConfirmar = {
                if (confirmar) {
                    acciones.onBorrarHistorial()
                } else {
                    acciones.onBorrarFavoritos()
                }
                onDialogo(null)
            },
            onCancelar = { onDialogo(null) },
        )
    }
}

/**
 * Diálogo de Privacidad de S47: dos borrados independientes con conservación
 * cruzada. Abrirlo no borra nada; cada acción pide su propia confirmación.
 */
@Composable
private fun DialogoPrivacidad(
    onBorrarHistorial: () -> Unit,
    onBorrarFavoritos: () -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        title = { Text(stringResource(R.string.catalogo_privacidad_titulo)) },
        text = { Text(stringResource(R.string.catalogo_privacidad_texto)) },
        confirmButton = {
            Column {
                TextButton(
                    onClick = onBorrarHistorial,
                    modifier = Modifier.testTag(TAG_BORRAR_HISTORIAL),
                ) {
                    Text(stringResource(R.string.catalogo_borrar_historial))
                }
                TextButton(
                    onClick = onBorrarFavoritos,
                    modifier = Modifier.testTag(TAG_BORRAR_FAVORITOS),
                ) {
                    Text(stringResource(R.string.catalogo_borrar_favoritos))
                }
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                modifier = Modifier.testTag(TAG_DIALOGO_CANCELAR),
            ) {
                Text(stringResource(R.string.catalogo_cancelar))
            }
        },
    )
}

@Composable
private fun DialogoAccionesCanal(
    canal: CanalBusqueda,
    onReproducir: () -> Unit,
    onFavorito: () -> Unit,
    onOculto: () -> Unit,
    onEditar: () -> Unit,
    onAgregarColeccion: () -> Unit,
    onSubir: () -> Unit,
    onBajar: () -> Unit,
    onEliminar: () -> Unit,
    onCerrar: () -> Unit,
) {
    val nombre = canal.nombre ?: stringResource(R.string.catalogo_sin_nombre)
    // En TV el diálogo no toma foco por sí solo y la pantalla queda sin foco
    // visible al cambiar de superficie; la primera acción (Reproducir) lo
    // toma al componerse, con el mismo patrón del foco inicial del catálogo
    // y del reproductor (E7-T1).
    val enfoqueReproducir = remember { FocusRequester() }
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.catalogo_acciones_canal, nombre)) },
        text = {
            Column(
                modifier = Modifier.width(320.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                LaunchedEffect(Unit) { enfoqueReproducir.requestFocus() }
                TextButton(
                    onClick = onReproducir,
                    modifier =
                        Modifier
                            .fillMaxWidth()
                            .focusRequester(enfoqueReproducir)
                            .testTag(TAG_ACCION_REPRODUCIR),
                ) {
                    Text(stringResource(R.string.catalogo_reproducir))
                }
                TextButton(
                    onClick = onFavorito,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_FAVORITO),
                ) {
                    Text(
                        stringResource(
                            if (canal.esFavorito) R.string.catalogo_quitar_favorito else R.string.catalogo_favorito,
                        ),
                    )
                }
                TextButton(
                    onClick = onOculto,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_OCULTO),
                ) {
                    Text(
                        stringResource(
                            if (canal.estaOculto) R.string.catalogo_mostrar else R.string.catalogo_ocultar,
                        ),
                    )
                }
                TextButton(
                    onClick = onEditar,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_EDITAR),
                ) {
                    Text(stringResource(R.string.catalogo_editar))
                }
                TextButton(
                    onClick = onAgregarColeccion,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_AGREGAR_COLECCION),
                ) {
                    Text(stringResource(R.string.catalogo_agregar_coleccion))
                }
                TextButton(
                    onClick = onSubir,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_SUBIR),
                ) {
                    Text(stringResource(R.string.catalogo_subir))
                }
                TextButton(
                    onClick = onBajar,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_BAJAR),
                ) {
                    Text(stringResource(R.string.catalogo_bajar))
                }
                TextButton(
                    onClick = onEliminar,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_ELIMINAR),
                ) {
                    Text(stringResource(R.string.catalogo_eliminar))
                }
                TextButton(
                    onClick = onCerrar,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_ACCION_CERRAR),
                ) {
                    Text(stringResource(R.string.catalogo_cerrar))
                }
            }
        },
        confirmButton = {},
        modifier = Modifier.testTag(TAG_ACCIONES_CANAL),
    )
}

/** Diálogo abierto en la pantalla de catálogo. */
private sealed interface DialogoCatalogo {
    data object NuevaLista : DialogoCatalogo

    data class RenombrarLista(val lista: ListaResumen) : DialogoCatalogo

    data class EliminarLista(val lista: ListaResumen) : DialogoCatalogo

    data object Colecciones : DialogoCatalogo

    data class AgregarAColeccion(val canal: CanalBusqueda) : DialogoCatalogo

    data object Perfiles : DialogoCatalogo

    data object ImportarUrl : DialogoCatalogo

    data object NuevoCanal : DialogoCatalogo

    /**
     * S47: diálogo de Privacidad con los dos borrados independientes.
     * `confirmarHistorial` nulo muestra el menú de borrado; no nulo pide la
     * confirmación del borrado correspondiente (S47a o S47b).
     */
    data class Privacidad(val confirmarHistorial: Boolean? = null) : DialogoCatalogo

    data class AccionesCanal(val canal: CanalBusqueda) : DialogoCatalogo

    data class EditarCanal(val canal: CanalBusqueda) : DialogoCatalogo

    data class EliminarCanal(val canal: CanalBusqueda) : DialogoCatalogo
}
