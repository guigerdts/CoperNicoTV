package com.copernicotv.app.player

import android.content.Context
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackException
import androidx.media3.common.Player
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.TrackSelectionParameters
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.exoplayer.source.DefaultMediaSourceFactory
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
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
 * Única sesión Media3 de la pantalla de reproducción (E6-T2, ampliada en E6-T4).
 *
 * Es dueña del `ExoPlayer` y publica su estado como un flujo inmutable. La
 * interfaz solo Observa y envía acciones: no crea ni recrea el motor, y la
 * liberación es idempotente para sobrevivir a dobles llamadas del ciclo de vida.
 *
 * La recuperación de S20 reintenta sobre la misma instancia con `prepare()`:
 * nunca se recrea la sesión. Los reintentos internos de Media3 se desactivan
 * con [PoliticaErroresMedia3] para que la política observable sea exactamente
 * la de S20 y no una suma de reintentos internos más los propios.
 *
 * La URL vive únicamente dentro de `MediaItem`; el estado publicado no la
 * expone ni se registra en logs (S57a).
 */
class SesionMedia3(
    contexto: Context,
    url: String,
    private val preferenciasIniciales: PreferenciasPista = PreferenciasPista(),
    private val intervaloMs: Long = INTERVALO_SONDEO_MS,
    private val politica: PoliticaReintentos = PoliticaReintentos(),
) {
    private val _estado = MutableStateFlow(EstadoSesion())
    val estado: StateFlow<EstadoSesion> = _estado.asStateFlow()

    private val _estadoPistas =
        MutableStateFlow(
            EstadoPistas(
                preferenciaAudio = preferenciasIniciales.audio,
                preferenciaSubtitulos = preferenciasIniciales.subtitulos,
            ),
        )

    /** Pistas del contenido actual y preferencia aplicada (E6-T3). */
    val estadoPistas: StateFlow<EstadoPistas> = _estadoPistas.asStateFlow()

    /** Motor de reproducción; la interfaz solo lo conecta a su superficie. */
    val reproductor: ExoPlayer =
        ExoPlayer.Builder(contexto)
            .setMediaSourceFactory(
                DefaultMediaSourceFactory(contexto)
                    .setLoadErrorHandlingPolicy(PoliticaErroresMedia3()),
            )
            .build()

    private val alcance = CoroutineScope(Dispatchers.Main.immediate + SupervisorJob())
    private val liberada = AtomicBoolean(false)
    private val reintentos = politica.maquina()
    private var trabajoReintento: Job? = null
    private var preferencias = preferenciasIniciales
    private var grupos: List<Tracks.Group> = emptyList()
    private var firmaGrupos = ""
    private val oyente =
        object : Player.Listener {
            override fun onPlayerError(error: PlaybackException) {
                if (liberada.get()) return
                val estadoHttp = estadoHttpDe(error)
                when (val decision = reintentos.alFallar(clasificarFallo(error.errorCode, estadoHttp))) {
                    is DecisionReintento.Reintentar -> programarReintento(decision)
                    DecisionReintento.Definitivo ->
                        terminarConError(codigoDeError(error.errorCode, estadoHttp))
                    DecisionReintento.Ignorar -> Unit
                }
            }

            override fun onTracksChanged(tracks: Tracks) {
                grupos = tracks.groups
                val firma = firmaDe(tracks)
                if (firma != firmaGrupos) {
                    firmaGrupos = firma
                    aplicarPreferencias(tracks)
                }
                publicarPistas(tracks)
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

    /**
     * Selecciona una pista de audio del contenido actual.
     *
     * Solo cambia `TrackSelectionParameters`: no recrea el motor ni la sesión.
     */
    fun seleccionarAudio(pista: PistaDisponible) {
        if (liberada.get() || pista.tipo != TipoPista.AUDIO) return
        val grupo = grupos.getOrNull(pista.grupo) ?: return
        if (grupo.type != C.TRACK_TYPE_AUDIO || !grupo.isTrackSupported(pista.indice)) return
        preferencias = preferencias.copy(audio = PreferenciaPista.Pista(pista.idioma, pista.etiqueta))
        aplicar { builder ->
            builder.setOverrideForType(TrackSelectionOverride(grupo.mediaTrackGroup, pista.indice))
        }
        publicarPistas()
    }

    /** Devuelve el audio a la selección automática del motor (D3). */
    fun usarAudioAutomatico() {
        if (liberada.get()) return
        preferencias = preferencias.copy(audio = PreferenciaPista.Automatica)
        aplicar { builder ->
            builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
            builder.setPreferredAudioLanguage(null)
        }
        publicarPistas()
    }

    /** Selecciona una pista de subtítulos del contenido actual. */
    fun seleccionarSubtitulos(pista: PistaDisponible) {
        if (liberada.get() || pista.tipo != TipoPista.SUBTITULOS) return
        val grupo = grupos.getOrNull(pista.grupo) ?: return
        if (grupo.type != C.TRACK_TYPE_TEXT || !grupo.isTrackSupported(pista.indice)) return
        preferencias = preferencias.copy(subtitulos = PreferenciaPista.Pista(pista.idioma, pista.etiqueta))
        aplicar { builder ->
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.setOverrideForType(TrackSelectionOverride(grupo.mediaTrackGroup, pista.indice))
        }
        publicarPistas()
    }

    /** Desactiva los subtítulos sin destruir la preferencia de idioma guardada. */
    fun desactivarSubtitulos() {
        if (liberada.get()) return
        preferencias = preferencias.copy(subtitulos = PreferenciaPista.Desactivada)
        aplicar { builder ->
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
        }
        publicarPistas()
    }

    /** Devuelve los subtítulos a la selección automática del motor. */
    fun usarSubtitulosAutomaticos() {
        if (liberada.get()) return
        preferencias = preferencias.copy(subtitulos = PreferenciaPista.Automatica)
        aplicar { builder ->
            builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
            builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            builder.setPreferredTextLanguage(null)
        }
        publicarPistas()
    }

    /**
     * Libera el motor una única vez y detiene el sondeo de estado.
     */
    fun liberar() {
        if (!liberada.compareAndSet(false, true)) return
        trabajoReintento?.cancel()
        trabajoReintento = null
        alcance.cancel()
        runCatching { reproductor.removeListener(oyente) }
        reproductor.release()
    }

    /** Solo para pruebas: confirma que la sesión liberó su motor. */
    fun estaLiberada(): Boolean = liberada.get()

    /**
     * Publica el reintento pendiente y lo ejecuta sobre la misma instancia.
     *
     * Existe una sola corrutina de reintento por sesión: la máquina ignora
     * fallos duplicados y esta guarda evita duplicar el trabajo si el motor
     * notificara dos veces. La cancelación de la sesión cancela la espera, de
     * modo que un reintento pendiente nunca sobrevive a un cambio de Canal o a
     * la salida de la pantalla.
     */
    private fun programarReintento(decision: DecisionReintento.Reintentar) {
        if (trabajoReintento?.isActive == true) return
        _estado.value =
            _estado.value.copy(
                estado = EstadoReproductor.REINTENTANDO,
                codigoError = null,
                reintento = decision.numero,
                reintentosMaximos = reintentos.maximo,
            )
        trabajoReintento =
            alcance.launch {
                politica.esperar(decision.esperaMs)
                if (!isActive || liberada.get()) return@launch
                reintentos.alEjecutar()
                _estado.value =
                    _estado.value.copy(
                        estado = EstadoReproductor.CARGANDO,
                        reintento = 0,
                        reintentosMaximos = 0,
                    )
                reproductor.prepare()
            }
    }

    /** Cierra la recuperación con el error definitivo (S20). */
    private fun terminarConError(codigo: String) {
        trabajoReintento?.cancel()
        trabajoReintento = null
        _estado.value =
            _estado.value.copy(
                estado = EstadoReproductor.ERROR,
                codigoError = codigo,
                reintento = 0,
                reintentosMaximos = 0,
            )
    }

    private fun publicarEstado() {
        val fase = _estado.value.estado
        if (fase == EstadoReproductor.ERROR || fase == EstadoReproductor.REINTENTANDO) return
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

    /**
     * Aplica la preferencia guardada una vez por cada conjunto de grupos.
     *
     * La firma evita reaplicar parámetros cuando el cambio de pistas proviene
     * de la propia selección; si el contenido publica grupos nuevos (por
     * ejemplo, subtítulos que llegan más tarde), se vuelve a intentar.
     */
    private fun aplicarPreferencias(tracks: Tracks) {
        if (liberada.get()) return
        val audio = construirPistas(tracks, TipoPista.AUDIO)
        val subtitulos = construirPistas(tracks, TipoPista.SUBTITULOS)
        val builder = reproductor.trackSelectionParameters.buildUpon()
        aplicarPreferenciaAudio(builder, audio)
        aplicarPreferenciaSubtitulos(builder, subtitulos)
        reproductor.trackSelectionParameters = builder.build()
    }

    private fun aplicarPreferenciaAudio(
        builder: TrackSelectionParameters.Builder,
        audio: List<PistaDisponible>,
    ) {
        builder.clearOverridesOfType(C.TRACK_TYPE_AUDIO)
        val preferencia = preferencias.audio
        if (preferencia !is PreferenciaPista.Pista) {
            builder.setPreferredAudioLanguage(null)
            return
        }
        builder.setPreferredAudioLanguage(preferencia.idioma)
        val coincidencia = buscarPista(audio, preferencia) ?: return
        val grupo = grupos.getOrNull(coincidencia.grupo) ?: return
        builder.setOverrideForType(TrackSelectionOverride(grupo.mediaTrackGroup, coincidencia.indice))
    }

    private fun aplicarPreferenciaSubtitulos(
        builder: TrackSelectionParameters.Builder,
        subtitulos: List<PistaDisponible>,
    ) {
        when (val preferencia = preferencias.subtitulos) {
            PreferenciaPista.Desactivada -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, true)
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
            }
            is PreferenciaPista.Pista -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.setPreferredTextLanguage(preferencia.idioma)
                val coincidencia = buscarPista(subtitulos, preferencia)
                if (coincidencia == null) {
                    builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                } else {
                    val grupo = grupos.getOrNull(coincidencia.grupo)
                    if (grupo != null) {
                        builder.setOverrideForType(
                            TrackSelectionOverride(grupo.mediaTrackGroup, coincidencia.indice),
                        )
                    }
                }
            }
            PreferenciaPista.Automatica -> {
                builder.setTrackTypeDisabled(C.TRACK_TYPE_TEXT, false)
                builder.clearOverridesOfType(C.TRACK_TYPE_TEXT)
                builder.setPreferredTextLanguage(null)
            }
        }
    }

    private fun publicarPistas(tracks: Tracks = reproductor.currentTracks) {
        val audio = construirPistas(tracks, TipoPista.AUDIO)
        val subtitulos = construirPistas(tracks, TipoPista.SUBTITULOS)
        _estadoPistas.value =
            EstadoPistas(
                audio = audio,
                subtitulos = subtitulos,
                preferenciaAudio = preferencias.audio,
                preferenciaSubtitulos = preferencias.subtitulos,
                avisoAudio = preferenciaNoDisponible(audio, preferencias.audio),
                avisoSubtitulos = preferenciaNoDisponible(subtitulos, preferencias.subtitulos),
                subtitulosDesactivados = !tracks.isTypeSelected(C.TRACK_TYPE_TEXT),
            )
    }

    private fun construirPistas(
        tracks: Tracks,
        tipo: TipoPista,
    ): List<PistaDisponible> {
        val tipoMedia3 = if (tipo == TipoPista.AUDIO) C.TRACK_TYPE_AUDIO else C.TRACK_TYPE_TEXT
        return tracks.groups.flatMapIndexed { indiceGrupo, grupo ->
            if (grupo.type != tipoMedia3) {
                emptyList()
            } else {
                (0 until grupo.length).map { indicePista ->
                    val formato = grupo.getTrackFormat(indicePista)
                    PistaDisponible(
                        grupo = indiceGrupo,
                        indice = indicePista,
                        tipo = tipo,
                        idioma = formato.language,
                        etiqueta = formato.label,
                        canales = formato.channelCount.coerceAtLeast(0),
                        forzada = formato.selectionFlags and C.SELECTION_FLAG_FORCED != 0,
                        activa = grupo.isTrackSelected(indicePista),
                        soportada = grupo.isTrackSupported(indicePista),
                    )
                }
            }
        }
    }

    private fun firmaDe(tracks: Tracks): String =
        tracks.groups
            .mapIndexed { indice, grupo -> "$indice:${grupo.type}:${grupo.length}" }
            .joinToString("|")

    private inline fun aplicar(bloque: (TrackSelectionParameters.Builder) -> Unit) {
        if (liberada.get()) return
        val builder = reproductor.trackSelectionParameters.buildUpon()
        bloque(builder)
        reproductor.trackSelectionParameters = builder.build()
    }

    private fun Long.tomarSiConocida(): Long = if (this == C.TIME_UNSET) 0L else this.coerceAtLeast(0L)

    private companion object {
        const val INTERVALO_SONDEO_MS = 250L
    }
}

/**
 * Traduce el error del motor a un código propio y estable. El mensaje de
 * Media3 puede contener la URL: no se propaga a la UI ni a un log (S57a).
 *
 * Los HTTP se tipan por clase: 4xx es un error de cliente que S20 no
 * reintenta, 5xx es un fallo del servidor y el resto conserva el código
 * genérico.
 */
internal fun codigoDeError(
    codigoError: Int,
    estadoHttp: Int?,
): String =
    when (codigoError) {
        PlaybackException.ERROR_CODE_IO_FILE_NOT_FOUND -> "fuente_no_encontrada"
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_FAILED,
        PlaybackException.ERROR_CODE_IO_NETWORK_CONNECTION_TIMEOUT,
        -> "sin_conexion"
        PlaybackException.ERROR_CODE_TIMEOUT -> "tiempo_agotado"
        PlaybackException.ERROR_CODE_IO_BAD_HTTP_STATUS ->
            when {
                estadoHttp == null -> "http_error"
                estadoHttp in 400..499 -> "http_cliente"
                estadoHttp in 500..599 -> "http_servidor"
                else -> "http_error"
            }
        else -> "error_reproduccion"
    }
