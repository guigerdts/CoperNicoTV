package com.copernicotv.app.ui

import android.app.Activity
import android.content.Context
import android.content.ContextWrapper
import android.content.res.Configuration
import android.view.View
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.focusable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.WindowInsetsSides
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.only
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.onPreviewKeyEvent
import androidx.compose.ui.input.key.type
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.copernicotv.app.R
import com.copernicotv.app.player.EstadoPistas
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.player.ModoAspecto
import com.copernicotv.app.player.SALTO_BUSQUEDA_MS
import com.copernicotv.app.player.TIEMPO_AUTO_OCULTAR_MS
import com.copernicotv.app.player.debeOcultarse
import com.copernicotv.app.player.formatearTiempo
import com.copernicotv.app.player.nombreEstadoMotor
import kotlinx.coroutines.delay

/** Etiqueta de la superficie de reproducción (E6-T1). */
const val TAG_REPRODUCTOR = "reproductor"

/** Etiqueta del estado observable del motor, con posición en milisegundos. */
const val TAG_REPRODUCTOR_ESTADO = "reproductor-estado"

/** Etiqueta de la fase legible por una persona (E6-T2). */
const val TAG_REPRODUCTOR_FASE = "reproductor-fase"

/** Etiqueta del error de reproducción. */
const val TAG_REPRODUCTOR_ERROR = "reproductor-error"

/** Etiqueta del control de vuelta al catálogo. */
const val TAG_REPRODUCTOR_VOLVER = "reproductor-volver"

/** Etiqueta de la barra de controles; desaparece con el auto-ocultado. */
const val TAG_REPRODUCTOR_CONTROLES = "reproductor-controles"

/** Etiqueta del control play/pausa. */
const val TAG_REPRODUCTOR_ALTERNAR = "reproductor-alternar"

/** Etiqueta de la barra de progreso. */
const val TAG_REPRODUCTOR_PROGRESO = "reproductor-progreso"

/** Etiqueta del tiempo transcurrido. */
const val TAG_REPRODUCTOR_TRANSCURRIDO = "reproductor-transcurrido"

/** Etiqueta de la duración del contenido. */
const val TAG_REPRODUCTOR_DURACION = "reproductor-duracion"

/** Etiqueta del salto de búsqueda hacia atrás. */
const val TAG_REPRODUCTOR_RETROCEDER = "reproductor-retroceder"

/** Etiqueta del salto de búsqueda hacia adelante. */
const val TAG_REPRODUCTOR_AVANZAR = "reproductor-avanzar"

/** Etiqueta del selector de ajuste de pantalla. */
const val TAG_REPRODUCTOR_ASPECTO = "reproductor-aspecto"

/** Etiqueta del indicador de búfer. */
const val TAG_REPRODUCTOR_BUFFER = "reproductor-buffer"

/** Etiqueta del selector de pista de audio (E6-T3). */
const val TAG_REPRODUCTOR_AUDIO = "reproductor-audio"

/** Etiqueta del selector de subtítulos (E6-T3). */
const val TAG_REPRODUCTOR_SUBTITULOS = "reproductor-subtitulos"

/** Etiqueta del panel de pistas abierto. */
const val TAG_REPRODUCTOR_PANEL_PISTAS = "reproductor-panel-pistas"

/** Etiqueta del aviso de preferencia de pista no disponible. */
const val TAG_REPRODUCTOR_AVISO_PISTAS = "reproductor-aviso-pistas"

/** Etiqueta de la línea «Preferida: …» dentro del panel de pistas. */
const val TAG_REPRODUCTOR_PREFERENCIA_GUARDADA = "reproductor-preferencia-guardada"

/** Etiqueta de la opción de audio automático. */
const val TAG_REPRODUCTOR_AUDIO_AUTO = "reproductor-audio-auto"

/** Etiqueta de la opción de subtítulos automáticos. */
const val TAG_REPRODUCTOR_SUBTITULOS_AUTO = "reproductor-subtitulos-auto"

/** Etiqueta de la opción de subtítulos desactivados. */
const val TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS = "reproductor-subtitulos-desactivados"

