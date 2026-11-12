package com.copernicotv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
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
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.focus.onFocusChanged
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.copernicotv.app.R
import com.copernicotv.app.player.EstadoPistas
import com.copernicotv.app.player.PistaDisponible
import com.copernicotv.app.player.PreferenciaPista
import com.copernicotv.app.player.TipoPista
import com.copernicotv.app.player.buscarPista
import com.copernicotv.app.player.etiquetaPista
import com.copernicotv.app.player.nombreIdioma

// Selectores y panel de pistas de E6-T3 (D3, S44).
//
// Vive aparte de la pantalla para que la lógica de presentación no compita con
// los controles de reproducción y para que cada foco tenga un único dueño.

/** Acciones de selección de pistas que la pantalla delega en el ViewModel. */
internal data class AccionesPistas(
    val alSeleccionarAudio: (PistaDisponible) -> Unit,
    val alAudioAutomatico: () -> Unit,
    val alSeleccionarSubtitulos: (PistaDisponible) -> Unit,
    val alSubtitulosAutomaticos: () -> Unit,
    val alDesactivarSubtitulos: () -> Unit,
)

/** Puntos de foco del reproductor, agrupados para no inflar las firmas. */
internal class EnfoquesControles(
    val alternar: FocusRequester,
    val audio: FocusRequester,
    val subtitulos: FocusRequester,
    val primeraOpcion: FocusRequester,
    val diagnostico: FocusRequester,
)

/** Panel abierto y sus dos transiciones; sobrevive a la recreación. */
internal class EstadoPanelPistas(
    val tipo: TipoPista?,
    val alAbrir: (TipoPista) -> Unit,
    val alCerrar: () -> Unit,
)

/**
 * Recuerda el panel abierto y devuelve el foco a su disparador al cerrarlo.
 *
 * Back cierra el panel antes que la pantalla. El auto-ocultado de controles
 * consulta [EstadoPanelPistas.tipo] para no robar el foco mientras está abierto.
 */
@Composable
internal fun recordarPanelPistas(
    enfoques: EnfoquesControles,
    alRevelar: () -> Unit,
): EstadoPanelPistas {
    var guardado by rememberSaveable { mutableStateOf<String?>(null) }
    var anterior by remember { mutableStateOf<TipoPista?>(null) }
    val tipo = guardado?.let { runCatching { TipoPista.valueOf(it) }.getOrNull() }
    BackHandler(enabled = tipo != null) { guardado = null }
    LaunchedEffect(guardado) {
        if (tipo != null) {
            anterior = tipo
            runCatching { enfoques.primeraOpcion.requestFocus() }
        } else {
            anterior?.let { previo ->
                val objetivo = if (previo == TipoPista.AUDIO) enfoques.audio else enfoques.subtitulos
                runCatching { objetivo.requestFocus() }
            }
        }
    }
    return EstadoPanelPistas(
        tipo = tipo,
        alAbrir = { elegido ->
            guardado = if (tipo == elegido) null else elegido.name
            alRevelar()
        },
        alCerrar = { guardado = null },
    )
}

/** Aviso persistente de preferencia guardada que el contenido no ofrece (S44d). */
@Composable
internal fun AvisoPistas(pistas: EstadoPistas) {
    if (!pistas.hayAviso) return
    val avisoAudio =
        if (pistas.avisoAudio) {
            stringResource(R.string.reproductor_aviso_audio, etiquetaPreferencia(pistas.preferenciaAudio))
        } else {
            null
        }
    val avisoSubtitulos =
        if (pistas.avisoSubtitulos) {
            stringResource(
                R.string.reproductor_aviso_subtitulos,
                etiquetaPreferencia(pistas.preferenciaSubtitulos),
            )
        } else {
            null
        }
    Text(
        text = listOfNotNull(avisoAudio, avisoSubtitulos).joinToString(" "),
        color = Color(0xFFFFB4AB),
        style = MaterialTheme.typography.labelMedium,
        modifier = Modifier.testTag(TAG_REPRODUCTOR_AVISO_PISTAS),
    )
}

/**
 * Fila con los selectores de audio y subtítulos.
 *
 * Solo aparece si el contenido publica pistas o si hay una preferencia guardada
 * que avisar: una ausencia total no inventa opciones (E6-T3).
 */
