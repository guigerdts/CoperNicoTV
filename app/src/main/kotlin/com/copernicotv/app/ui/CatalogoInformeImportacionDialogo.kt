package com.copernicotv.app.ui

import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.AnnotatedString

/**
 * Diálogo del informe de la última importación (E7-T7 ronda 7, S49).
 *
 * Presenta el informe técnico completo (mismo contrato que el botón del
 * diálogo de importación: sin URL, sin credenciales) y lo copia al
 * portapapeles. Se abre desde la pantalla de catálogo, sin depender del
 * diálogo de importación original.
 */
@Composable
internal fun DialogoInformeImportacion(
    informe: String,
    onCerrar: () -> Unit,
) {
    val portapapeles = LocalClipboardManager.current
    AlertDialog(
        onDismissRequest = onCerrar,
        title = { Text(stringResource(R.string.catalogo_informe_ultima_importacion)) },
        text = { Text(informe) },
        confirmButton = {
            TextButton(
                onClick = { portapapeles.setText(AnnotatedString(informe)) },
                modifier = Modifier.testTag(TAG_INFORME_IMPORTACION_COPIAR),
            ) {
                Text(stringResource(R.string.catalogo_importar_informe))
            }
        },
        dismissButton = {
            TextButton(
                onClick = onCerrar,
                modifier = Modifier.testTag(TAG_DIALOGO_CANCELAR),
            ) {
                Text(stringResource(R.string.catalogo_informe_ultima_importacion_cerrar))
            }
        },
    )
}
