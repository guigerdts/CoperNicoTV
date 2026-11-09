package com.copernicotv.app.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.res.Configuration
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import com.copernicotv.app.R
import com.copernicotv.app.player.DiagnosticoReproductor
import com.copernicotv.app.player.textoDiagnostico

/**
 * Panel de diagnóstico técnico de E6-T5.
 *
 * Muestra y copia el mismo texto: protocolo, URL saneada, fase, posición,
 * búfer, pistas y último error. No contiene la URL original ni credenciales
 * (S57a). Es común a la pantalla de reproducción y a la de error definitivo.
 */
@Composable
internal fun PanelDiagnostico(
    diagnostico: DiagnosticoReproductor,
    alCerrar: () -> Unit,
) {
    val contexto = LocalContext.current
    val esTelevision =
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
    val texto = textoDiagnostico(diagnostico)
    var copiado by remember { mutableStateOf(false) }
    val enfoqueCopiar = remember { FocusRequester() }
    LaunchedEffect(Unit) {
        if (esTelevision) runCatching { enfoqueCopiar.requestFocus() }
    }
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black.copy(alpha = 0.72f))
                // Consume los toques fuera del panel para que los controles de
                // debajo no reciban una interacción mientras está abierto. Va en
                // el padre: los hijos (botones) tienen prioridad de golpe y el
                // resto del área cae aquí.
                .pointerInput(Unit) { detectTapGestures { } }
                .testTag(TAG_REPRODUCTOR_PANEL_DIAGNOSTICO),
        contentAlignment = Alignment.Center,
    ) {
        Column(
            modifier =
                Modifier
                    .fillMaxWidth(0.92f)
                    .fillMaxHeight(0.85f)
                    .background(Color(0xFF101418), RoundedCornerShape(12.dp))
                    .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.reproductor_diagnostico_titulo),
                color = Color.White,
                style = MaterialTheme.typography.titleMedium,
            )
            Text(
                text = texto,
                color = Color.White.copy(alpha = 0.92f),
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
                modifier =
                    Modifier
                        .weight(1f)
                        .verticalScroll(rememberScrollState())
                        .testTag(TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO),
            )
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                BotonControl(
                    etiqueta = stringResource(R.string.reproductor_diagnostico_copiar),
                    tag = TAG_REPRODUCTOR_COPIAR_DIAGNOSTICO,
                    onClick = {
                        // Refleja el resultado real de la escritura: si el
                        // sistema rechaza la copia no avisamos como exitosa.
                        copiado = copiarAlPortapapeles(contexto, texto)
                    },
                    modifier = Modifier.focusRequester(enfoqueCopiar),
                ) {
                    Text(
                        stringResource(
                            if (copiado) {
                                R.string.reproductor_diagnostico_copiado
                            } else {
                                R.string.reproductor_diagnostico_copiar
                            },
                        ),
                    )
                }
                Spacer(modifier = Modifier.weight(1f))
                BotonControl(
                    etiqueta = stringResource(R.string.reproductor_diagnostico_cerrar),
                    tag = TAG_REPRODUCTOR_CERRAR_DIAGNOSTICO,
                    onClick = alCerrar,
                ) {
                    Text(stringResource(R.string.reproductor_volver))
                }
            }
        }
    }
}

/**
 * Escribe el diagnóstico en el portapapeles del sistema. Devuelve `true` solo
 * si la escritura se completó; el panel usa ese resultado para marcar el botón
 * como copiado y no anunciar un éxito que no ocurrió.
 */
private fun copiarAlPortapapeles(
    contexto: Context,
    texto: String,
): Boolean {
    val gestor = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager ?: return false
    return try {
        gestor.setPrimaryClip(ClipData.newPlainText("Diagnóstico", texto))
        true
    } catch (excepcion: SecurityException) {
        false
    }
}
