package com.copernicotv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
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
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.copernicotv.app.R
import com.copernicotv.app.data.ConexionVista
import com.copernicotv.app.data.DetalleConexion
import com.copernicotv.app.data.FormularioM3U
import com.copernicotv.app.data.FormularioXtream
import com.copernicotv.app.data.GuiaAdministrada
import com.copernicotv.core.conexion.DatosConexionM3U
import com.copernicotv.core.conexion.EstadoConexion
import com.copernicotv.core.conexion.FormatoSalidaXtream
import com.copernicotv.core.conexion.ProtocoloConexion
import com.copernicotv.core.conexion.TipoConexion

/** Etiqueta del botón que vuelve al catálogo desde Conexiones. */
const val TAG_VOLVER_CONEXIONES = "volver-conexiones"

/** Etiqueta del botón de alta de conexión. */
const val TAG_NUEVA_CONEXION = "nueva-conexion"

/** Etiqueta del indicador de carga. */
const val TAG_CONEXIONES_CARGANDO = "conexiones-cargando"

/** Etiqueta del error de la pantalla. */
const val TAG_ERROR_CONEXIONES = "error-conexiones"

/** Etiqueta del aviso de la pantalla. */
const val TAG_AVISO_CONEXIONES = "aviso-conexiones"

/** Etiqueta del estado sin conexiones. */
const val TAG_SIN_CONEXIONES = "sin-conexiones"

/** Etiqueta del formulario de conexión. */
const val TAG_FORMULARIO_CONEXION = "formulario-conexion"

/** Etiqueta del botón de guardado del formulario. */
const val TAG_GUARDAR_CONEXION = "guardar-conexion"

/** Etiqueta del diálogo de confirmación de baja. */
const val TAG_ELIMINAR_CONEXION = "eliminar-conexion"

/** Prefijo de la etiqueta de una tarjeta de conexión. */
const val PREFIJO_TAG_CONEXION = "conexion"

/** Acción «probar» de una conexión. */
const val TAG_PROBAR = "conexion-probar"

/** Acción «editar» de una conexión. */
const val TAG_EDITAR = "conexion-editar"

/** Acción «activar o desactivar» de una conexión. */
const val TAG_ALTERNAR = "conexion-alternar"

/** Acción «actualizar lista» de una conexión. */
const val TAG_ACTUALIZAR_LISTA = "conexion-actualizar-lista"

/** Acción «actualizar guía» de una conexión. */
const val TAG_ACTUALIZAR_GUIA = "conexion-actualizar-guia"

/** Acción «eliminar» de una conexión. */
const val TAG_ELIMINAR = "conexion-eliminar"

/** Campo nombre del formulario. */
const val TAG_CAMPO_NOMBRE = "conexion-campo-nombre"

/** Campo servidor del formulario Xtream. */
const val TAG_CAMPO_SERVIDOR = "conexion-campo-servidor"

/** Campo puerto del formulario Xtream. */
const val TAG_CAMPO_PUERTO = "conexion-campo-puerto"

/** Campo usuario del formulario Xtream. */
const val TAG_CAMPO_USUARIO = "conexion-campo-usuario"

/** Campo contraseña del formulario Xtream. */
const val TAG_CAMPO_CONTRASENA = "conexion-campo-contrasena"

/** Campo URL de lista del formulario M3U. */
const val TAG_CAMPO_URL_LISTA = "conexion-campo-url-lista"

/** Campo URL de guía de ambos formularios. */
const val TAG_CAMPO_GUIA = "conexion-campo-guia"

/** Interruptor de actualización automática. */
const val TAG_CAMPO_AUTOMATICA = "conexion-campo-automatica"

/** Campo intervalo de actualización en horas. */
const val TAG_CAMPO_INTERVALO = "conexion-campo-intervalo"

/** Interruptor de conexión activa. */
const val TAG_CAMPO_ACTIVA = "conexion-campo-activa"

/** Etiqueta de una acción concreta sobre una conexión. */
fun tagAccionConexion(
    base: String,
    id: Long,
): String = "$base-$id"

