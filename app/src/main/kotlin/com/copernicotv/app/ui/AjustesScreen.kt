package com.copernicotv.app.ui

import android.content.ActivityNotFoundException
import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
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
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.FilterChip
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copernicotv.app.R
import com.copernicotv.app.ota.FalloInstalacion
import com.copernicotv.app.ota.FalloOta
import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.ManifiestoActualizacion
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Etiqueta del botón que vuelve al catálogo. */
const val TAG_VOLVER_AJUSTES = "volver-ajustes"

/** Etiqueta del botón que comprueba si hay una versión nueva. */
const val TAG_BUSCAR_ACTUALIZACION = "buscar-actualizacion"

/** Etiqueta del botón que instala la versión descargada. */
const val TAG_INSTALAR_ACTUALIZACION = "instalar-actualizacion"

/** Etiqueta del botón que abre la autorización de instalación del sistema. */
const val TAG_PERMISO_INSTALACION = "permiso-instalacion"

/** Etiqueta del canal estable. */
const val TAG_CANAL_ESTABLE = "canal-estable"

/** Etiqueta del canal beta. */
const val TAG_CANAL_BETA = "canal-beta"

/** Etiqueta del avance de la comprobación o descarga. */
const val TAG_PROGRESO_OTA = "progreso-ota"

/** Etiqueta del mensaje del último intento. */
const val TAG_AVISO_OTA = "aviso-ota"

private val FORMATO_FECHA: DateTimeFormatter = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm")

/**
 * Pantalla Ajustes > Actualizaciones (S9).
 *
 * Una sola pantalla sirve a móvil y a TV: los controles son Material 3 con
 * altura mínima de 48 dp, así que el D-pad los recorre sin tratamiento
 * especial, igual que en Fuentes y Guía. Muestra la versión instalada, el
 * canal, la última comprobación, la versión disponible con sus notas, el
 * avance de la descarga y las dos acciones que requieren decisión del usuario:
 * autorizar la instalación y confirmarla en el diálogo del sistema.
 */
@Composable
fun AjustesScreen(
    estado: AjustesUiState,
    onBuscar: () -> Unit,
    onCambiarCanal: (CanalActualizacion) -> Unit,
    onInstalar: () -> Unit,
    onActualizarPermiso: () -> Unit,
    onOlvidarAviso: () -> Unit,
    onVolver: () -> Unit,
) {
    val contexto = LocalContext.current
    val lanzadorPermiso =
        rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
            // Al volver de los ajustes del sistema la autorización puede haber
            // cambiado; se relee en vez de suponerla.
            onActualizarPermiso()
        }
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .windowInsetsPadding(WindowInsets.safeDrawing)
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.ajustes_titulo),
                style = MaterialTheme.typography.headlineMedium,
            )
            TextButton(
                onClick = onVolver,
                modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_VOLVER_AJUSTES),
            ) {
                Text(stringResource(R.string.ajustes_volver))
            }
            Text(
                text = stringResource(R.string.ajustes_actualizaciones),
                style = MaterialTheme.typography.titleLarge,
            )
            Text(stringResource(R.string.ajustes_version_instalada, estado.versionInstalada))
            Text(stringResource(R.string.ajustes_canal))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                FilterChip(
                    selected = estado.canal == CanalActualizacion.ESTABLE,
                    onClick = { onCambiarCanal(CanalActualizacion.ESTABLE) },
                    label = { Text(stringResource(R.string.ajustes_canal_estable)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_CANAL_ESTABLE),
                )
                FilterChip(
                    selected = estado.canal == CanalActualizacion.BETA,
                    onClick = { onCambiarCanal(CanalActualizacion.BETA) },
                    label = { Text(stringResource(R.string.ajustes_canal_beta)) },
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_CANAL_BETA),
                )
            }
            Text(stringResource(R.string.ajustes_ultima_comprobacion, textoFecha(estado.ultimaComprobacionMs)))
            Button(
                onClick = onBuscar,
                enabled = !estado.comprobando,
                modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_BUSCAR_ACTUALIZACION),
            ) {
                Text(stringResource(if (estado.comprobando) R.string.ajustes_buscando else R.string.ajustes_buscar))
            }
            if (estado.comprobando) {
                val progreso = estado.progreso
                if (progreso == null) {
                    LinearProgressIndicator(modifier = Modifier.fillMaxWidth().testTag(TAG_PROGRESO_OTA))
                } else {
                    LinearProgressIndicator(
                        progress = { progreso / 100f },
                        modifier = Modifier.fillMaxWidth().testTag(TAG_PROGRESO_OTA),
                    )
                    Text(stringResource(R.string.ajustes_progreso, progreso))
                }
            }
            estado.disponible?.let { manifiesto ->
                TarjetaDisponible(
                    manifiesto = manifiesto,
                    puedeInstalar = estado.puedeInstalar,
                    permisoConcedido = estado.permisoInstalacion,
                    onInstalar = onInstalar,
                    onPedirPermiso = {
                        val intent =
                            Intent(
                                Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                                Uri.parse("package:${contexto.packageName}"),
                            )
                        try {
                            lanzadorPermiso.launch(intent)
                        } catch (sinAjustes: ActivityNotFoundException) {
                            // Algunos equipos de TV no exponen esta pantalla; se
                            // relee el permiso y el aviso explica el estado real.
                            onActualizarPermiso()
                        }
                    },
                )
            }
            estado.aviso?.let { aviso ->
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    Text(
                        text = textoAviso(aviso),
                        modifier = Modifier.weight(1f).testTag(TAG_AVISO_OTA),
                    )
                    TextButton(onClick = onOlvidarAviso) {
                        Text(stringResource(R.string.ajustes_aviso_cerrar))
                    }
                }
            }
        }
    }
}

