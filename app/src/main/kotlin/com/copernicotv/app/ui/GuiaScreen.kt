package com.copernicotv.app.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.paging.LoadState
import androidx.paging.compose.LazyPagingItems
import com.copernicotv.app.R
import com.copernicotv.app.data.AsociacionVista
import com.copernicotv.app.data.CanalGuia
import com.copernicotv.app.data.CanalGuiaFila
import com.copernicotv.app.data.CanalOpcion
import com.copernicotv.app.data.ProgramaGuia
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Etiqueta del botón que vuelve al catálogo. */
const val TAG_VOLVER_GUIA = "volver-guia"

/** Etiqueta del indicador de carga de la guía. */
const val TAG_GUIA_CARGANDO = "guia-cargando"

/** Etiqueta del error de carga de la guía. */
const val TAG_ERROR_GUIA = "error-guia"

/** Etiqueta del estado sin fuentes de guía. */
const val TAG_SIN_FUENTES_GUIA = "sin-fuentes-guia"

/** Etiqueta del estado sin canales con guía. */
const val TAG_SIN_GUIA = "sin-guia"

/** Etiqueta del estado sin programas en la ventana. */
const val TAG_SIN_PROGRAMAS_GUIA = "sin-programas-guia"

/** Etiqueta de una fila de Canal. */
const val TAG_CANAL_GUIA = "canal-guia"

/** Etiqueta de una fila de Programa. */
const val TAG_PROGRAMA_GUIA = "programa-guia"

/** Etiqueta del diálogo de detalle. */
const val TAG_DETALLE_GUIA = "detalle-guia"

/** Etiqueta del diálogo de edición de Asociación. */
const val TAG_EDITAR_ASOCIACION = "editar-asociacion"

/** Etiqueta de una Asociación pendiente. */
const val TAG_ASOCIACION_PENDIENTE = "asociacion-pendiente"

/** Etiqueta de una fila de Canal concreta. */
fun tagCanalGuia(canalId: Long): String = "$TAG_CANAL_GUIA-$canalId"

/** Etiqueta de un Programa concreto. */
fun tagProgramaGuia(programaId: Long): String = "$TAG_PROGRAMA_GUIA-$programaId"

/** Etiqueta de una Asociación pendiente concreta. */
fun tagAsociacionPendiente(id: Long): String = "$TAG_ASOCIACION_PENDIENTE-$id"

/**
 * Pantalla de Guía (E5-T5).
 *
 * Lista actual/siguiente por Canal (S25). Al abrir un Canal muestra la vista
 * temporal paginada (S26). El detalle presenta título, inicio, fin y
 * descripción en hora local (S28). Las Asociaciones pendientes se resuelven sin
 * reasignar programas automáticamente (S27d/S27e).
 */
@Composable
fun GuiaScreen(
    estado: GuiaUiState,
    programas: LazyPagingItems<ProgramaGuia>,
    onSeleccionarFuente: (Long) -> Unit,
    onAbrirCanal: (CanalGuia) -> Unit,
    onVolverAlListado: () -> Unit,
    onAbrirDetalle: (ProgramaGuia) -> Unit,
    onCerrarDetalle: () -> Unit,
    onConfirmarAsociacion: (Long) -> Unit,
    onEditarAsociacion: (Long, Long) -> Unit,
    onRevocarAsociacion: (Long) -> Unit,
    onVolver: () -> Unit,
) {
    var editarId by remember { mutableStateOf<Long?>(null) }
    Surface(
        modifier =
            Modifier
                // E7-T3 (edge-to-edge): el contenido respeta las barras del
                // sistema en vez de quedar debajo de ellas.
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(modifier = Modifier.fillMaxSize().padding(16.dp)) {
            CabeceraGuia(onVolver = onVolver)
            SelectorFuentes(estado = estado, onSeleccionarFuente = onSeleccionarFuente)
            MensajesGuia(estado = estado)
            if (estado.pendientes.isNotEmpty()) {
                SeccionPendientes(
                    pendientes = estado.pendientes,
                    onConfirmar = onConfirmarAsociacion,
                    onEditar = { editarId = it },
                    onRevocar = onRevocarAsociacion,
                )
            }
            val seleccionado = estado.canalSeleccionado
            if (seleccionado == null) {
                ListaCanales(canales = estado.canales, onAbrirCanal = onAbrirCanal)
            } else {
                VistaCanal(
                    canal = seleccionado,
                    programas = programas,
                    onVolverAlListado = onVolverAlListado,
                    onAbrirDetalle = onAbrirDetalle,
                )
            }
        }
    }
    estado.detalle?.let { programa ->
        DialogoDetalle(programa = programa, onCerrar = onCerrarDetalle)
    }
    editarId?.let { id ->
        DialogoEditarAsociacion(
            canales = estado.canalesCatalogo,
            onElegir = { canalId ->
                onEditarAsociacion(id, canalId)
                editarId = null
            },
            onCancelar = { editarId = null },
        )
    }
}