/**
 * Pantalla de administración de conexiones IPTV (S4, S5, S6).
 *
 * Ofrece crear, editar, eliminar con confirmación, activar o desactivar, probar
 * y actualizar lista y guía para los métodos Xtream Codes y M3U/M3U8. El
 * formulario nunca muestra la contraseña guardada: dejar el campo vacío en una
 * edición conserva el secreto.
 */
@Suppress("LongParameterList", "LongMethod")
@Composable
fun ConexionesScreen(
    estado: ConexionesUiState,
    onVolver: () -> Unit,
    onCrearXtream: (FormularioXtream) -> Unit,
    onEditarXtream: (Long, FormularioXtream) -> Unit,
    onCrearM3U: (FormularioM3U) -> Unit,
    onEditarM3U: (Long, FormularioM3U) -> Unit,
    onEliminar: (Long) -> Unit,
    onActivar: (Long) -> Unit,
    onDesactivar: (Long) -> Unit,
    onProbar: (Long) -> Unit,
    onActualizarLista: (Long) -> Unit,
    onActualizarGuia: (Long) -> Unit,
    onCargarDetalle: (Long) -> Unit,
    onLimpiarDetalle: () -> Unit,
) {
    var crear by remember { mutableStateOf(false) }
    var editandoId by remember { mutableStateOf<Long?>(null) }
    var porEliminar by remember { mutableStateOf<ConexionVista?>(null) }
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
                    modifier = Modifier.testTag(TAG_VOLVER_CONEXIONES),
                ) {
                    Text(stringResource(R.string.conexiones_volver))
                }
                Text(
                    text = stringResource(R.string.conexiones_titulo),
                    style = MaterialTheme.typography.headlineMedium,
                )
            }
            Button(
                onClick = {
                    onLimpiarDetalle()
                    editandoId = null
                    crear = true
                },
                modifier = Modifier.testTag(TAG_NUEVA_CONEXION),
            ) {
                Text(stringResource(R.string.conexiones_nueva))
            }
            if (estado.cargando) {
                Text(
                    text = stringResource(R.string.conexiones_cargando),
                    modifier = Modifier.testTag(TAG_CONEXIONES_CARGANDO),
                )
            }
            estado.error?.let { codigo ->
                Text(
                    text = stringResource(R.string.conexiones_error, codigo),
                    modifier = Modifier.testTag(TAG_ERROR_CONEXIONES),
                )
            }
            estado.aviso?.let { codigo ->
                Text(
                    text = stringResource(R.string.conexiones_aviso, codigo),
                    modifier = Modifier.testTag(TAG_AVISO_CONEXIONES),
                )
            }
            if (!estado.cargando && estado.conexiones.isEmpty()) {
                Text(
                    text = stringResource(R.string.conexiones_sin_conexiones),
                    modifier = Modifier.testTag(TAG_SIN_CONEXIONES),
                )
            }
            estado.conexiones.forEach { conexion ->
                TarjetaConexion(
                    conexion = conexion,
                    guia = estado.guias.firstOrNull { it.conexionId == conexion.id },
                    probando = estado.probandoId == conexion.id,
                    onProbar = { onProbar(conexion.id) },
                    onEditar = {
                        onCargarDetalle(conexion.id)
                        editandoId = conexion.id
                    },
                    onActivar = { onActivar(conexion.id) },
                    onDesactivar = { onDesactivar(conexion.id) },
                    onActualizarLista = { onActualizarLista(conexion.id) },
                    onActualizarGuia = { onActualizarGuia(conexion.id) },
                    onEliminar = { porEliminar = conexion },
                )
            }
        }
    }
    if (crear) {
        DialogoFormularioConexion(
            detalle = null,
            onGuardarXtream = { formulario ->
                onCrearXtream(formulario)
                crear = false
            },
            onGuardarM3U = { formulario ->
                onCrearM3U(formulario)
                crear = false
            },
            onCancelar = {
                crear = false
                onLimpiarDetalle()
            },
        )
    }
    val detalle = estado.detalle
    if (editandoId != null && detalle != null) {
        DialogoFormularioConexion(
            detalle = detalle,
            onGuardarXtream = { formulario ->
                onEditarXtream(detalle.id, formulario)
                editandoId = null
                onLimpiarDetalle()
            },
            onGuardarM3U = { formulario ->
                onEditarM3U(detalle.id, formulario)
                editandoId = null
                onLimpiarDetalle()
            },
            onCancelar = {
                editandoId = null
                onLimpiarDetalle()
            },
        )
    }
    porEliminar?.let { conexion ->
        DialogoConfirmarBaja(
            conexion = conexion,
            onConfirmar = {
                onEliminar(conexion.id)
                porEliminar = null
            },
            onCancelar = { porEliminar = null },
        )
    }
}