/** Etiqueta de la opción de audio en la posición [indice] de la lista visible. */
fun tagPistaAudio(indice: Int): String = "$TAG_REPRODUCTOR_AUDIO-$indice"

/** Etiqueta de la opción de subtítulos en la posición [indice] de la lista visible. */
fun tagPistaSubtitulos(indice: Int): String = "$TAG_REPRODUCTOR_SUBTITULOS-$indice"

/**
 * Pantalla de reproducción de un Canal con Media3 (E6-T1, ampliada en E6-T2).
 *
 * La sesión `ExoPlayer` vive en el ViewModel: rotar, cambiar el modo de aspecto
 * o adjuntar de nuevo la superficie no recrean el motor ni reinician la
 * posición. La pantalla solo cablea la superficie, los controles propios
 * (táctiles y D-pad) y el modo inmersivo.
 */
@Composable
fun ReproductorScreen(
    canalId: Long,
    onVolver: () -> Unit,
    viewModel: ReproductorViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    val sesionEstado by viewModel.sesionEstado.collectAsStateWithLifecycle()
    val estadoPistas by viewModel.estadoPistas.collectAsStateWithLifecycle()
    LaunchedEffect(canalId) { viewModel.cargar(canalId) }
    val salir = {
        viewModel.cerrar()
        onVolver()
    }
    BackHandler(onBack = salir)
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .background(Color.Black)
                .testTag(TAG_REPRODUCTOR),
    ) {
        when (val actual = estado) {
            ReproductorUiState.Cargando ->
                CircularProgressIndicator(
                    modifier = Modifier.align(Alignment.Center),
                    color = MaterialTheme.colorScheme.primary,
                )
            is ReproductorUiState.Error -> ErrorReproduccion(actual.codigo, onVolver = salir)
            is ReproductorUiState.Listo ->
                SuperficieReproductor(
                    datos = actual,
                    estado = sesionEstado,
                    pistas = estadoPistas,
                    motor = viewModel.motor,
                    onAlternar = viewModel::alternar,
                    onDesplazar = viewModel::desplazar,
                    onBuscarA = viewModel::buscarA,
                    onSalir = salir,
                    accionesPistas =
                        AccionesPistas(
                            alSeleccionarAudio = viewModel::seleccionarAudio,
                            alAudioAutomatico = viewModel::usarAudioAutomatico,
                            alSeleccionarSubtitulos = viewModel::seleccionarSubtitulos,
                            alSubtitulosAutomaticos = viewModel::usarSubtitulosAutomaticos,
                            alDesactivarSubtitulos = viewModel::desactivarSubtitulos,
                        ),
                )
        }
    }
}

