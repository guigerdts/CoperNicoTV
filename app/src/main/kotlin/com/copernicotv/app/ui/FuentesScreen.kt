package com.copernicotv.app.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.copernicotv.app.R
import com.copernicotv.app.data.DisponibilidadFuente
import com.copernicotv.app.data.IndicadoresFuente
import com.copernicotv.app.data.ResultadoActualizacionFuente

/** Etiqueta del botón que vuelve al catálogo. */
const val TAG_VOLVER_FUENTES = "volver-fuentes"

/** Etiqueta del indicador de carga de la pantalla. */
const val TAG_FUENTES_CARGANDO = "fuentes-cargando"

/** Etiqueta del error de carga de la pantalla. */
const val TAG_ERROR_FUENTES = "error-fuentes"

/** Etiqueta del estado sin Fuentes. */
const val TAG_SIN_FUENTES = "sin-fuentes"

/** Prefijo de la etiqueta de carga de una Fuente. */
const val TAG_FUENTE_CARGA = "fuente-carga"

/** Prefijo de la etiqueta de actualización de una Fuente. */
const val TAG_FUENTE_ACTUALIZACION = "fuente-actualizacion"

/** Prefijo de la etiqueta de disponibilidad de una Fuente. */
const val TAG_FUENTE_DISPONIBILIDAD = "fuente-disponibilidad"

/** Prefijo de la etiqueta del botón de Actualización de una Fuente. */
const val TAG_FUENTE_ACTUALIZAR = "fuente-actualizar"

/** Etiqueta de un indicador concreto de una Fuente. */
fun tagFuente(
    base: String,
    fuenteId: Long,
): String = "$base-$fuenteId"

/**
 * Pantalla de Fuentes (E4-T8).
 *
 * Muestra los tres indicadores de S7 por separado: carga, última Actualización
 * y disponibilidad. Ninguno se reutiliza como otro.
 */
@Composable
fun FuentesScreen(
    estado: FuentesUiState,
    onActualizar: (Long) -> Unit,
    onVolver: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier =
                Modifier
                    .fillMaxSize()
                    .verticalScroll(rememberScrollState())
                    .padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text(
                text = stringResource(R.string.fuentes_titulo),
                style = MaterialTheme.typography.headlineMedium,
            )
            Button(
                onClick = onVolver,
                modifier = Modifier.testTag(TAG_VOLVER_FUENTES),
            ) {
                Text(stringResource(R.string.fuentes_volver))
            }
            if (estado.cargando) {
                Text(
                    text = stringResource(R.string.fuentes_cargando),
                    modifier = Modifier.testTag(TAG_FUENTES_CARGANDO),
                )
            }
            estado.error?.let { codigo ->
                Text(
                    text = stringResource(R.string.fuentes_error, codigo),
                    modifier = Modifier.testTag(TAG_ERROR_FUENTES),
                )
            }
            if (!estado.cargando && estado.fuentes.isEmpty()) {
                Text(
                    text = stringResource(R.string.fuentes_sin_fuentes),
                    modifier = Modifier.testTag(TAG_SIN_FUENTES),
                )
            }
            estado.fuentes.forEach { fuente ->
                TarjetaFuente(fuente = fuente, onActualizar = onActualizar)
            }
        }
    }
}

@Composable
private fun TarjetaFuente(
    fuente: IndicadoresFuente,
    onActualizar: (Long) -> Unit,
) {
    Text(
        text = fuente.nombre,
        style = MaterialTheme.typography.titleMedium,
    )
    Text(
        text =
            if (fuente.cargando) {
                stringResource(R.string.fuentes_carga_en_curso)
            } else {
                stringResource(R.string.fuentes_carga_en_reposo)
            },
        modifier = Modifier.testTag(tagFuente(TAG_FUENTE_CARGA, fuente.fuenteId)),
    )
    Text(
        text = textoActualizacion(fuente.actualizacion),
        modifier = Modifier.testTag(tagFuente(TAG_FUENTE_ACTUALIZACION, fuente.fuenteId)),
    )
    Text(
        text =
            when (fuente.disponibilidad) {
                DisponibilidadFuente.DISPONIBLE -> stringResource(R.string.fuentes_disponible)
                DisponibilidadFuente.SIN_CONTENIDO -> stringResource(R.string.fuentes_sin_contenido)
            },
        modifier = Modifier.testTag(tagFuente(TAG_FUENTE_DISPONIBILIDAD, fuente.fuenteId)),
    )
    Button(
        onClick = { onActualizar(fuente.fuenteId) },
        enabled = !fuente.cargando,
        modifier = Modifier.testTag(tagFuente(TAG_FUENTE_ACTUALIZAR, fuente.fuenteId)),
    ) {
        Text(stringResource(R.string.fuentes_actualizar))
    }
}

@Composable
private fun textoActualizacion(resultado: ResultadoActualizacionFuente?): String =
    when {
        resultado == null -> stringResource(R.string.fuentes_sin_intentos)
        resultado.exito -> stringResource(R.string.fuentes_ultima_correcta)
        else -> stringResource(R.string.fuentes_ultima_fallida, resultado.causa.orEmpty())
    }