@Composable
private fun TarjetaConexion(
    conexion: ConexionVista,
    guia: GuiaAdministrada?,
    probando: Boolean,
    onProbar: () -> Unit,
    onEditar: () -> Unit,
    onActivar: () -> Unit,
    onDesactivar: () -> Unit,
    onActualizarLista: () -> Unit,
    onActualizarGuia: () -> Unit,
    onEliminar: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().testTag("$PREFIJO_TAG_CONEXION-${conexion.id}"),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        Text(text = conexion.nombre, style = MaterialTheme.typography.titleMedium)
        Text(text = stringResource(R.string.conexiones_metodo, textoMetodo(conexion.tipo)))
        Text(text = stringResource(R.string.conexiones_estado, textoEstado(conexion.estado)))
        Text(
            text =
                if (conexion.activa) {
                    stringResource(R.string.conexiones_activa)
                } else {
                    stringResource(R.string.conexiones_inactiva)
                },
        )
        conexion.ultimoCodigo?.let { codigo ->
            Text(text = stringResource(R.string.conexiones_ultimo_resultado, codigo))
        }
        Text(
            text =
                stringResource(
                    R.string.conexiones_ultima_lista,
                    conexion.listaActualizadaEn?.toString() ?: stringResource(R.string.conexiones_nunca),
                ),
        )
        Text(
            text =
                stringResource(
                    R.string.conexiones_ultima_guia,
                    guia?.ultimaActualizacionEn?.toString() ?: stringResource(R.string.conexiones_nunca),
                ),
        )
        guia?.let { fuente ->
            Text(text = stringResource(R.string.conexiones_guia_estado, fuente.estado))
            fuente.ultimoErrorCodigo?.let { codigo ->
                Text(text = stringResource(R.string.conexiones_guia_error, codigo))
            }
        }
        // FlowRow-like wrap: las acciones se apilan en filas de a dos para que a
        // escalas grandes ninguna quede fuera del área visible (H-02/H-03).
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onProbar,
                enabled = !probando,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionConexion(TAG_PROBAR, conexion.id)),
            ) {
                Text(
                    stringResource(
                        if (probando) R.string.conexiones_probando else R.string.conexiones_probar,
                    ),
                )
            }
            TextButton(
                onClick = onEditar,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionConexion(TAG_EDITAR, conexion.id)),
            ) {
                Text(stringResource(R.string.conexiones_editar))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = if (conexion.activa) onDesactivar else onActivar,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionConexion(TAG_ALTERNAR, conexion.id)),
            ) {
                Text(
                    stringResource(
                        if (conexion.activa) R.string.conexiones_desactivar else R.string.conexiones_activar,
                    ),
                )
            }
            TextButton(
                onClick = onActualizarLista,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionConexion(TAG_ACTUALIZAR_LISTA, conexion.id)),
            ) {
                Text(stringResource(R.string.conexiones_actualizar_lista))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(
                onClick = onActualizarGuia,
                enabled = guia != null,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionConexion(TAG_ACTUALIZAR_GUIA, conexion.id)),
            ) {
                Text(stringResource(R.string.conexiones_actualizar_guia))
            }
            TextButton(
                onClick = onEliminar,
                modifier = Modifier.heightIn(min = 48.dp).testTag(tagAccionConexion(TAG_ELIMINAR, conexion.id)),
            ) {
                Text(stringResource(R.string.conexiones_eliminar))
            }
        }
    }
}

