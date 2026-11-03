package com.copernicotv.app.ui

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.ReproductorRepositorio
import com.copernicotv.app.data.ResultadoStream
import com.copernicotv.app.player.EstadoPistas
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.player.PistaDisponible
import com.copernicotv.app.player.PoliticaReintentos
import com.copernicotv.app.player.PreferenciaPista
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.player.SesionMedia3
import com.copernicotv.core.player.ProtocoloStream
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de la pantalla de reproducción (E6-T1, ampliado en E6-T2). */
sealed interface ReproductorUiState {
    data object Cargando : ReproductorUiState

    /**
     * Canal resuelto y con su sesión Media3 abierta.
     *
     * `toString` omite la URL para que un log accidental del estado no exponga
     * la credencial del flujo (S57a).
     */
    data class Listo(
        val canalId: Long,
        val nombre: String?,
        val url: String,
        val protocolo: ProtocoloStream,
    ) : ReproductorUiState {
        override fun toString(): String = "Listo(canalId=$canalId, protocolo=$protocolo)"
    }

    data class Error(val codigo: String) : ReproductorUiState
}

/**
 * Dueño de la sesión Media3 de un Canal (E6-T2).
 *
 * La sesión se crea una sola vez por Canal y sobrevive a los cambios de
 * configuración porque vive en el ViewModel, no en la composición. La pantalla
 * solo adjunta su superficie: rotar, cambiar de modo de aspecto o redimensionar
 * nunca recrea el motor ni reinicia la posición.
 */