@Composable
private fun TarjetaDisponible(
    manifiesto: ManifiestoActualizacion,
    puedeInstalar: Boolean,
    permisoConcedido: Boolean,
    onInstalar: () -> Unit,
    onPedirPermiso: () -> Unit,
) {
    Surface(
        color = MaterialTheme.colorScheme.surfaceVariant,
        shape = MaterialTheme.shapes.medium,
        modifier = Modifier.fillMaxWidth(),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Text(
                text = stringResource(R.string.ajustes_disponible, manifiesto.versionName),
                style = MaterialTheme.typography.titleMedium,
            )
            Text(stringResource(R.string.ajustes_publicada, textoFecha(manifiesto.publicadoEn.toEpochMilli())))
            Text(stringResource(R.string.ajustes_tamano, textoTamano(manifiesto.tamanoBytes)))
            if (manifiesto.obligatoria) {
                Text(stringResource(R.string.ajustes_obligatoria))
            }
            if (manifiesto.notas.isNotBlank()) {
                Text(manifiesto.notas)
            }
            if (!permisoConcedido) {
                Button(
                    onClick = onPedirPermiso,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_PERMISO_INSTALACION),
                ) {
                    Text(stringResource(R.string.ajustes_pedir_permiso))
                }
            } else if (puedeInstalar) {
                Button(
                    onClick = onInstalar,
                    modifier = Modifier.heightIn(min = 48.dp).testTag(TAG_INSTALAR_ACTUALIZACION),
                ) {
                    Text(stringResource(R.string.ajustes_instalar))
                }
            }
        }
    }
}

@Composable
private fun textoFecha(ms: Long?): String =
    if (ms == null) {
        stringResource(R.string.ajustes_nunca)
    } else {
        FORMATO_FECHA.withZone(ZoneId.systemDefault()).format(Instant.ofEpochMilli(ms))
    }

private fun textoTamano(bytes: Long): String = String.format(Locale.getDefault(), "%.1f MB", bytes / (1024.0 * 1024.0))

@Composable
private fun textoAviso(aviso: AvisoAjustes): String =
    when (aviso) {
        AvisoAjustes.AlDia -> stringResource(R.string.ajustes_al_dia)
        is AvisoAjustes.Incompatible -> stringResource(R.string.ajustes_incompatible, aviso.versionName)
        is AvisoAjustes.Fallo -> stringResource(textoFallo(aviso.tipo))
        is AvisoAjustes.SinPermiso -> stringResource(R.string.ajustes_sin_permiso)
        is AvisoAjustes.InstalacionFallida ->
            stringResource(
                R.string.ajustes_instalacion_fallida,
                stringResource(textoFalloInstalacion(aviso.tipo)),
            )
        is AvisoAjustes.InstalacionRechazada -> stringResource(R.string.ajustes_instalacion_rechazada)
        AvisoAjustes.Instalada -> stringResource(R.string.ajustes_instalada)
        AvisoAjustes.Cancelada -> stringResource(R.string.ajustes_cancelada)
        AvisoAjustes.ConfirmacionPendiente -> stringResource(R.string.ajustes_confirmacion)
    }

private fun textoFallo(tipo: FalloOta): Int =
    when (tipo) {
        FalloOta.CONFIGURACION -> R.string.ajustes_fallo_configuracion
        FalloOta.SIN_RED -> R.string.ajustes_fallo_sin_red
        FalloOta.SERVIDOR -> R.string.ajustes_fallo_servidor
        FalloOta.FIRMA -> R.string.ajustes_fallo_firma
        FalloOta.MANIFIESTO -> R.string.ajustes_fallo_manifiesto
        FalloOta.DESCARGA -> R.string.ajustes_fallo_descarga
        FalloOta.VERIFICACION -> R.string.ajustes_fallo_verificacion
    }

private fun textoFalloInstalacion(tipo: FalloInstalacion): Int =
    if (tipo == FalloInstalacion.PERMISO) {
        R.string.ajustes_sin_permiso
    } else {
        R.string.ajustes_instalacion_no_preparada
    }
