package com.copernicotv.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable

/** Tema único del canario: oscuro, apto para TV y móvil. */
@Composable
fun CoperNicoTheme(contenido: @Composable () -> Unit) {
    MaterialTheme(colorScheme = darkColorScheme(), content = contenido)
}
