package com.copernicotv.app.data

import com.copernicotv.core.identity.AvanceEtapaImportacion

/**
 * Callback de avance numérico (entradas, agrupadas, canales): coopera con la
 * cancelación vía [activo], verifica el vigilante (puede abortar con
 * `ImportacionAvanceDetenido`, E7-T7 S22) y registra con [tic].
 */
internal fun alimentadorTick(
    avance: AvanceImportacion,
    activo: () -> Unit,
    tic: (Int) -> Unit,
): (Int) -> Unit =
    { total ->
        activo()
        avance.verificar()
        tic(total)
    }

/**
 * Callback de avances por etapa (E7-T7 ronda 4, S34): coopera con la
 * cancelación vía [activo], verifica el vigilante y registra la etapa.
 */
internal fun alimentadorEtapas(
    avance: AvanceImportacion,
    activo: () -> Unit,
): (AvanceEtapaImportacion) -> Unit =
    { etapa ->
        activo()
        avance.verificar()
        avance.ticEtapa(etapa)
    }
