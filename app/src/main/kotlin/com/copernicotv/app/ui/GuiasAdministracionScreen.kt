package com.copernicotv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.copernicotv.app.R
import com.copernicotv.app.data.ConexionVista
import com.copernicotv.app.data.FormularioGuia
import com.copernicotv.app.data.GuiaAdministrada
import com.copernicotv.core.conexion.TipoConexion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

/** Etiqueta del botón que vuelve a la guía. */
const val TAG_VOLVER_GUIAS = "volver-guias"

/** Etiqueta del botón de alta de guía. */
const val TAG_NUEVA_GUIA = "nueva-guia"

/** Etiqueta del indicador de carga de guías. */
const val TAG_GUIAS_CARGANDO = "guias-cargando"

/** Etiqueta del error de guías. */
const val TAG_ERROR_GUIAS = "error-guias"

/** Etiqueta del aviso de guías. */
const val TAG_AVISO_GUIAS = "aviso-guias"

/** Etiqueta del estado sin guías. */
const val TAG_SIN_GUIAS = "sin-guias"

/** Etiqueta del formulario de guía. */
const val TAG_FORMULARIO_GUIA = "formulario-guia"

/** Etiqueta del botón de guardado del formulario de guía. */
const val TAG_GUARDAR_GUIA = "guardar-guia"

/** Etiqueta del diálogo de baja de guía. */
const val TAG_ELIMINAR_GUIA = "eliminar-guia"

/** Acción «actualizar ahora» de una guía. */
const val TAG_GUIA_ACTUALIZAR = "guia-actualizar"

/** Acción «editar» de una guía. */
const val TAG_GUIA_EDITAR = "guia-editar"

/** Acción «eliminar» de una guía. */
const val TAG_GUIA_ELIMINAR = "guia-eliminar"

/** Interruptor de activación de una guía. */
const val TAG_GUIA_ACTIVA = "guia-activa"

/** Etiqueta de una acción concreta sobre una guía. */
fun tagAccionGuia(
    base: String,
    id: Long,
): String = "$base-$id"

