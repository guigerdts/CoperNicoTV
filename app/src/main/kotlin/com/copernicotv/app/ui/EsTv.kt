package com.copernicotv.app.ui

import android.content.res.Configuration
import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration

/**
 * Indica si la app corre en una pantalla de television.
 *
 * E7-T1 centraliza aqui la deteccion por modo de UI para que la shell y las
 * tareas de E7 consulten una sola fuente: aunque el modo TELEVISION no se
 * falsifica en pruebas de JVM, las pantallas instrumentadas del emulador de TV
 * ya lo reciben del sistema.
 */
@Composable
fun recordarEsTv(): Boolean =
    (LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK) ==
        Configuration.UI_MODE_TYPE_TELEVISION