@Composable
private fun SuperficieReproductor(
    datos: ReproductorUiState.Listo,
    estado: EstadoSesion,
    pistas: EstadoPistas,
    motor: ExoPlayer?,
    onAlternar: () -> Unit,
    onDesplazar: (Long) -> Unit,
    onBuscarA: (Long) -> Unit,
    onSalir: () -> Unit,
    accionesPistas: AccionesPistas,
) {
    val vista = LocalView.current
    val fase = estado.estado
    val esTelevision =
        LocalConfiguration.current.uiMode and Configuration.UI_MODE_TYPE_MASK ==
            Configuration.UI_MODE_TYPE_TELEVISION
    var controlesVisibles by rememberSaveable { mutableStateOf(true) }
    var interacciones by remember { mutableLongStateOf(0L) }
    var modoGuardado by rememberSaveable { mutableStateOf(ModoAspecto.AJUSTAR.name) }
    var arrastreMs by remember { mutableStateOf<Long?>(null) }
    val modo = ModoAspecto.valueOf(modoGuardado)
    val enfoqueRaiz = remember { FocusRequester() }
    val enfoqueAlternar = remember { FocusRequester() }
    val enfoques =
        remember {
            EnfoquesControles(
                alternar = enfoqueAlternar,
                audio = FocusRequester(),
                subtitulos = FocusRequester(),
                primeraOpcion = FocusRequester(),
            )
        }
    val revelar = {
        interacciones += 1
        controlesVisibles = true
    }
    val panelPistas = recordarPanelPistas(enfoques, revelar)

    InmersivoPantallaCompleta(vista)
    EfectosReproductor(
        vista = vista,
        fase = fase,
        controlesVisibles = controlesVisibles,
        interacciones = interacciones,
        panelAbierto = panelPistas.tipo != null,
        esTelevision = esTelevision,
        enfoqueRaiz = enfoqueRaiz,
        enfoqueAlternar = enfoqueAlternar,
        alTerminar = revelar,
        alOcultar = {
            panelPistas.alCerrar()
            controlesVisibles = false
        },
    )
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .focusRequester(enfoqueRaiz)
                .onPreviewKeyEvent { evento -> teclaDpad(evento, controlesVisibles, revelar, onAlternar, onDesplazar) }
                .toquesReproductor(
                    fase = fase,
                    controlesVisibles = controlesVisibles,
                    alOcultar = {
                        panelPistas.alCerrar()
                        controlesVisibles = false
                    },
                    alRevelar = revelar,
                )
                .focusable(),
    ) {
        SuperficieVideo(motor, modo)
        IndicadorCarga(fase)
        DiagnosticoEstado(datos, fase, estado.posicionMs)
        if (controlesVisibles) {
            Controles(
                datos = datos,
                estado = estado,
                fase = fase,
                modo = modo,
                arrastreMs = arrastreMs,
                pistas = pistas,
                panelPistas = panelPistas,
                enfoques = enfoques,
                acciones =
                    AccionesReproductor(
                        alAlternar = {
                            revelar()
                            onAlternar()
                        },
                        alDesplazar = { salto ->
                            revelar()
                            onDesplazar(salto)
                        },
                        alArrastrar = { valor -> arrastreMs = valor },
                        alTerminarArrastre = {
                            arrastreMs?.let { valor ->
                                onBuscarA(valor)
                                revelar()
                            }
                            arrastreMs = null
                        },
                        alCambiarModo = {
                            revelar()
                            modoGuardado = modo.siguiente().name
                        },
                        alSalir = {
                            revelar()
                            onSalir()
                        },
                    ),
                accionesPistas = accionesPistas,
            )
        }
    }
}

/** Acciones que la barra de controles delega en la pantalla. */
private data class AccionesReproductor(
    val alAlternar: () -> Unit,
    val alDesplazar: (Long) -> Unit,
    val alArrastrar: (Long) -> Unit,
    val alTerminarArrastre: () -> Unit,
    val alCambiarModo: () -> Unit,
    val alSalir: () -> Unit,
)

@Composable
private fun BoxScope.Controles(
    datos: ReproductorUiState.Listo,
    estado: EstadoSesion,
    fase: EstadoReproductor,
    modo: ModoAspecto,
    arrastreMs: Long?,
    pistas: EstadoPistas,
    panelPistas: EstadoPanelPistas,
    enfoques: EnfoquesControles,
    acciones: AccionesReproductor,
    accionesPistas: AccionesPistas,
) {
    Box(
        modifier =
            Modifier
                .fillMaxSize()
                .testTag(TAG_REPRODUCTOR_CONTROLES),
    ) {
        BarraSuperior(datos, fase, acciones.alSalir)
        BarraInferior(
            estado = estado,
            fase = fase,
            modo = modo,
            arrastreMs = arrastreMs,
            pistas = pistas,
            panelPistas = panelPistas,
            enfoques = enfoques,
            acciones = acciones,
            accionesPistas = accionesPistas,
        )
    }
}

@Composable
private fun BoxScope.BarraSuperior(
    datos: ReproductorUiState.Listo,
    fase: EstadoReproductor,
    alSalir: () -> Unit,
) {
    Column(
        modifier =
            Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Black.copy(alpha = 0.72f), Color.Transparent),
                    ),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Top))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            BotonControl(
                etiqueta = stringResource(R.string.reproductor_volver),
                tag = TAG_REPRODUCTOR_VOLVER,
                onClick = alSalir,
            ) { Text(stringResource(R.string.reproductor_volver)) }
            Column(
                modifier =
                    Modifier
                        .weight(1f)
                        .padding(horizontal = 8.dp),
            ) {
                Text(
                    text = datos.nombre ?: stringResource(R.string.catalogo_sin_nombre),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    text = etiquetaFase(fase),
                    color = Color.White.copy(alpha = 0.8f),
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.testTag(TAG_REPRODUCTOR_FASE),
                )
            }
        }
    }
}

