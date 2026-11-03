package com.copernicotv.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Única sesión Media3 de la pantalla de reproducción (E6-T2).
 *
 * Es dueña del `ExoPlayer` y publica su estado como un flujo inmutable. La
 * interfaz solo Observa y envía acciones: no crea ni recrea el motor, y la
 * liberación es idempotente para sobrevivir a dobles llamadas del ciclo de vida.
 *
 * La URL vive únicamente dentro de `MediaItem`; el estado publicado no la
 * expone ni se registra en logs (S57a).
 */
class SesionMedia3(
    contexto: Context,
    url: String,
    private val intervaloMs: Long = INTERVALO_SONDEO_MS,
) {
    private val _estado = MutableStateFlow(EstadoSesion())
    val estado: StateFlow<EstadoSesion> = _estado.asStateFlow()

    /** Motor de reproducción; la interfaz solo lo conecta a su superficie. */
    val reproductor: ExoPlayer = ExoPlayer.Builder(contexto).build()

    private val alcance = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val liberada = AtomicBoolean(false)
    private val oyente =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                _estado.value =
                    _estado.value.copy(
                        estado = EstadoReproductor.ERROR,
                        codigoError = codigoDeError(error),
                    )
            }
        }

    init {
        reproductor.addListener(oyente)
        reproductor.setMediaItem(MediaItem.fromUri(url))
        reproductor.prepare()
        reproductor.playWhenReady = true
        alcance.launch {
            while (isActive) {
                publicarEstado()
                delay(intervaloMs)
            }
        }
    }

    /** Alterna play y pausa; no hace nada si la sesión ya fue liberada. */
    fun alternar() {
        if (liberada.get()) return
        if (reproductor.isPlaying) reproductor.pause() else reproductor.play()
    }

    /** Busca una posición absoluta limitada a la duración conocida. */
    fun buscarA(posicionMs: Long) {
        if (liberada.get()) return
        val duracion = _estado.value.duracionMs
        if (duracion <= 0L) return
        reproductor.seekTo(limitarBusqueda(posicionMs, duracion))
    }

    /** Desplaza la posición actual en [desplazamientoMs] (positivo o negativo). */
    fun desplazar(desplazamientoMs: Long) {
        buscarA(_estado.value.posicionMs + desplazamientoMs)
    }

    /** Libera el motor una única vez y detiene el sondeo de estado. */
    fun liberar() {
        if (!liberada.compareAndSet(false, true)) return
        alcance.cancel()
        runCatching { reproductor.removeListener(oyente) }
        reproductor.release()
    }

    /** Solo para pruebas: confirma que la sesión liberó su motor. */
    fun estaLiberada(): Boolean = liberada.get()

    private fun publicarEstado() {
        if (_estado.value.estado == EstadoReproductor.ERROR) return
        val duracion = reproductor.duration.tomarSiConocida()
        _estado.value =
            EstadoSesion(
                estado = faseActual(),
                posicionMs = reproductor.currentPosition.coerceAtLeast(0L),
                duracionMs = duracion,
                bufferMs = reproductor.bufferedPosition.coerceAtLeast(0L),
                puedeBuscar = duracion > 0L && reproductor.isCurrentMediaItemSeekable,
            )
    }

    private fun faseActual(): EstadoReproductor =
        when (reproductor.playbackState) {
            Player.STATE_IDLE -> EstadoReproductor.CARGANDO
            Player.STATE_BUFFERING -> EstadoReproductor.BUFFERING
            Player.STATE_ENDED -> EstadoReproductor.TERMINADO
            Player.STATE_READY ->
                if (reproductor.isPlaying) {
                    EstadoReproductor.REPRODUCIENDO
                } else {
                    EstadoReproductor.PAUSADO
                }
            else -> EstadoReproductor.CARGANDO
        }

    private fun Long.tomarSiConocida(): Long = if (this == C.TIME_UNSET) 0L else this.coerceAtLeast(0L)

    private companion object {
        const val INTERVALO_SONDEO_MS = 250L
    }
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
