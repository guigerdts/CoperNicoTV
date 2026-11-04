package com.copernicotv.app.ui

import androidx.compose.foundation.focusable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
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

/** Etiqueta del disparador de importación del canario. */
const val TAG_IMPORTAR = "importar-fixture"

/** Prefijo de las etiquetas de canal del catálogo. */
const val PREFIJO_TAG_CANAL = "canal-"

/**
 * Pantalla del canario: importar el fixture y navegar el catálogo con D-pad.
 *
 * Cada canal es un nodo enfocable, de modo que el recorrido con D-pad sea real
 * y no simulado. El orden visible es `Apariencia.orden`, no el orden de la
 * lista M3U por casualidad.
 */
@Composable
fun CatalogoScreen(
    estado: CatalogoUiState,
    onImportar: () -> Unit,
) {
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                text = stringResource(R.string.catalogo_titulo),
                style = MaterialTheme.typography.headlineMedium,
            )
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
            if (estado.canales.isEmpty()) {
                Text(
                    text = stringResource(R.string.catalogo_vacio),
                    style = MaterialTheme.typography.bodyMedium,
                )
            } else {
                LazyColumn(
                    modifier = Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    itemsIndexed(estado.canales) { indice, nombre ->
                        Text(
                            text = nombre,
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
            }
        }
    }
}