@Composable
private fun BoxScope.BarraInferior(
    estado: EstadoSesion,
    fase: EstadoReproductor,
    modo: ModoAspecto,
    arrastreMs: Long?,
    pistas: EstadoPistas,
    panelPistas: EstadoPanelPistas,
    enfoques: EnfoquesControles,
    acciones: AccionesReproductor,
    accionesPistas: AccionesPistas,
) {
    val duracion = estado.duracionMs
    val posicion = (arrastreMs ?: estado.posicionMs).coerceIn(0L, maxOf(duracion, 0L))
    Column(
        modifier =
            Modifier
                .align(Alignment.BottomCenter)
                .fillMaxWidth()
                .background(
                    Brush.verticalGradient(
                        listOf(Color.Transparent, Color.Black.copy(alpha = 0.72f)),
                    ),
                )
                .windowInsetsPadding(WindowInsets.safeDrawing.only(WindowInsetsSides.Bottom))
                .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalArrangement = Arrangement.spacedBy(4.dp),
    ) {
        panelPistas.tipo?.let { tipo ->
            PanelPistas(tipo, pistas, enfoques.primeraOpcion, accionesPistas, panelPistas.alCerrar)
        }
        AvisoPistas(pistas)
        FilaProgreso(duracion, posicion, estado.puedeBuscar, acciones)
        FilaBotones(fase, modo, estado.puedeBuscar, enfoques.alternar, acciones)
        FilaPistas(pistas, enfoques, panelPistas.alAbrir)
    }
}

/** Aviso persistente de preferencia guardada que el contenido no ofrece (S44d). */

@Composable
private fun FilaProgreso(
    duracion: Long,
    posicion: Long,
    puedeBuscar: Boolean,
    acciones: AccionesReproductor,
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(
            text = formatearTiempo(posicion),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(TAG_REPRODUCTOR_TRANSCURRIDO),
        )
        Slider(
            value = posicion.toFloat(),
            onValueChange = { acciones.alArrastrar(it.toLong()) },
            onValueChangeFinished = acciones.alTerminarArrastre,
            valueRange = 0f..maxOf(duracion, 1L).toFloat(),
            enabled = puedeBuscar,
            modifier =
                Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
                    .testTag(TAG_REPRODUCTOR_PROGRESO),
        )
        Text(
            text = formatearTiempo(if (duracion > 0L) duracion else -1L),
            color = Color.White,
            style = MaterialTheme.typography.labelMedium,
            modifier = Modifier.testTag(TAG_REPRODUCTOR_DURACION),
        )
    }
}

@Composable
private fun FilaBotones(
    fase: EstadoReproductor,
    modo: ModoAspecto,
    puedeBuscar: Boolean,
    enfoqueAlternar: FocusRequester,
    acciones: AccionesReproductor,
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        BotonControl(
            etiqueta = stringResource(R.string.reproductor_pausar),
            tag = TAG_REPRODUCTOR_ALTERNAR,
            onClick = acciones.alAlternar,
            modifier = Modifier.focusRequester(enfoqueAlternar),
        ) {
            Text(
                stringResource(
                    if (fase == EstadoReproductor.REPRODUCIENDO) {
                        R.string.reproductor_pausar
                    } else {
                        R.string.reproductor_play
                    },
                ),
            )
        }
        BotonControl(
            etiqueta = stringResource(R.string.reproductor_retroceder),
            tag = TAG_REPRODUCTOR_RETROCEDER,
            onClick = { acciones.alDesplazar(-SALTO_BUSQUEDA_MS) },
            habilitado = puedeBuscar,
        ) { Text("−10 s") }
        BotonControl(
            etiqueta = stringResource(R.string.reproductor_avanzar),
            tag = TAG_REPRODUCTOR_AVANZAR,
            onClick = { acciones.alDesplazar(SALTO_BUSQUEDA_MS) },
            habilitado = puedeBuscar,
        ) { Text("+10 s") }
        Spacer(modifier = Modifier.weight(1f))
        BotonControl(
            etiqueta = stringResource(R.string.reproductor_aspecto),
            tag = TAG_REPRODUCTOR_ASPECTO,
            onClick = acciones.alCambiarModo,
        ) { Text(stringResource(modo.recursoEtiqueta())) }
    }
}