@Composable
internal fun FilaPistas(
    pistas: EstadoPistas,
    enfoques: EnfoquesControles,
    alAbrirPanel: (TipoPista) -> Unit,
) {
    val mostrarAudio = pistas.audio.isNotEmpty() || pistas.avisoAudio
    val mostrarSubtitulos = pistas.subtitulos.isNotEmpty() || pistas.avisoSubtitulos
    if (!mostrarAudio && !mostrarSubtitulos) return
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (mostrarAudio) {
            val texto =
                stringResource(
                    R.string.reproductor_audio_etiqueta,
                    textoActivo(pistas.audio) ?: stringResource(R.string.reproductor_pista_automatica),
                )
            BotonControl(
                etiqueta = stringResource(R.string.reproductor_selector_audio),
                tag = TAG_REPRODUCTOR_AUDIO,
                onClick = { alAbrirPanel(TipoPista.AUDIO) },
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(enfoques.audio),
            ) {
                Text(texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
        if (mostrarSubtitulos) {
            val texto =
                stringResource(
                    R.string.reproductor_subtitulos_etiqueta,
                    textoActivo(pistas.subtitulos) ?: stringResource(R.string.reproductor_subtitulos_desactivados),
                )
            BotonControl(
                etiqueta = stringResource(R.string.reproductor_selector_subtitulos),
                tag = TAG_REPRODUCTOR_SUBTITULOS,
                onClick = { alAbrirPanel(TipoPista.SUBTITULOS) },
                modifier =
                    Modifier
                        .weight(1f)
                        .focusRequester(enfoques.subtitulos),
            ) {
                Text(texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

/** Opciones del panel de pistas, ya resueltas para presentación. */
private data class OpcionPista(
    val tag: String,
    val texto: String,
    val descripcion: String,
    val seleccionada: Boolean,
    val habilitada: Boolean,
    val alSeleccionar: () -> Unit,
)

/** Panel inline de pistas; no interrumpe la reproducción ni recrea la sesión. */
@Composable
internal fun PanelPistas(
    tipo: TipoPista,
    pistas: EstadoPistas,
    enfoquePrimeraOpcion: FocusRequester,
    acciones: AccionesPistas,
    alCerrar: () -> Unit,
) {
    val opciones =
        when (tipo) {
            TipoPista.AUDIO -> opcionesAudio(pistas, acciones)
            TipoPista.SUBTITULOS -> opcionesSubtitulos(pistas, acciones)
        }
    val aviso =
        when (tipo) {
            TipoPista.AUDIO -> pistas.avisoAudio
            TipoPista.SUBTITULOS -> pistas.avisoSubtitulos
        }
    val preferencia =
        when (tipo) {
            TipoPista.AUDIO -> pistas.preferenciaAudio
            TipoPista.SUBTITULOS -> pistas.preferenciaSubtitulos
        }
    Column(
        modifier =
            Modifier
                .fillMaxWidth()
                .heightIn(max = 220.dp)
                .verticalScroll(rememberScrollState())
                .background(Color.Black.copy(alpha = 0.55f), RoundedCornerShape(8.dp))
                .padding(8.dp)
                .testTag(TAG_REPRODUCTOR_PANEL_PISTAS),
        // DC-11: separación entre objetivos táctiles adyacentes de 8 dp.
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text(
            text =
                stringResource(
                    if (tipo == TipoPista.AUDIO) R.string.reproductor_audio else R.string.reproductor_subtitulos,
                ),
            color = Color.White.copy(alpha = 0.8f),
            style = MaterialTheme.typography.labelMedium,
        )
        if (aviso) {
            Text(
                text = stringResource(R.string.reproductor_preferencia_guardada, etiquetaPreferencia(preferencia)),
                color = Color(0xFFFFB4AB),
                style = MaterialTheme.typography.labelMedium,
                modifier = Modifier.testTag(TAG_REPRODUCTOR_PREFERENCIA_GUARDADA),
            )
        }
        opciones.forEachIndexed { indice, opcion ->
            val modificador =
                if (indice == 0) {
                    Modifier
                        .fillMaxWidth()
                        .focusRequester(enfoquePrimeraOpcion)
                } else {
                    Modifier.fillMaxWidth()
                }
            BotonControl(
                etiqueta = opcion.descripcion,
                tag = opcion.tag,
                onClick = {
                    opcion.alSeleccionar()
                    alCerrar()
                },
                habilitado = opcion.habilitada,
                seleccionado = opcion.seleccionada,
                modifier = modificador,
            ) {
                Text(opcion.texto, maxLines = 1, overflow = TextOverflow.Ellipsis)
            }
        }
    }
}

@Composable
private fun opcionesAudio(
    pistas: EstadoPistas,
    acciones: AccionesPistas,
): List<OpcionPista> {
    val preferencia = pistas.preferenciaAudio
    val opciones =
        mutableListOf(
            OpcionPista(
                tag = TAG_REPRODUCTOR_AUDIO_AUTO,
                texto = stringResource(R.string.reproductor_pista_automatica),
                descripcion = stringResource(R.string.reproductor_pista_automatica),
                seleccionada = preferencia == PreferenciaPista.Automatica,
                habilitada = true,
                alSeleccionar = acciones.alAudioAutomatico,
            ),
        )
    pistas.audio.forEachIndexed { indice, pista ->
        val base = textoPista(pista, indice)
        opciones +=
            OpcionPista(
                tag = tagPistaAudio(indice),
                texto = textoOpcionPista(pista, base),
                descripcion = base,
                seleccionada = preferencia is PreferenciaPista.Pista && buscarPista(listOf(pista), preferencia) != null,
                habilitada = pista.soportada,
                alSeleccionar = { acciones.alSeleccionarAudio(pista) },
            )
    }
    return opciones
}

@Composable
private fun opcionesSubtitulos(
    pistas: EstadoPistas,
    acciones: AccionesPistas,
): List<OpcionPista> {
    val preferencia = pistas.preferenciaSubtitulos
    val opciones =
        mutableListOf(
            OpcionPista(
                tag = TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS,
                texto = stringResource(R.string.reproductor_subtitulos_desactivados),
                descripcion = stringResource(R.string.reproductor_subtitulos_desactivados),
                seleccionada = preferencia == PreferenciaPista.Desactivada,
                habilitada = true,
                alSeleccionar = acciones.alDesactivarSubtitulos,
            ),
            OpcionPista(
                tag = TAG_REPRODUCTOR_SUBTITULOS_AUTO,
                texto = stringResource(R.string.reproductor_pista_automatica),
                descripcion = stringResource(R.string.reproductor_pista_automatica),
                seleccionada = preferencia == PreferenciaPista.Automatica,
                habilitada = true,
                alSeleccionar = acciones.alSubtitulosAutomaticos,
            ),
        )
    pistas.subtitulos.forEachIndexed { indice, pista ->
        val base = textoPista(pista, indice)
        opciones +=
            OpcionPista(
                tag = tagPistaSubtitulos(indice),
                texto = textoOpcionPista(pista, base),
                descripcion = base,
                seleccionada = preferencia is PreferenciaPista.Pista && buscarPista(listOf(pista), preferencia) != null,
                habilitada = pista.soportada,
                alSeleccionar = { acciones.alSeleccionarSubtitulos(pista) },
            )
    }
    return opciones
}

/** Etiqueta de la pista activa dentro de una lista, o `null` si no hay ninguna. */
@Composable
private fun textoActivo(pistas: List<PistaDisponible>): String? {
    val indice = pistas.indexOfFirst { it.activa }
    if (indice < 0) return null
    return textoPista(pistas[indice], indice)
}

@Composable
private fun textoPista(
    pista: PistaDisponible,
    posicion: Int,
): String = etiquetaPista(pista) ?: stringResource(R.string.reproductor_pista_numero, posicion + 1)

@Composable
private fun textoOpcionPista(
    pista: PistaDisponible,
    base: String,
): String {
    val forzada = if (pista.forzada) stringResource(R.string.reproductor_pista_forzada, base) else base
    val activa = if (pista.activa) stringResource(R.string.reproductor_pista_activa, forzada) else forzada
    return if (pista.soportada) activa else stringResource(R.string.reproductor_pista_no_soportada, activa)
}

/** Etiqueta legible de una preferencia guardada, para avisos y paneles. */
@Composable
private fun etiquetaPreferencia(preferencia: PreferenciaPista): String =
    when (preferencia) {
        is PreferenciaPista.Pista ->
            preferencia.etiqueta?.trim()?.takeIf { it.isNotEmpty() }
                ?: nombreIdioma(preferencia.idioma)
                ?: stringResource(R.string.reproductor_idioma_no_indicado)
        PreferenciaPista.Automatica -> stringResource(R.string.reproductor_pista_automatica)
        PreferenciaPista.Desactivada -> stringResource(R.string.reproductor_subtitulos_desactivados)
    }

@Composable
internal fun BotonControl(
    etiqueta: String,
    tag: String,
    onClick: () -> Unit,
    habilitado: Boolean = true,
    seleccionado: Boolean = false,
    modifier: Modifier = Modifier,
    paddingHorizontal: Dp = 12.dp,
    contenido: @Composable () -> Unit,
) {
    var enfocado by remember { mutableStateOf(false) }
    val forma = RoundedCornerShape(8.dp)
    val descripcionEstado = if (seleccionado) stringResource(R.string.reproductor_pista_seleccionada) else null
    Box(
        modifier =
            modifier
                // DC-11: área táctil de fila de pista de al menos 48 dp.
                .heightIn(min = 48.dp)
                .testTag(tag)
                .onFocusChanged { enfocado = it.isFocused }
                .clip(forma)
                .background(if (seleccionado) Color.White.copy(alpha = 0.18f) else Color.Transparent)
                .border(
                    width = if (enfocado) 2.dp else 1.dp,
                    color = if (enfocado) Color.White else Color.White.copy(alpha = 0.32f),
                    shape = forma,
                )
                .clickable(enabled = habilitado, onClick = onClick)
                .semantics {
                    contentDescription = etiqueta
                    if (descripcionEstado != null) stateDescription = descripcionEstado
                }
                .alpha(if (habilitado) 1f else 0.45f)
                .padding(horizontal = paddingHorizontal, vertical = 8.dp),
        contentAlignment = Alignment.Center,
    ) {
        contenido()
    }
}
