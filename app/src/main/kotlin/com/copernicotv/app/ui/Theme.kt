package com.copernicotv.app.ui

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.sp
import com.copernicotv.core.tema.TemaUi
import androidx.tv.material3.MaterialTheme as MaterialThemeTv
import androidx.tv.material3.darkColorScheme as darkColorSchemeTv
import androidx.tv.material3.lightColorScheme as lightColorSchemeTv

/** Base de la escala de TV: la tipografía por defecto de Material 3. */
private val tipografiaBase: Typography = Typography()

/**
 * Escala tipográfica de TV de DC-11 (umbrales mínimos de P2).
 *
 * La shell de TV comparte pantallas con el móvil, y la tipografía por
 * defecto de Material 3 queda por debajo de los mínimos de legibilidad a
 * 10 pies: cuerpo ≥ 18 sp, título de elemento ≥ 24 sp, encabezado de
 * sección ≥ 32 sp y metadatos ≥ 16 sp. Esta escala solo se aplica en TV
 * dentro de `CoperNicoTheme`; el móvil conserva la tipografía por defecto
 * hasta la adaptación de E7-T3. `CanarioFocoDpadTvTest` verifica los
 * valores contra DC-11.
 *
 * La base se declara antes: las propiedades top-level se inicializan en
 * orden de declaración y `TipografiaTv` la consume al componerse.
 */
val TipografiaTv: Typography =
    tipografiaBase.copy(
        headlineMedium = tipografiaBase.headlineMedium.copy(fontSize = 32.sp),
        titleLarge = tipografiaBase.titleLarge.copy(fontSize = 28.sp),
        titleMedium = tipografiaBase.titleMedium.copy(fontSize = 24.sp),
        titleSmall = tipografiaBase.titleSmall.copy(fontSize = 18.sp),
        bodyLarge = tipografiaBase.bodyLarge.copy(fontSize = 18.sp),
        bodyMedium = tipografiaBase.bodyMedium.copy(fontSize = 18.sp),
        bodySmall = tipografiaBase.bodySmall.copy(fontSize = 16.sp),
        labelSmall = tipografiaBase.labelSmall.copy(fontSize = 16.sp),
        labelMedium = tipografiaBase.labelMedium.copy(fontSize = 16.sp),
    )

/**
 * Tema del canario (S43): claro u oscuro, apto para TV y móvil.
 *
 * En TV (E7-T1) la shell vive bajo `androidx.tv.material3.MaterialTheme`
 * (Compose for TV); el contenido sigue en Material 3 para que las pantallas
 * compartidas no cambien, con la escala tipográfica de DC-11 aplicada solo
 * en TV (E7-T2). El parámetro `tema` (E7-T4) elige el esquema de color en
 * ambas ramas sin duplicarlas; el default conserva el oscuro previo.
 */
@Composable
fun CoperNicoTheme(
    esTv: Boolean,
    tema: TemaUi = TemaUi.POR_DEFECTO,
    contenido: @Composable () -> Unit,
) {
    val claro = tema == TemaUi.CLARO
    val esquemaTv =
        if (claro) {
            lightColorSchemeTv()
        } else {
            darkColorSchemeTv()
        }
    MaterialTheme(colorScheme = if (claro) lightColorScheme() else darkColorScheme()) {
        if (esTv) {
            MaterialTheme(typography = TipografiaTv) {
                MaterialThemeTv(colorScheme = esquemaTv) { contenido() }
            }
        } else {
            contenido()
        }
    }
}