/**
 * Administración de guías EPG (S7, H-06).
 *
 * Lista cada fuente con su estado, última actualización, último error y
 * reintentos, y permite crear, editar, eliminar, activar y actualizar a
 * demanda. La URL guardada nunca se muestra completa: en edición, dejarla vacía
 * conserva la existente.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun GuiasAdministracionScreen(
    estado: GuiasAdministracionUiState,
    onVolver: () -> Unit,
    onCrear: (FormularioGuia) -> Unit,
    onEditar: (Long, FormularioGuia) -> Unit,
    onEliminar: (Long) -> Unit,
    onCambiarActiva: (Long, Boolean) -> Unit,
    onActualizarAhora: (Long) -> Unit,
) {
    var crear by remember { mutableStateOf(false) }
    var editando by remember { mutableStateOf<GuiaAdministrada?>(null) }
    var porEliminar by remember { mutableStateOf<GuiaAdministrada?>(null) }
    Surface(
        modifier =
            Modifier
                .fillMaxSize()
                .windowInsetsPadding(WindowInsets.safeDrawing),
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Button(
                    onClick = onVolver,
                    modifier = Modifier.testTag(TAG_VOLVER_GUIAS),
                ) {
                    Text(stringResource(R.string.guias_volver))
                }
                Text(
                    text = stringResource(R.string.guias_titulo),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Button(
                onClick = { crear = true },
                modifier = Modifier.testTag(TAG_NUEVA_GUIA),
            ) {
                Text(stringResource(R.string.guias_nueva))
            }
            if (estado.cargando) {
                Text(
                    text = stringResource(R.string.guias_cargando),
                    modifier = Modifier.testTag(TAG_GUIAS_CARGANDO),
                )
            }
            estado.error?.let { codigo ->
                Text(
                    text = stringResource(R.string.guias_error, codigo),
                    modifier = Modifier.testTag(TAG_ERROR_GUIAS),
                )
            }
            estado.aviso?.let { codigo ->
                Text(
                    text = stringResource(R.string.guias_aviso, codigo),
                    modifier = Modifier.testTag(TAG_AVISO_GUIAS),
                )
            }
            if (!estado.cargando && estado.guias.isEmpty()) {
                Text(
                    text = stringResource(R.string.guias_sin_guias),
                    modifier = Modifier.testTag(TAG_SIN_GUIAS),
                )
            }
            estado.guias.forEach { guia ->
                TarjetaGuia(
                    guia = guia,
                    conexion = estado.conexiones.firstOrNull { it.id == guia.conexionId },
                    onActiva = { activa -> onCambiarActiva(guia.id, activa) },
                    onActualizar = { onActualizarAhora(guia.id) },
                    onEditar = { editando = guia },
                    onEliminar = { porEliminar = guia },
                )
            }
        }
    }
    if (crear) {
        DialogoFormularioGuia(
            guia = null,
            conexiones = estado.conexiones,
            onGuardar = { formulario ->
                onCrear(formulario)
                crear = false
            },
            onCancelar = { crear = false },
        )
    }
    editando?.let { guia ->
        DialogoFormularioGuia(
            guia = guia,
            conexiones = estado.conexiones,
            onGuardar = { formulario ->
                onEditar(guia.id, formulario)
                editando = null
            },
            onCancelar = { editando = null },
        )
    }
    porEliminar?.let { guia ->
        AlertDialog(
            onDismissRequest = { porEliminar = null },
            modifier = Modifier.testTag(TAG_ELIMINAR_GUIA),
            title = { Text(stringResource(R.string.guias_eliminar_titulo)) },
            text = { Text(stringResource(R.string.guias_eliminar_mensaje, guia.nombre)) },
            confirmButton = {
                TextButton(
                    onClick = {
                        onEliminar(guia.id)
                        porEliminar = null
                    },
                ) {
                    Text(stringResource(R.string.guias_eliminar_confirmar))
                }
            },
            dismissButton = {
                TextButton(onClick = { porEliminar = null }) { Text(stringResource(R.string.guias_cancelar)) }
            },
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TarjetaGuia(
    guia: GuiaAdministrada,
    conexion: ConexionVista?,
    onActiva: (Boolean) -> Unit,
    onActualizar: () -> Unit,
    onEditar: () -> Unit,
    onEliminar: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = guia.nombre, style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.guias_estado, guia.estado))
        Text(text = stringResource(R.string.guias_origen, guia.origen))
        Text(
            text =
                stringResource(
                    R.string.guias_ultima,
                    guia.ultimaActualizacionEn?.let(::fechaHoraLocal) ?: stringResource(R.string.guias_nunca),
                ),
        )
        Text(
            text =
                stringResource(
                    R.string.guias_asociacion,
                    conexion?.nombre ?: stringResource(R.string.guias_sin_conexion),
                ),
        )
        guia.ultimoErrorCodigo?.let { codigo ->
            Text(text = stringResource(R.string.guias_ultimo_error, codigo))
        }
        if (guia.intentosFallidos > 0) {
            Text(text = stringResource(R.string.guias_intentos, guia.intentosFallidos))
        }
        if (!guia.puedeReintentar && guia.activa && guia.actualizacionAutomatica) {
            Text(text = stringResource(R.string.guias_reintento_agotado))
        }
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Switch(
                checked = guia.activa,
                onCheckedChange = onActiva,
                modifier = Modifier.testTag(tagAccionGuia(TAG_GUIA_ACTIVA, guia.id)),
            )
            Text(text = stringResource(R.string.guias_activa))
        }
        // FlowRow: a escalas grandes las acciones se reparten en varias líneas y
        // ninguna queda fuera del área visible (H-02/H-03).
        FlowRow(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            TextButton(
                onClick = onActualizar,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionGuia(TAG_GUIA_ACTUALIZAR, guia.id)),
            ) {
                Text(stringResource(R.string.guias_actualizar))
            }
            TextButton(
                onClick = onEditar,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionGuia(TAG_GUIA_EDITAR, guia.id)),
            ) {
                Text(stringResource(R.string.guias_editar))
            }
            TextButton(
                onClick = onEliminar,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionGuia(TAG_GUIA_ELIMINAR, guia.id)),
            ) {
                Text(stringResource(R.string.guias_eliminar))
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Suppress("LongMethod")
@Composable
private fun DialogoFormularioGuia(
    guia: GuiaAdministrada?,
    conexiones: List<ConexionVista>,
    onGuardar: (FormularioGuia) -> Unit,
    onCancelar: () -> Unit,
) {
    var nombre by remember { mutableStateOf(guia?.nombre.orEmpty()) }
    var url by remember { mutableStateOf("") }
    var activa by remember { mutableStateOf(guia?.activa ?: true) }
    var automatica by remember { mutableStateOf(guia?.actualizacionAutomatica ?: false) }
    var intervalo by remember { mutableStateOf(guia?.intervaloHoras?.toString().orEmpty()) }
    var conexionId by remember { mutableStateOf(guia?.conexionId) }
    AlertDialog(
        onDismissRequest = onCancelar,
        modifier = Modifier.testTag(TAG_FORMULARIO_GUIA),
        title = {
            Text(
                stringResource(
                    if (guia == null) R.string.guias_formulario_nueva else R.string.guias_formulario_editar,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.guias_campo_nombre)) },
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = {
                        Text(
                            stringResource(
                                if (guia?.urlPresente == true) {
                                    R.string.guias_campo_url_conservar
                                } else {
                                    R.string.guias_campo_url
                                },
                            ),
                        )
                    },
                )
                Text(text = stringResource(R.string.guias_campo_conexion))
                FlowRow(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    FilterChip(
                        selected = conexionId == null,
                        onClick = { conexionId = null },
                        label = { Text(stringResource(R.string.guias_sin_conexion)) },
                    )
                    conexiones.forEach { conexion ->
                        FilterChip(
                            selected = conexionId == conexion.id,
                            onClick = { conexionId = conexion.id },
                            label = { Text("${conexion.nombre} (${textoMetodoConexion(conexion.tipo)})") },
                        )
                    }
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = activa, onCheckedChange = { activa = it })
                    Text(text = stringResource(R.string.guias_campo_activa))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Switch(checked = automatica, onCheckedChange = { automatica = it })
                    Text(text = stringResource(R.string.guias_campo_automatica))
                }
                OutlinedTextField(
                    value = intervalo,
                    onValueChange = { intervalo = it },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    label = { Text(stringResource(R.string.guias_campo_intervalo)) },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                )
                Button(
                    onClick = {
                        onGuardar(
                            FormularioGuia(
                                nombre = nombre,
                                url = url.takeIf { it.isNotBlank() },
                                activa = activa,
                                actualizacionAutomatica = automatica,
                                intervaloHoras = intervalo.toLongOrNull(),
                                conexionId = conexionId,
                            ),
                        )
                    },
                    modifier = Modifier.fillMaxWidth().testTag(TAG_GUARDAR_GUIA),
                ) {
                    Text(stringResource(R.string.guias_guardar))
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancelar) { Text(stringResource(R.string.guias_cancelar)) }
        },
    )
}

@Composable
private fun textoMetodoConexion(tipo: TipoConexion): String =
    stringResource(
        when (tipo) {
            TipoConexion.XTREAM -> R.string.conexiones_metodo_xtream
            TipoConexion.M3U -> R.string.conexiones_metodo_m3u
        },
    )

private fun fechaHoraLocal(instante: Instant): String =
    DateTimeFormatter
        .ofLocalizedDateTime(FormatStyle.MEDIUM, FormatStyle.SHORT)
        .withZone(ZoneId.systemDefault())
        .format(instante)