@Composable
private fun CabeceraGuia(onVolver: () -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Button(
            onClick = onVolver,
            modifier = Modifier.testTag(TAG_VOLVER_GUIA),
        ) {
            Text(stringResource(R.string.guia_volver))
        }
        Text(
            text = stringResource(R.string.guia_titulo),
            style = MaterialTheme.typography.headlineMedium,
        )
    }
}

@Composable
private fun SelectorFuentes(
    estado: GuiaUiState,
    onSeleccionarFuente: (Long) -> Unit,
) {
    if (estado.fuentes.isEmpty()) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        estado.fuentes.forEach { fuente ->
            FilterChip(
                selected = fuente.id == estado.fuenteSeleccionadaId,
                onClick = { onSeleccionarFuente(fuente.id) },
                label = { Text(fuente.nombre) },
            )
        }
    }
}

@Composable
private fun MensajesGuia(estado: GuiaUiState) {
    if (estado.cargando) {
        Text(
            text = stringResource(R.string.guia_cargando),
            modifier = Modifier.testTag(TAG_GUIA_CARGANDO),
        )
    }
    estado.error?.let { codigo ->
        Text(
            text = stringResource(R.string.guia_error, codigo),
            modifier = Modifier.testTag(TAG_ERROR_GUIA),
        )
    }
    if (!estado.cargando && estado.fuentes.isEmpty()) {
        Text(
            text = stringResource(R.string.guia_sin_fuentes),
            modifier = Modifier.testTag(TAG_SIN_FUENTES_GUIA),
        )
    }
    if (!estado.cargando && estado.fuentes.isNotEmpty() && estado.canales.isEmpty()) {
        Text(
            text = stringResource(R.string.guia_sin_canales),
            modifier = Modifier.testTag(TAG_SIN_GUIA),
        )
    }
}

@Composable
private fun SeccionPendientes(
    pendientes: List<AsociacionVista>,
    onConfirmar: (Long) -> Unit,
    onEditar: (Long) -> Unit,
    onRevocar: (Long) -> Unit,
) {
    Text(
        text = stringResource(R.string.guia_pendientes),
        style = MaterialTheme.typography.titleMedium,
    )
    pendientes.forEach { asociacion ->
        FilaPendiente(
            asociacion = asociacion,
            onConfirmar = { onConfirmar(asociacion.id) },
            onEditar = { onEditar(asociacion.id) },
            onRevocar = { onRevocar(asociacion.id) },
        )
    }
}

@Composable
private fun ColumnScope.ListaCanales(
    canales: List<CanalGuiaFila>,
    onAbrirCanal: (CanalGuia) -> Unit,
) {
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(canales, key = { it.canal.canalId }) { fila ->
            FilaCanal(fila = fila, onAbrir = { onAbrirCanal(fila.canal) })
        }
    }
}