@Composable
private fun DialogoFormularioConexion(
    detalle: DetalleConexion?,
    onGuardarXtream: (FormularioXtream) -> Unit,
    onGuardarM3U: (FormularioM3U) -> Unit,
    onCancelar: () -> Unit,
) {
    var tipo by remember { mutableStateOf(detalle?.tipo ?: TipoConexion.XTREAM) }
    AlertDialog(
        onDismissRequest = onCancelar,
        modifier = Modifier.testTag(TAG_FORMULARIO_CONEXION),
        title = {
            Text(
                stringResource(
                    if (detalle == null) R.string.conexiones_formulario_nueva else R.string.conexiones_formulario_editar,
                ),
            )
        },
        text = {
            Column(
                modifier = Modifier.heightIn(max = 520.dp).verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(8.dp),
            ) {
                if (detalle == null) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilterChip(
                            selected = tipo == TipoConexion.XTREAM,
                            onClick = { tipo = TipoConexion.XTREAM },
                            label = { Text(stringResource(R.string.conexiones_metodo_xtream)) },
                        )
                        FilterChip(
                            selected = tipo == TipoConexion.M3U,
                            onClick = { tipo = TipoConexion.M3U },
                            label = { Text(stringResource(R.string.conexiones_metodo_m3u)) },
                        )
                    }
                } else {
                    Text(text = stringResource(R.string.conexiones_metodo, textoMetodo(tipo)))
                }
                when (tipo) {
                    TipoConexion.XTREAM ->
                        CamposXtream(
                            detalle = detalle,
                            onGuardar = onGuardarXtream,
                        )
                    TipoConexion.M3U ->
                        CamposM3U(
                            detalle = detalle,
                            onGuardar = onGuardarM3U,
                        )
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onCancelar) { Text(stringResource(R.string.conexiones_cancelar)) }
        },
    )
}

