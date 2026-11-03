package com.copernicotv.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.copernicotv.app.R
import kotlinx.coroutines.delay

/** Etiqueta de la superficie de reproducción (E6-T1). */
const val TAG_REPRODUCTOR = "reproductor"

/** Etiqueta del estado observable del motor, con posición en milisegundos. */
const val TAG_REPRODUCTOR_ESTADO = "reproductor-estado"

/** Etiqueta del error de reproducción. */
const val TAG_REPRODUCTOR_ERROR = "reproductor-error"

/** Etiqueta del control de vuelta al catálogo. */
const val TAG_REPRODUCTOR_VOLVER = "reproductor-volver"

/**
 * Pantalla de reproducción de un Canal con Media3 (E6-T1).
 *
 * Crea una sesión `ExoPlayer` por URL resuelta, la libera al salir y muestra un
 * error tipado sin exponer la URL ni el mensaje del motor (S57a). Los controles
 * personalizados, la selección de pistas y el cambio de canal pertenecen a
 * E6-T2 y siguientes: aquí se usa el controlador estándar de `PlayerView`.
 */
@Composable
fun ReproductorScreen(
    canalId: Long,
    onVolver: () -> Unit,
    viewModel: ReproductorViewModel = hiltViewModel(),
) {
    val estado by viewModel.estado.collectAsStateWithLifecycle()
    LaunchedEffect(canalId) { viewModel.cargar(canalId) }
    BackHandler(onBack = onVolver)
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
            is ReproductorUiState.Error -> ErrorReproduccion(actual.codigo, onVolver)
            is ReproductorUiState.Listo ->
                SuperficieReproductor(
                    datos = actual,
                    onFallo = viewModel::fallo,
                    onVolver = onVolver,
                )
        }
    }
}

@Composable
private fun SuperficieReproductor(
    datos: ReproductorUiState.Listo,
    onFallo: (String) -> Unit,
    onVolver: () -> Unit,
) {
    val contexto = LocalContext.current
    val vista = LocalView.current
    val falloActual by rememberUpdatedState(onFallo)
    val reproductor =
        remember(datos.url) {
            ExoPlayer.Builder(contexto).build().apply {
                setMediaItem(MediaItem.fromUri(datos.url))
                prepare()
                playWhenReady = true
            }
        }
    var estadoObservable by remember(reproductor) { mutableStateOf("IDLE|0") }
    DisposableEffect(reproductor) {
        val oyente =
            object : Player.Listener {
                override fun onPlayerError(error: PlaybackException) {
                    falloActual(codigoDeError(error))
                }
            }
        reproductor.addListener(oyente)
        val pantallaEncendida = vista.keepScreenOn
        vista.keepScreenOn = true
        onDispose {
            reproductor.removeListener(oyente)
            reproductor.release()
            vista.keepScreenOn = pantallaEncendida
        }
    }
    LaunchedEffect(reproductor) {
        while (true) {
            estadoObservable = "${nombreEstado(reproductor.playbackState)}|${reproductor.currentPosition}"
            delay(INTERVALO_ESTADO_MS)
        }
    }
    AndroidView(
        factory = { contextoVista ->
            PlayerView(contextoVista).apply {
                player = reproductor
                useController = true
            }
        },
        update = { playerView -> playerView.player = reproductor },
        modifier = Modifier.fillMaxSize(),
    )
    Box(modifier = Modifier.fillMaxSize()) {
        Row(
            modifier =
                Modifier
                    .align(Alignment.TopStart)
                    .padding(12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(
                onClick = onVolver,
                modifier = Modifier.testTag(TAG_REPRODUCTOR_VOLVER),
            ) {
                Text(stringResource(R.string.reproductor_volver))
            }
            Column {
                Text(
                    text = datos.nombre ?: stringResource(R.string.catalogo_sin_nombre),
                    color = Color.White,
                    style = MaterialTheme.typography.titleSmall,
                )
                Text(
                    text = "${datos.protocolo.etiqueta} · $estadoObservable",
                    color = Color.White.copy(alpha = 0.7f),
                    style = MaterialTheme.typography.labelSmall,
                    modifier = Modifier.testTag(TAG_REPRODUCTOR_ESTADO),
                )
            }
        }
    }
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

/** Nombre estable del estado del motor para la etiqueta de diagnóstico. */
private fun nombreEstado(estado: Int): String =
    when (estado) {
        Player.STATE_IDLE -> "IDLE"
        Player.STATE_BUFFERING -> "BUFFERING"
        Player.STATE_READY -> "READY"
        Player.STATE_ENDED -> "ENDED"
        else -> "DESCONOCIDO"
    }

/**
 * Traduce el error del motor a un código propio y estable. El mensaje de
 * Media3 puede contener la URL: no se propaga a la UI ni a un log (S57a).
 */
internal fun codigoDeError(error: PlaybackException): String =
    when (error.errorCode) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "fuente_no_encontrada"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "sin_conexion"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS -> "http_error"
        else -> "error_reproduccion"
    }

private const val INTERVALO_ESTADO_MS = 250L