@Composable
private fun ColumnScope.VistaCanal(
    canal: CanalGuia,
    programas: LazyPagingItems<ProgramaGuia>,
    onVolverAlListado: () -> Unit,
    onAbrirDetalle: (ProgramaGuia) -> Unit,
) {
    TextButton(onClick = onVolverAlListado) {
        Text(stringResource(R.string.guia_volver_canales))
    }
    Text(
        text = canal.nombre ?: stringResource(R.string.guia_canal_sin_nombre),
        style = MaterialTheme.typography.titleLarge,
    )
    if (programas.loadState.refresh is LoadState.Loading && programas.itemCount == 0) {
        Text(
            text = stringResource(R.string.guia_cargando),
            modifier = Modifier.testTag(TAG_GUIA_CARGANDO),
        )
    }
    if (programas.loadState.refresh is LoadState.NotLoading && programas.itemCount == 0) {
        Text(
            text = stringResource(R.string.guia_sin_programas),
            modifier = Modifier.testTag(TAG_SIN_PROGRAMAS_GUIA),
        )
    }
    LazyColumn(
        modifier = Modifier.weight(1f).fillMaxWidth(),
        state = rememberLazyListState(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        items(
            count = programas.itemCount,
            key = { indice -> programas.peek(indice)?.id ?: indice },
        ) { indice ->
            programas[indice]?.let { programa ->
                FilaPrograma(
                    programa = programa,
                    onAbrir = { onAbrirDetalle(programa) },
                )
            }
        }
    }
}

@Composable
private fun FilaCanal(
    fila: CanalGuiaFila,
    onAbrir: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onAbrir)
                .testTag(tagCanalGuia(fila.canal.canalId)),
    ) {
        Text(
            text = fila.canal.nombre ?: stringResource(R.string.guia_canal_sin_nombre),
            style = MaterialTheme.typography.titleMedium,
        )
        Text(
            text = stringResource(R.string.guia_ahora, fila.actual?.titulo ?: stringResource(R.string.guia_sin_programa)),
        )
        Text(
            text = stringResource(R.string.guia_siguiente, fila.siguiente?.titulo ?: stringResource(R.string.guia_sin_programa)),
        )
    }
}

@Composable
private fun FilaPrograma(
    programa: ProgramaGuia,
    onAbrir: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .clickable(onClick = onAbrir)
                .testTag(tagProgramaGuia(programa.id)),
    ) {
        Text(text = programa.titulo, style = MaterialTheme.typography.titleSmall)
        Text(text = rangoLocal(programa.inicio, programa.fin))
        programa.descripcion?.takeIf { it.isNotBlank() }?.let { descripcion ->
            Text(text = descripcion, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun FilaPendiente(
    asociacion: AsociacionVista,
    onConfirmar: () -> Unit,
    onEditar: () -> Unit,
    onRevocar: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .testTag(tagAsociacionPendiente(asociacion.id)),
    ) {
        Text(
            text = asociacion.canalNombre ?: stringResource(R.string.guia_canal_sin_nombre),
            style = MaterialTheme.typography.titleSmall,
        )
        Text(text = stringResource(R.string.guia_channel_id, asociacion.channelIdTexto))
        Text(text = stringResource(R.string.guia_estado, asociacion.estado.valor))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onClick = onConfirmar) { Text(stringResource(R.string.guia_confirmar)) }
            TextButton(onClick = onEditar) { Text(stringResource(R.string.guia_editar)) }
            TextButton(onClick = onRevocar) { Text(stringResource(R.string.guia_revocar)) }
        }
    }
}

@Composable
private fun DialogoDetalle(
    programa: ProgramaGuia,
    onCerrar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCerrar,
        modifier = Modifier.testTag(TAG_DETALLE_GUIA),
        title = { Text(programa.titulo) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.guia_detalle_inicio, fechaLocal(programa.inicio)))
                Text(stringResource(R.string.guia_detalle_fin, fechaLocal(programa.fin)))
                Text(stringResource(R.string.guia_detalle_descripcion))
                Text(
                    text =
                        programa.descripcion
                            ?.takeIf { it.isNotBlank() }
                            ?: stringResource(R.string.guia_detalle_sin_descripcion),
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onCerrar) { Text(stringResource(R.string.guia_cerrar)) }
        },
    )
}

@Composable
private fun DialogoEditarAsociacion(
    canales: List<CanalOpcion>,
    onElegir: (Long) -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        modifier = Modifier.testTag(TAG_EDITAR_ASOCIACION),
        title = { Text(stringResource(R.string.guia_editar_titulo)) },
        text = {
            LazyColumn(modifier = Modifier.heightIn(max = 320.dp)) {
                items(canales, key = { it.id }) { canal ->
                    TextButton(onClick = { onElegir(canal.id) }) {
                        Text(canal.nombre ?: stringResource(R.string.guia_canal_sin_nombre))
                    }
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancelar) { Text(stringResource(R.string.guia_cancelar)) }
        },
    )
}

private fun rangoLocal(
    inicio: Instant,
    fin: Instant,
): String = "${horaLocal(inicio)}–${horaLocal(fin)}"

private fun horaLocal(instante: Instant): String =
    DateTimeFormatter
        .ofLocalizedTime(FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instante)

private fun fechaLocal(instante: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instante)