/** Superficie del motor: una única instancia de `PlayerView` por sesión. */
@Composable
private fun SuperficieVideo(
    motor: ExoPlayer?,
    modo: ModoAspecto,
) {
    if (motor == null) return
    AndroidView(
        factory = { contextoVista ->
            PlayerView(contextoVista).apply {
                useController = false
                player = motor
                resizeMode = modo.aResizeMode()
            }
        },
        update = { vistaReproductor ->
            vistaReproductor.player = motor
            vistaReproductor.resizeMode = modo.aResizeMode()
        },
        modifier = Modifier.fillMaxSize(),
    )
}

@Composable
private fun BoxScope.IndicadorCarga(fase: EstadoReproductor) {
    if (fase != EstadoReproductor.CARGANDO && fase != EstadoReproductor.BUFFERING) return
    CircularProgressIndicator(
        modifier =
            Modifier
                .align(Alignment.Center)
                .testTag(TAG_REPRODUCTOR_BUFFER),
        color = Color.White,
    )
}

/**
 * Diagnóstico estable de E6-T1: invisible, pero siempre presente para que la
 * evidencia de CI no dependa de que los controles estén visibles.
 */
@Composable
private fun BoxScope.DiagnosticoEstado(
    datos: ReproductorUiState.Listo,
    fase: EstadoReproductor,
    posicionMs: Long,
) {
    Text(
        text = "${datos.protocolo.etiqueta} · ${nombreEstadoMotor(fase)}|$posicionMs",
        modifier =
            Modifier
                .align(Alignment.BottomStart)
                .alpha(0f)
                .testTag(TAG_REPRODUCTOR_ESTADO),
    )
}

/** Temporizadores y foco del reproductor, separados de la composición visual. */
@Composable
private fun EfectosReproductor(
    vista: View,
    fase: EstadoReproductor,
    controlesVisibles: Boolean,
    interacciones: Long,
    panelAbierto: Boolean,
    esTelevision: Boolean,
    enfoqueRaiz: FocusRequester,
    enfoqueAlternar: FocusRequester,
    alTerminar: () -> Unit,
    alOcultar: () -> Unit,
) {
    LaunchedEffect(fase) {
        vista.keepScreenOn =
            fase == EstadoReproductor.REPRODUCIENDO || fase == EstadoReproductor.BUFFERING
        if (fase == EstadoReproductor.TERMINADO) alTerminar()
    }
    LaunchedEffect(controlesVisibles, interacciones, fase, panelAbierto) {
        if (controlesVisibles && !panelAbierto && debeOcultarse(fase)) {
            delay(TIEMPO_AUTO_OCULTAR_MS)
            alOcultar()
        }
    }
    // El foco solo se mueve cuando aparecen o desaparecen los controles: una
    // fase nueva o un panel abierto no deben robarlo.
    LaunchedEffect(controlesVisibles, esTelevision) {
        if (!esTelevision) return@LaunchedEffect
        val objetivo = if (controlesVisibles) enfoqueAlternar else enfoqueRaiz
        runCatching { objetivo.requestFocus() }
    }
}

/** Toque sobre la superficie: oculta la barra activa o la vuelve a mostrar. */
private fun Modifier.toquesReproductor(
    fase: EstadoReproductor,
    controlesVisibles: Boolean,
    alOcultar: () -> Unit,
    alRevelar: () -> Unit,
): Modifier =
    pointerInput(fase, controlesVisibles) {
        detectTapGestures {
            if (controlesVisibles && debeOcultarse(fase)) alOcultar() else alRevelar()
        }
    }

