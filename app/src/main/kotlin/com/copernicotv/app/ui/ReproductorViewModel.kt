package com.copernicotv.app.ui

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.copernicotv.app.data.ReproductorRepositorio
import com.copernicotv.app.data.ResultadoStream
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
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
    ) : ViewModel() {
        private val _estado = MutableStateFlow<ReproductorUiState>(ReproductorUiState.Cargando)
        val estado: StateFlow<ReproductorUiState> = _estado.asStateFlow()

        private val _sesionEstado = MutableStateFlow(EstadoSesion())
        val sesionEstado: StateFlow<EstadoSesion> = _sesionEstado.asStateFlow()

        /** Motor de la sesión activa; `null` mientras no hay sesión abierta. */
        val motor: ExoPlayer?
            get() = sesion?.reproductor

        private var sesion: SesionMedia3? = null
        private var canalCargado: Long? = null
        private var carga: Job? = null
        private var recolector: Job? = null

        /**
         * Resuelve el Canal y abre su sesión. Es idempotente por Canal: una
         * recomposición o una actividad recreada no vuelven a abrir el motor.
         */
        fun cargar(canalId: Long) {
            if (canalCargado == canalId && sesion != null) return
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
                            abrirSesion(datos.url)
                            _estado.value = datos
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

        private fun abrirSesion(url: String) {
            val nueva = SesionMedia3(contexto, url)
            sesion = nueva
            recolector?.cancel()
            recolector =
                viewModelScope.launch {
                    nueva.estado.collect { estadoSesion ->
                        _sesionEstado.value = estadoSesion
                        if (estadoSesion.estado == EstadoReproductor.ERROR) {
                            _estado.value =
                                ReproductorUiState.Error(estadoSesion.codigoError ?: "error_reproduccion")
                            cerrarSesion()
                        }
                    }
                }
        }

        private fun cerrarSesion() {
            recolector?.cancel()
            recolector = null
            sesion?.liberar()
            sesion = null
            _sesionEstado.value = EstadoSesion()
        }
    }