@HiltViewModel
class ReproductorViewModel
    @Inject
    constructor(
        private val repositorio: ReproductorRepositorio,
        @ApplicationContext private val contexto: Context,
        private val preferenciasPista: RepositorioPreferenciasPista,
        private val politicaReintentos: PoliticaReintentos,
    ) : ViewModel() {
        private val _estado = MutableStateFlow<ReproductorUiState>(ReproductorUiState.Cargando)
        val estado: StateFlow<ReproductorUiState> = _estado.asStateFlow()

        private val _sesionEstado = MutableStateFlow(EstadoSesion())
        val sesionEstado: StateFlow<EstadoSesion> = _sesionEstado.asStateFlow()

        private val _estadoPistas = MutableStateFlow(EstadoPistas())
        val estadoPistas: StateFlow<EstadoPistas> = _estadoPistas.asStateFlow()

        /** Motor de la sesión activa; `null` mientras no hay sesión abierta. */
        val motor: ExoPlayer?
            get() = sesion?.reproductor

        private var sesion: SesionMedia3? = null
        private var canalCargado: Long? = null
        private var carga: Job? = null
        private var recolector: Job? = null
        private var recolectorPistas: Job? = null
        private var preferenciasActuales = PreferenciasPista()

        /**
         * Resuelve el Canal y abre su sesión. Es idempotente por Canal: una
         * recomposición o una actividad recreada no vuelven a abrir el motor.
         */
        fun cargar(canalId: Long) {
            // Una carga en curso para el mismo Canal ya es dueña de la sesión:
            // repetirla la liberaría y reiniciaría el presupuesto de S20.
            if (canalCargado == canalId && (sesion != null || carga?.isActive == true)) return
            canalCargado = canalId
            carga?.cancel()
            carga =
                viewModelScope.launch {
                    cerrarSesion()
                    _estado.value = ReproductorUiState.Cargando
                    when (val resultado = repositorio.resolver(canalId)) {
                        is ResultadoStream.Ok -> {
                            val datos =
                                ReproductorUiState.Listo(
                                    canalId = resultado.stream.canalId,
                                    nombre = resultado.stream.nombre,
                                    url = resultado.stream.url,
                                    protocolo = resultado.stream.protocolo,
                                )
                            preferenciasActuales =
                                runCatching { preferenciasPista.leer() }
                                    .getOrDefault(PreferenciasPista())
                            val nueva = abrirSesion(datos.url, preferenciasActuales)
                            // Si la sesión falló antes de publicar el Canal, el
                            // error definitivo ya ganó: no lo pisamos con Listo.
                            if (sesion === nueva) _estado.value = datos
                        }
                        is ResultadoStream.Error -> {
                            _estado.value = ReproductorUiState.Error(resultado.codigo)
                            canalCargado = null
                        }
                    }
                }
        }

        /** Alterna play y pausa de la sesión activa. */
        fun alternar() {
            sesion?.alternar()
        }

        /** Desplaza la posición actual (positivo avanza, negativo retrocede). */
        fun desplazar(desplazamientoMs: Long) {
            sesion?.desplazar(desplazamientoMs)
        }

        /** Busca una posición absoluta del contenido. */
        fun buscarA(posicionMs: Long) {
            sesion?.buscarA(posicionMs)
        }

        /** Selecciona una pista de audio y la guarda como preferencia (D3). */
        fun seleccionarAudio(pista: PistaDisponible) {
            sesion?.seleccionarAudio(pista)
            guardarPreferencias(
                preferenciasActuales.copy(audio = PreferenciaPista.Pista(pista.idioma, pista.etiqueta)),
            )
        }

        /** Devuelve el audio a la selección automática y lo guarda (D3). */
        fun usarAudioAutomatico() {
            sesion?.usarAudioAutomatico()
            guardarPreferencias(preferenciasActuales.copy(audio = PreferenciaPista.Automatica))
        }

        /** Selecciona una pista de subtítulos y la guarda como preferencia (D3). */
        fun seleccionarSubtitulos(pista: PistaDisponible) {
            sesion?.seleccionarSubtitulos(pista)
            guardarPreferencias(
                preferenciasActuales.copy(subtitulos = PreferenciaPista.Pista(pista.idioma, pista.etiqueta)),
            )
        }

        /** Desactiva los subtítulos y guarda esa preferencia (S44). */
        fun desactivarSubtitulos() {
            sesion?.desactivarSubtitulos()
            guardarPreferencias(preferenciasActuales.copy(subtitulos = PreferenciaPista.Desactivada))
        }

        /** Devuelve los subtítulos a la selección automática y lo guarda (D3). */
        fun usarSubtitulosAutomaticos() {
            sesion?.usarSubtitulosAutomaticos()
            guardarPreferencias(preferenciasActuales.copy(subtitulos = PreferenciaPista.Automatica))
        }

        /** Cierra la sesión al salir de la pantalla y permite reabrir el Canal. */
        fun cerrar() {
            carga?.cancel()
            carga = null
            cerrarSesion()
            canalCargado = null
            if (_estado.value !is ReproductorUiState.Error) {
                _estado.value = ReproductorUiState.Cargando
            }
        }

        /** Solo para pruebas: sesión viva de la pantalla. */
        @VisibleForTesting
        fun sesionActual(): SesionMedia3? = sesion

        override fun onCleared() {
            carga?.cancel()
            cerrarSesion()
        }

        private fun abrirSesion(
            url: String,
            preferencias: PreferenciasPista,
        ): SesionMedia3 {
            val nueva = SesionMedia3(contexto, url, preferencias, politica = politicaReintentos)
            sesion = nueva
            recolector?.cancel()
            recolector =
                viewModelScope.launch {
                    nueva.estado.collect { estadoSesion ->
                        // Un callback de la sesión anterior no debe tocar la nueva (E6-T4).
                        if (sesion !== nueva) return@collect
                        _sesionEstado.value = estadoSesion
                        if (estadoSesion.estado == EstadoReproductor.ERROR) {
                            _estado.value =
                                ReproductorUiState.Error(estadoSesion.codigoError ?: "error_reproduccion")
                            cerrarSesion()
                        }
                    }
                }
            recolectorPistas?.cancel()
            recolectorPistas =
                viewModelScope.launch {
                    nueva.estadoPistas.collect { pistas ->
                        if (sesion !== nueva) return@collect
                        _estadoPistas.value = pistas
                        preferenciasActuales =
                            PreferenciasPista(
                                audio = pistas.preferenciaAudio,
                                subtitulos = pistas.preferenciaSubtitulos,
                            )
                    }
                }
            return nueva
        }

        private fun guardarPreferencias(preferencias: PreferenciasPista) {
            preferenciasActuales = preferencias
            viewModelScope.launch {
                runCatching { preferenciasPista.guardar(preferencias) }
            }
        }

        private fun cerrarSesion() {
            // La sesión deja de ser la vigente antes de cancelar los recolectores:
            // así una entrega en vuelo no puede escribir estado de la anterior.
            val anterior = sesion
            sesion = null
            recolector?.cancel()
            recolector = null
            recolectorPistas?.cancel()
            recolectorPistas = null
            anterior?.liberar()
            _sesionEstado.value = EstadoSesion()
            _estadoPistas.value = EstadoPistas()
        }
    }
