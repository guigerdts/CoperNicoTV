package com.copernicotv.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.tv.material3.MaterialTheme as MaterialThemeTv

/**
 * Tema único del canario: oscuro, apto para TV y móvil.
 *
 * En TV (E7-T1) la shell vive bajo `androidx.tv.material3.MaterialTheme`
 * (Compose for TV); el contenido sigue en Material 3 para que las pantallas
 * compartidas no cambien. E7-T4 añadirá el tema claro sin duplicar esta rama.
 */
@Composable
fun CoperNicoTheme(
    esTv: Boolean,
    contenido: @Composable () -> Unit,
) {
    MaterialTheme(colorScheme = darkColorScheme()) {
        if (esTv) {
            MaterialThemeTv { contenido() }
        } else {
            contenido()
        }
    }
}
