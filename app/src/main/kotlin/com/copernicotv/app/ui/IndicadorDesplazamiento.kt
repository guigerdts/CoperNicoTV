package com.copernicotv.app.ui

import androidx.compose.foundation.ScrollState
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import com.copernicotv.app.R

/**
 * Aviso visible de que un bloque desplazable tiene contenido por debajo.
 *
 * H-02/H-03: con `font_scale` 2.0 los controles del catálogo no caben completos
 * y antes no había ninguna señal de que quedaran accesos ocultos. El aviso se
 * muestra solo mientras el desplazamiento tenga contenido pendiente, y
 * desaparece al llegar al final.
 */
@Composable
fun IndicadorDesplazamiento(
    desplazamiento: ScrollState,
    modifier: Modifier = Modifier,
    etiqueta: String = TAG_MAS_CONTROLES,
) {
    if (!desplazamiento.canScrollForward) return
    Text(
        text = stringResource(R.string.catalogo_desliza_para_mas),
        style = MaterialTheme.typography.labelSmall,
        modifier = modifier.fillMaxWidth().testTag(etiqueta),
    )
}
