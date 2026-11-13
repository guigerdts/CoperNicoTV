package com.copernicotv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
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
import com.copernicotv.app.R
import com.copernicotv.app.data.DiagnosticoImportacion

/**
 * Diálogo de «Importar desde URL» (E7-T7).
 *
 * No se cierra al Guardar: la fase en curso, el error con su causa y el número
 * de canales cargados viven en [ImportacionUrlUi] y se muestran aquí hasta que
 * el usuario cierra. En fallo los campos conservan el nombre y la URL, así que
 * reintentar no vuelve a pedir las credenciales.
 */
@Composable
internal fun DialogoImportarUrl(
    importacion: ImportacionUrlUi?,
    importando: Boolean,
    onGuardar: (String, String) -> Unit,
    onCancelar: () -> Unit,
) {
    var nombre by remember { mutableStateOf("") }
    var url by remember { mutableStateOf("") }
    // Durante la importación el diálogo no se cierra: la fase, el error con su
    // causa y el resultado viven en [importacion] hasta que el usuario cierra.
    val enCurso = importando && importacion != null && importacion.canales == null && importacion.error == null
    AlertDialog(
        onDismissRequest = { if (!enCurso) onCancelar() },
        title = { Text(stringResource(R.string.catalogo_importar_url)) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = nombre,
                    onValueChange = { nombre = it },
                    label = { Text(stringResource(R.string.catalogo_nombre_fuente)) },
                    singleLine = true,
                    enabled = !enCurso,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_DIALOGO_NOMBRE),
                )
                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text(stringResource(R.string.catalogo_url_lista)) },
                    singleLine = true,
                    enabled = !enCurso,
                    modifier = Modifier.fillMaxWidth().testTag(TAG_DIALOGO_URL),
                )
                importacion?.let { estado -> EstadoImportacionUrl(estado) }
            }
        },
        confirmButton = {
            val cerrado = importacion?.canales != null
            TextButton(
                onClick = {
                    if (cerrado) {
                        onCancelar()
                    } else {
                        onGuardar(nombre.trim(), url.trim())
                    }
                },
                enabled = !enCurso && if (cerrado) true else nombre.isNotBlank() && url.isNotBlank(),
                modifier = Modifier.testTag(TAG_DIALOGO_GUARDAR),
            ) {
                Text(
                    stringResource(
                        if (cerrado) R.string.catalogo_cerrar else R.string.catalogo_guardar,
                    ),
                )
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCancelar,
                enabled = !enCurso,
                modifier = Modifier.testTag(TAG_DIALOGO_CANCELAR),
            ) {
                Text(stringResource(R.string.catalogo_cancelar))
            }
        },
    )
}

/** Fase, éxito o error con causa, según el estado de la importación. */
@Composable
private fun EstadoImportacionUrl(estado: ImportacionUrlUi) {
    when {
        estado.fase != null -> {
            Text(
                text = stringResource(textoDeFase(estado.fase)),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TAG_IMPORTAR_URL_FASE),
            )
        }
        estado.canales != null -> {
            Text(
                text = stringResource(R.string.catalogo_importar_completada, estado.canales),
                style = MaterialTheme.typography.bodyMedium,
                modifier = Modifier.testTag(TAG_IMPORTAR_URL_EXITO),
            )
        }
        estado.error != null -> {
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    text = stringResource(R.string.catalogo_error, estado.error),
                    style = MaterialTheme.typography.bodyMedium,
                    modifier = Modifier.testTag(TAG_IMPORTAR_URL_ERROR),
                )
                lineaDiagnostico(estado.diagnostico)?.let { detalle ->
                    Text(
                        text = stringResource(R.string.catalogo_importar_diagnostico, detalle),
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
                Text(
                    text = stringResource(R.string.catalogo_importar_reintentar),
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/** Recurso de la fase en curso; un código desconocido cae en la fase de descarga. */
private fun textoDeFase(codigo: String): Int =
    when (codigo) {
        "conectando" -> R.string.catalogo_importar_fase_conectando
        "procesando_m3u" -> R.string.catalogo_importar_fase_procesando
        "guardando" -> R.string.catalogo_importar_fase_guardando
        else -> R.string.catalogo_importar_fase_descargando
    }

/**
 * Línea de diagnóstico para la UI: solo campos técnicos acotados y sin datos
 * sensibles. Los campos ausentes no se muestran.
 */
private fun lineaDiagnostico(diagnostico: DiagnosticoImportacion?): String? {
    if (diagnostico == null) return null
    val partes =
        listOfNotNull(
            diagnostico.codigoHttp?.let { "HTTP $it" },
            diagnostico.tipoContenido,
            diagnostico.bytesDescargados?.let { "$it bytes" },
            diagnostico.entradasDetectadas?.let { "$it entradas" },
            diagnostico.motivo,
        )
    if (partes.isEmpty()) return null
    return partes.joinToString(" · ")
}
