package com.copernicotv.app.player

import android.os.SystemClock
import androidx.media3.common.C
import androidx.media3.common.TrackSelectionOverride
import androidx.media3.common.Tracks
import androidx.media3.exoplayer.ExoPlayer
import com.copernicotv.core.player.CausaImagen
import com.copernicotv.core.player.EstadoImagen
import com.copernicotv.core.player.EvidenciaImagen
import com.copernicotv.core.player.FaseBase
import com.copernicotv.core.player.MaquinaImagen
import com.copernicotv.core.player.NivelImagen

/**
 * Detección de audio-sin-imagen y escalera de recuperación de imagen dentro
 * de la sesión Media3 (E7-T10, S5).
 *
 * La máquina en `:core` recibe evidencia observada y decide; esta clase
 * ejecuta el nivel pendiente solo cuando la máquina lo manda. La escalera es
 * independiente de la política de reintentos: un problema de imagen no
 * consume presupuesto de conexión y una pérdida de red no abre casos de
 * imagen. Los niveles que tocan la vista o el ciclo de vida de la sesión se
 * delegan por [AccionesImagen]; los niveles con el motor propio
 * (REPONER_SELECCION, REPREPARAR) se ejecutan aquí.
 */
internal class EscaleraImagen(
    ventanaMs: Long,
    private val permitirReconstruccion: Boolean,
    private val acciones: AccionesImagen,
    private val observador: ObservadorAudiovisual,
    private val liberada: () -> Boolean,
    private val reproductor: () -> ExoPlayer,
    private val grupos: () -> List<Tracks.Group>,
    private val reiniciarPrimerFrame: () -> Unit,
    private val publicar: (EstadoImagen?) -> Unit,
) {
    private val maquina = MaquinaImagen(ventanaMs = ventanaMs)
    private var avisoImagenRecuperada = false
    private var avisoImagenRecuperadaDesdeMs = 0L

    /**
     * Sondeo de la escalera con la evidencia que Media3 reportó.
     *
     * Cuando la sesión no está reproduciendo (buffering, reintento de red,
     * error) la escalera queda en pausa: el aviso de recuperación de
     * conectividad ya está en pantalla y confundir causas es exactamente lo
     * que esta recuperción debe evitar.
     */
    fun gestionar(faseBase: FaseBase) {
        if (liberada()) return
        val ahora = SystemClock.elapsedRealtime()
        val publicado =
            maquina.tick(evidenciaDe(faseBase), ahora, permitirReconstruccion)
        publicarEstadoImagen(publicado, ahora)
        if (!maquina.debeEjecutarNivel(permitirReconstruccion)) return
        val nivel = maquina.nivelPendiente(permitirReconstruccion) ?: return
        ejecutarNivel(nivel)
        maquina.registrarNivel(nivel, ahora)
    }

    /** Gancho de pruebas instrumentadas: fuerza un caso de imagen (E7-T10, S5). */
    fun forzarCaso(causa: CausaImagen) {
        maquina.abrirCaso(causa)
    }

    /** Retira el aviso de imagen: error definitivo de sesión o estabilidad completa. */
    fun vaciar() {
        avisoImagenRecuperada = false
        avisoImagenRecuperadaDesdeMs = 0L
    }

    /** Publica el estado de imagen, llevando el aviso de recuperación entre sondeos. */
    private fun publicarEstadoImagen(
        publicado: EstadoImagen?,
        ahoraMs: Long,
    ) {
        if (publicado != null) {
            avisoImagenRecuperada = publicado == EstadoImagen.IMAGEN_RECUPERADA
            if (avisoImagenRecuperada) avisoImagenRecuperadaDesdeMs = ahoraMs
        }
        // El aviso de recuperación es transitorio: la imagen volvió, el
        // usuario ya lo vio. Un tope de tiempo evita un badge eterno cuando
        // la ventana de estabilidad de T3 no está en curso.
        val visible =
            publicado
                ?: EstadoImagen.IMAGEN_RECUPERADA.takeIf {
                    avisoImagenRecuperada &&
                        ahoraMs - avisoImagenRecuperadaDesdeMs < AVISO_IMAGEN_RECUPERADA_MS
                }
        publicar(visible)
    }

    /** Evidencia de imagen derivada solo de lo que Media3 reportó (S5). */
    private fun evidenciaDe(faseBase: FaseBase): EvidenciaImagen =
        EvidenciaImagen(
            fase = faseBase,
            audioActivo = observador.audioActivo,
            hayGrupoVideo = observador.hayGrupoVideo,
            pistaVideoSeleccionada = observador.pistaVideo != null,
            decoderVideoInicializado = observador.decoderVideo != null,
            primerFrameVisto = observador.primerFrameVideoVisto,
            superficieActiva = observador.superficieActiva,
        )

    /** Ejecuta un nivel de la escalera de imagen (S5: del menos invasivo al más costoso). */
    private fun ejecutarNivel(nivel: NivelImagen) {
        if (liberada()) return
        when (nivel) {
            NivelImagen.RESTAURAR_SUPERFICIE -> {
                // Un re-attach real exige evidencia fresca: el primer frame
                // debe volver a llegar para declarar la imagen recuperada.
                reiniciarPrimerFrame()
                acciones.restaurarSuperficie()
            }
            NivelImagen.REPONER_SELECCION -> reponerSeleccionVideo()
            NivelImagen.REPREPARAR -> repreparar()
            NivelImagen.RECONSTRUIR ->
                acciones.reconstruirSesion(posicionParaReconstruir())
        }
    }

    /**
     * Repone la selección y habilitación de la pista de video (S5).
     *
     * Solo fuerza un grupo de video que el dispositivo puede reproducir:
     * forzar un formato no soportado dispararía un error de decoder y
     * mataría también el audio, y recuperar la imagen nunca debe romperla.
     */
    private fun reponerSeleccionVideo() {
        if (liberada()) return
        val motor = reproductor()
        val grupo =
            grupos().firstOrNull { grupo ->
                grupo.type == C.TRACK_TYPE_VIDEO &&
                    grupo.length > 0 &&
                    (0 until grupo.length).any { grupo.isTrackSupported(it) }
            } ?: return
        val indiceSoportado =
            (0 until grupo.length).first { grupo.isTrackSupported(it) }
        reiniciarPrimerFrame()
        motor.trackSelectionParameters =
            motor.trackSelectionParameters
                .buildUpon()
                .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, false)
                .setOverrideForType(TrackSelectionOverride(grupo.mediaTrackGroup, indiceSoportado))
                .build()
    }

    /**
     * Reprepara el recurso preservando la posición (S5).
     *
     * `setMediaItem(mismoItem, posicion)` reconstruye el periodo de medios
     * (lo que `prepare()` en READY no hace) sin `stop()`, sin perder la
     * posición y sin crear un segundo reproductor.
     */
    private fun repreparar() {
        if (liberada()) return
        val motor = reproductor()
        val item = motor.currentMediaItem ?: return
        val posicion = motor.currentPosition.coerceAtLeast(0L)
        reiniciarPrimerFrame()
        motor.setMediaItem(item, posicion)
        motor.prepare()
        motor.playWhenReady = true
    }

    /** Posición de reconstrucción: solo para VOD con duración conocida (S5). */
    private fun posicionParaReconstruir(): Long {
        val motor = reproductor()
        val duracion = motor.duration
        return if (duracion > 0L) motor.currentPosition.coerceAtLeast(0L) else 0L
    }

    private companion object {
        /** Cuánto queda visible el aviso de «imagen recuperada» (E7-T10, S5). */
        const val AVISO_IMAGEN_RECUPERADA_MS = 5_000L
    }
}