@Suppress("LongMethod")
@Composable
private fun CamposXtream(
    detalle: DetalleConexion?,
    onGuardar: (FormularioXtream) -> Unit,
) {
    var nombre by remember { mutableStateOf(detalle?.nombre.orEmpty()) }
    var servidor by remember { mutableStateOf(detalle?.servidor.orEmpty()) }
    var puerto by remember { mutableStateOf(detalle?.puerto?.toString().orEmpty()) }
    var protocolo by remember { mutableStateOf(detalle?.protocolo ?: ProtocoloConexion.HTTPS) }
    var usuario by remember { mutableStateOf(detalle?.usuario.orEmpty()) }
    var contrasena by remember { mutableStateOf("") }
    var formato by remember { mutableStateOf(detalle?.formatoSalida ?: FormatoSalidaXtream.M3U8) }
    var urlGuia by remember { mutableStateOf("") }
    var automatica by remember { mutableStateOf(detalle?.actualizacionAutomatica ?: true) }
    var intervalo by remember { mutableStateOf((detalle?.intervaloHoras ?: DatosConexionM3U.INTERVALO_POR_DEFECTO).toString()) }
    var activa by remember { mutableStateOf(detalle?.activa ?: true) }
    CampoTexto(
        valor = nombre,
        onValor = { nombre = it },
        etiqueta = R.string.conexiones_campo_nombre,
        tag = TAG_CAMPO_NOMBRE,
    )
    CampoTexto(
        valor = servidor,
        onValor = { servidor = it },
        etiqueta = R.string.conexiones_campo_servidor,
        tag = TAG_CAMPO_SERVIDOR,
    )
    CampoTexto(
        valor = puerto,
        onValor = { puerto = it },
        etiqueta = R.string.conexiones_campo_puerto,
        tag = TAG_CAMPO_PUERTO,
        numerico = true,
    )
    FilaProtocolo(protocolo = protocolo, onProtocolo = { protocolo = it })
    CampoTexto(
        valor = usuario,
        onValor = { usuario = it },
        etiqueta = R.string.conexiones_campo_usuario,
        tag = TAG_CAMPO_USUARIO,
    )
    OutlinedTextField(
        value = contrasena,
        onValueChange = { contrasena = it },
        modifier = Modifier.fillMaxWidth().testTag(TAG_CAMPO_CONTRASENA),
        singleLine = true,
        label = {
            Text(
                stringResource(
                    if (detalle?.passwordPresente == true) {
                        R.string.conexiones_campo_contrasena_conservar
                    } else {
                        R.string.conexiones_campo_contrasena
                    },
                ),
            )
        },
        visualTransformation = PasswordVisualTransformation(),
    )
    FilaFormato(formato = formato, onFormato = { formato = it })
    CampoTexto(
        valor = urlGuia,
        onValor = { urlGuia = it },
        etiqueta = R.string.conexiones_campo_guia,
        tag = TAG_CAMPO_GUIA,
    )
    detalle?.urlGuiaRedactada?.let { redactada ->
        Text(text = stringResource(R.string.conexiones_guia_actual, redactada))
    }
    FilaInterruptor(
        valor = automatica,
        onValor = { automatica = it },
        etiqueta = R.string.conexiones_campo_automatica,
        tag = TAG_CAMPO_AUTOMATICA,
    )
    CampoTexto(
        valor = intervalo,
        onValor = { intervalo = it },
        etiqueta = R.string.conexiones_campo_intervalo,
        tag = TAG_CAMPO_INTERVALO,
        numerico = true,
    )
    FilaInterruptor(
        valor = activa,
        onValor = { activa = it },
        etiqueta = R.string.conexiones_campo_activa,
        tag = TAG_CAMPO_ACTIVA,
    )
    Button(
        onClick = {
            onGuardar(
                FormularioXtream(
                    nombre = nombre,
                    servidor = servidor,
                    puerto = puerto.toIntOrNull(),
                    protocolo = protocolo,
                    usuario = usuario,
                    contrasena = contrasena.takeIf { it.isNotEmpty() },
                    formatoSalida = formato,
                    urlGuia = urlGuia.takeIf { it.isNotBlank() },
                    actualizacionAutomatica = automatica,
                    intervaloHoras = intervalo.toLongOrNull() ?: DatosConexionM3U.INTERVALO_POR_DEFECTO,
                    activa = activa,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth().testTag(TAG_GUARDAR_CONEXION),
    ) {
        Text(stringResource(R.string.conexiones_guardar))
    }
}

@Suppress("LongMethod")
@Composable
private fun CamposM3U(
    detalle: DetalleConexion?,
    onGuardar: (FormularioM3U) -> Unit,
) {
    var nombre by remember { mutableStateOf(detalle?.nombre.orEmpty()) }
    var urlLista by remember { mutableStateOf("") }
    var urlGuia by remember { mutableStateOf("") }
    var automatica by remember { mutableStateOf(detalle?.actualizacionAutomatica ?: true) }
    var intervalo by remember { mutableStateOf((detalle?.intervaloHoras ?: DatosConexionM3U.INTERVALO_POR_DEFECTO).toString()) }
    var activa by remember { mutableStateOf(detalle?.activa ?: true) }
    CampoTexto(
        valor = nombre,
        onValor = { nombre = it },
        etiqueta = R.string.conexiones_campo_nombre,
        tag = TAG_CAMPO_NOMBRE,
    )
    CampoTexto(
        valor = urlLista,
        onValor = { urlLista = it },
        etiqueta =
            if (detalle?.urlListaPresente == true) {
                R.string.conexiones_campo_url_lista_conservar
            } else {
                R.string.conexiones_campo_url_lista
            },
        tag = TAG_CAMPO_URL_LISTA,
    )
    detalle?.urlListaRedactada?.let { redactada ->
        Text(text = stringResource(R.string.conexiones_lista_actual, redactada))
    }
    CampoTexto(
        valor = urlGuia,
        onValor = { urlGuia = it },
        etiqueta = R.string.conexiones_campo_guia,
        tag = TAG_CAMPO_GUIA,
    )
    detalle?.urlGuiaRedactada?.let { redactada ->
        Text(text = stringResource(R.string.conexiones_guia_actual, redactada))
    }
    FilaInterruptor(
        valor = automatica,
        onValor = { automatica = it },
        etiqueta = R.string.conexiones_campo_automatica,
        tag = TAG_CAMPO_AUTOMATICA,
    )
    CampoTexto(
        valor = intervalo,
        onValor = { intervalo = it },
        etiqueta = R.string.conexiones_campo_intervalo,
        tag = TAG_CAMPO_INTERVALO,
        numerico = true,
    )
    FilaInterruptor(
        valor = activa,
        onValor = { activa = it },
        etiqueta = R.string.conexiones_campo_activa,
        tag = TAG_CAMPO_ACTIVA,
    )
    Button(
        onClick = {
            onGuardar(
                FormularioM3U(
                    nombre = nombre,
                    urlLista = urlLista.takeIf { it.isNotBlank() },
                    urlGuia = urlGuia.takeIf { it.isNotBlank() },
                    actualizacionAutomatica = automatica,
                    intervaloHoras = intervalo.toLongOrNull() ?: DatosConexionM3U.INTERVALO_POR_DEFECTO,
                    activa = activa,
                ),
            )
        },
        modifier = Modifier.fillMaxWidth().testTag(TAG_GUARDAR_CONEXION),
    ) {
        Text(stringResource(R.string.conexiones_guardar))
    }
}

@Composable
private fun CampoTexto(
    valor: String,
    onValor: (String) -> Unit,
    etiqueta: Int,
    tag: String,
    numerico: Boolean = false,
) {
    OutlinedTextField(
        value = valor,
        onValueChange = onValor,
        modifier = Modifier.fillMaxWidth().testTag(tag),
        singleLine = true,
        label = { Text(stringResource(etiqueta)) },
        keyboardOptions =
            if (numerico) {
                KeyboardOptions(keyboardType = KeyboardType.Number)
            } else {
                KeyboardOptions.Default
            },
    )
}

@Composable
private fun FilaProtocolo(
    protocolo: ProtocoloConexion,
    onProtocolo: (ProtocoloConexion) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = protocolo == ProtocoloConexion.HTTPS,
            onClick = { onProtocolo(ProtocoloConexion.HTTPS) },
            label = { Text(stringResource(R.string.conexiones_protocolo_https)) },
        )
        FilterChip(
            selected = protocolo == ProtocoloConexion.HTTP,
            onClick = { onProtocolo(ProtocoloConexion.HTTP) },
            label = { Text(stringResource(R.string.conexiones_protocolo_http)) },
        )
    }
}

@Composable
private fun FilaFormato(
    formato: FormatoSalidaXtream,
    onFormato: (FormatoSalidaXtream) -> Unit,
) {
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        FilterChip(
            selected = formato == FormatoSalidaXtream.M3U8,
            onClick = { onFormato(FormatoSalidaXtream.M3U8) },
            label = { Text(stringResource(R.string.conexiones_formato_m3u8)) },
        )
        FilterChip(
            selected = formato == FormatoSalidaXtream.TS,
            onClick = { onFormato(FormatoSalidaXtream.TS) },
            label = { Text(stringResource(R.string.conexiones_formato_ts)) },
        )
    }
}