@Composable
private fun etiquetaFase(fase: EstadoReproductor): String =
    when (fase) {
        EstadoReproductor.CARGANDO -> stringResource(R.string.reproductor_fase_cargando)
        EstadoReproductor.BUFFERING -> stringResource(R.string.reproductor_fase_buffering)
        EstadoReproductor.REPRODUCIENDO -> stringResource(R.string.reproductor_fase_reproduciendo)
        EstadoReproductor.PAUSADO -> stringResource(R.string.reproductor_fase_pausado)
        EstadoReproductor.TERMINADO -> stringResource(R.string.reproductor_fase_terminado)
        EstadoReproductor.ERROR -> stringResource(R.string.reproductor_fase_error)
    }

private fun ModoAspecto.recursoEtiqueta(): Int =
    when (this) {
        ModoAspecto.AJUSTAR -> R.string.reproductor_modo_ajustar
        ModoAspecto.RELLENAR -> R.string.reproductor_modo_rellenar
        ModoAspecto.ZOOM -> R.string.reproductor_modo_zoom
    }

private fun ModoAspecto.aResizeMode(): Int =
    when (this) {
        ModoAspecto.AJUSTAR -> AspectRatioFrameLayout.RESIZE_MODE_FIT
        ModoAspecto.RELLENAR -> AspectRatioFrameLayout.RESIZE_MODE_FILL
        ModoAspecto.ZOOM -> AspectRatioFrameLayout.RESIZE_MODE_ZOOM
    }

/**
 * Teclas del control remoto cuando los controles están ocultos.
 *
 * Con los controles visibles no se intercepta nada: el foco navega entre los
 * botones y el D-pad central activa el control enfocado; cualquier tecla
 * reinicia el auto-ocultado. Izquierda y derecha solo se convierten en búsqueda
 * cuando no hay un control que las consuma.
 */
private fun teclaDpad(
    evento: KeyEvent,
    controlesVisibles: Boolean,
    revelar: () -> Unit,
    alAlternar: () -> Unit,
    alDesplazar: (Long) -> Unit,
): Boolean {
    if (evento.type != KeyEventType.KeyDown) return false
    if (controlesVisibles) {
        revelar()
        return false
    }
    return when (evento.key) {
        Key.DirectionLeft -> {
            revelar()
            alDesplazar(-SALTO_BUSQUEDA_MS)
            true
        }
        Key.DirectionRight -> {
            revelar()
            alDesplazar(SALTO_BUSQUEDA_MS)
            true
        }
        Key.DirectionCenter, Key.Enter, Key.Spacebar -> {
            revelar()
            alAlternar()
            true
        }
        Key.DirectionUp, Key.DirectionDown -> {
            revelar()
            false
        }
        else -> false
    }
}

/**
 * Pantalla completa inmersiva mientras se reproduce.
 *
 * Restaura las barras del sistema y `keepScreenOn` al salir para no dejar la
 * actividad en un estado que afecte al resto de la aplicación.
 */
@Composable
private fun InmersivoPantallaCompleta(vista: View) {
    val encendidaOriginal = remember(vista) { vista.keepScreenOn }
    DisposableEffect(vista) {
        val controlador =
            vista.context.actividad()?.window?.let { ventana ->
                WindowCompat.getInsetsController(ventana, vista)
            }
        controlador?.systemBarsBehavior =
            WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
        controlador?.hide(WindowInsetsCompat.Type.systemBars())
        onDispose {
            controlador?.show(WindowInsetsCompat.Type.systemBars())
            vista.keepScreenOn = encendidaOriginal
        }
    }
}

private fun Context.actividad(): Activity? {
    var actual: Context? = this
    while (actual is ContextWrapper) {
        if (actual is Activity) return actual
        actual = actual.baseContext
    }
    return null
}

@Composable
private fun ErrorReproduccion(
    codigo: String,
    onVolver: () -> Unit,
) {
    Box(modifier = Modifier.fillMaxSize()) {
        Text(
            text = stringResource(R.string.reproductor_error, codigo),
            color = Color.White,
            style = MaterialTheme.typography.bodyLarge,
            modifier =
                Modifier
                    .align(Alignment.Center)
                    .padding(24.dp)
                    .testTag(TAG_REPRODUCTOR_ERROR),
        )
        TextButton(
            onClick = onVolver,
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp)
                    .testTag(TAG_REPRODUCTOR_VOLVER),
        ) {
            Text(stringResource(R.string.reproductor_volver))
        }
    }
}
