package com.copernicotv.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.copernicotv.app.R
import com.copernicotv.app.data.CanalBusqueda
import com.copernicotv.app.data.CriteriosBusqueda
import com.copernicotv.app.data.GrupoOpcion

/** Etiqueta del disparador de importación del canario. */
const val TAG_IMPORTAR = "importar-fixture"

/** Etiqueta del disparador de selección de documento SAF. */
const val TAG_ABRIR_DOCUMENTO = "abrir-documento"

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

/** Prefijo de las etiquetas de filtro por Grupo. */
const val PREFIJO_TAG_GRUPO = "filtro-grupo-"

/** Etiqueta del estado sin resultados. */
const val TAG_SIN_RESULTADOS = "sin-resultados"

/** Etiqueta del error de carga paginada. */
const val TAG_ERROR_CARGA = "error-carga-catalogo"

/**
 * Pantalla del canario: importar, buscar y filtrar el catálogo con D-pad.
 *
 * La lista visible es la consulta paginada de la Lista activa; `estado.canales`
 * se conserva para el camino de persistencia existente. Cada resultado es un
 * nodo enfocable para que el recorrido con D-pad sea real.
 */
@Composable
fun CatalogoScreen(
    estado: CatalogoUiState,
    criterios: CriteriosBusqueda,
    grupos: List<GrupoOpcion>,
    resultados: LazyPagingItems<CanalBusqueda>,
    onImportar: () -> Unit,
    onAbrirDocumento: () -> Unit,
    onTexto: (String) -> Unit,
    onGrupo: (Long?) -> Unit,
    onFavoritos: () -> Unit,
    onTodos: () -> Unit,
    onLimpiar: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.catalogo_titulo),
                style = MaterialTheme.typography.headlineMedium,
            )
            Button(
                onClick = onAbrirDocumento,
                enabled = !estado.importando,
                modifier = Modifier.testTag(TAG_ABRIR_DOCUMENTO),
            ) {
                Text(stringResource(R.string.catalogo_abrir_documento))
            }
            Button(
                onClick = onImportar,
                enabled = !estado.importando,
                modifier = Modifier.testTag(TAG_IMPORTAR),
            ) {
                Text(stringResource(R.string.catalogo_importar))
            }
            estado.mensaje?.let { codigo ->
                Text(
                    text = stringResource(R.string.catalogo_error, codigo),
                    style = MaterialTheme.typography.bodyMedium,
                )
            }
            OutlinedTextField(
                value = criterios.texto,
                onValueChange = onTexto,
                label = { Text(stringResource(R.string.catalogo_buscar)) },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag(TAG_BUSCAR),
            )
            if (criterios.texto.isNotEmpty() || criterios.grupoId != null || criterios.soloFavoritos) {
                TextButton(
                    onClick = onLimpiar,
                    modifier = Modifier.testTag(TAG_LIMPIAR),
                ) {
                    Text(stringResource(R.string.catalogo_limpiar))
                }
            }
            Filtros(
                criterios = criterios,
                grupos = grupos,
                onGrupo = onGrupo,
                onFavoritos = onFavoritos,
                onTodos = onTodos,
            )
            Resultados(
                resultados = resultados,
                modifier = Modifier.weight(1f),
            )
        }
    }
}

@Composable
private fun Filtros(
    criterios: CriteriosBusqueda,
    grupos: List<GrupoOpcion>,
    onGrupo: (Long?) -> Unit,
    onFavoritos: () -> Unit,
    onTodos: () -> Unit,
) {
    LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        item {
            FilterChip(
                selected = criterios.grupoId == null && !criterios.soloFavoritos,
                onClick = onTodos,
                label = { Text(stringResource(R.string.catalogo_todos)) },
                modifier = Modifier.testTag(TAG_FILTRO_TODOS),
            )
        }
        item {
            FilterChip(
                selected = criterios.soloFavoritos,
                onClick = onFavoritos,
                label = { Text(stringResource(R.string.catalogo_favoritos)) },
                modifier = Modifier.testTag(TAG_FILTRO_FAVORITOS),
            )
        }
        items(grupos, key = { grupo -> grupo.id }) { grupo ->
            FilterChip(
                selected = criterios.grupoId == grupo.id,
                onClick = { onGrupo(if (criterios.grupoId == grupo.id) null else grupo.id) },
                label = { Text(grupo.nombre) },
                modifier = Modifier.testTag(PREFIJO_TAG_GRUPO + grupo.id),
            )
        }
    }
}

@Composable
private fun Resultados(
    resultados: LazyPagingItems<CanalBusqueda>,
    modifier: Modifier = Modifier,
) {
    val refresco = resultados.loadState.refresh
    when {
        refresco is LoadState.Loading && resultados.itemCount == 0 -> {
            LinearProgressIndicator(modifier = modifier.fillMaxWidth())
        }
        refresco is LoadState.Error && resultados.itemCount == 0 -> {
            Text(
                text = stringResource(R.string.catalogo_error_carga),
                style = MaterialTheme.typography.bodyMedium,
                modifier = modifier.testTag(TAG_ERROR_CARGA),
            )
        }
        resultados.itemCount == 0 -> {
            Text(
                text = stringResource(R.string.catalogo_sin_resultados),
                style = MaterialTheme.typography.bodyMedium,
                modifier = modifier.testTag(TAG_SIN_RESULTADOS),
            )
        }
        else -> {
            LazyColumn(
                modifier = modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                items(
                    count = resultados.itemCount,
                    key = { indice -> resultados.peek(indice)?.id ?: "indice-$indice" },
                ) { indice ->
                    val canal = resultados[indice]
                    if (canal != null) {
                        Text(
                            text = canal.nombre ?: stringResource(R.string.catalogo_sin_nombre),
                            style = MaterialTheme.typography.titleMedium,
                            modifier =
                                Modifier
                                    .fillMaxWidth()
                                    .focusable()
                                    .padding(12.dp)
                                    .testTag(PREFIJO_TAG_CANAL + indice),
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