@Composable
private fun FilaInterruptor(
    valor: Boolean,
    onValor: (Boolean) -> Unit,
    etiqueta: Int,
    tag: String,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Switch(
            checked = valor,
            onCheckedChange = onValor,
            modifier = Modifier.testTag(tag),
        )
        Text(text = stringResource(etiqueta))
    }
}

@Composable
private fun DialogoConfirmarBaja(
    conexion: ConexionVista,
    onConfirmar: () -> Unit,
    onCancelar: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onCancelar,
        modifier = Modifier.testTag(TAG_ELIMINAR_CONEXION),
        title = { Text(stringResource(R.string.conexiones_eliminar_titulo)) },
        text = { Text(stringResource(R.string.conexiones_eliminar_mensaje, conexion.nombre)) },
        confirmButton = {
            TextButton(onClick = onConfirmar) { Text(stringResource(R.string.conexiones_eliminar_confirmar)) }
        },
        dismissButton = {
            TextButton(onClick = onCancelar) { Text(stringResource(R.string.conexiones_cancelar)) }
        },
    )
}

@Composable
private fun textoMetodo(tipo: TipoConexion): String =
    stringResource(
        when (tipo) {
            TipoConexion.XTREAM -> R.string.conexiones_metodo_xtream
            TipoConexion.M3U -> R.string.conexiones_metodo_m3u
        },
    )

@Composable
private fun textoEstado(estado: EstadoConexion): String =
    stringResource(
        when (estado) {
            EstadoConexion.SIN_PROBAR -> R.string.conexiones_estado_sin_probar
            EstadoConexion.CORRECTA -> R.string.conexiones_estado_correcta
            EstadoConexion.ERROR -> R.string.conexiones_estado_error
            EstadoConexion.ACTUALIZANDO -> R.string.conexiones_estado_actualizando
        },
    )
