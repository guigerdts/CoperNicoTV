package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.data.ReproductorRepositorio
import com.copernicotv.app.data.ResultadoStream
import com.copernicotv.core.player.ProtocoloStream
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de la pantalla de reproducción (E6-T1). */
sealed interface ReproductorUiState {
    data object Cargando : ReproductorUiState

    /**
     * Canal resuelto y listo para crear la sesión de Media3.
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

/** Resuelve la URL reproducible de un Canal y expone su estado a la pantalla. */
@HiltViewModel
class ReproductorViewModel
    @Inject
    constructor(
        private val repositorio: ReproductorRepositorio,
    ) : ViewModel() {
        private val _estado = MutableStateFlow<ReproductorUiState>(ReproductorUiState.Cargando)
        val estado: StateFlow<ReproductorUiState> = _estado.asStateFlow()

        fun cargar(canalId: Long) {
            viewModelScope.launch {
                _estado.value = ReproductorUiState.Cargando
                _estado.value =
                    when (val resultado = repositorio.resolver(canalId)) {
                        is ResultadoStream.Ok ->
                            ReproductorUiState.Listo(
                                canalId = resultado.stream.canalId,
                                nombre = resultado.stream.nombre,
                                url = resultado.stream.url,
                                protocolo = resultado.stream.protocolo,
                            )
                        is ResultadoStream.Error -> ReproductorUiState.Error(resultado.codigo)
                    }
            }
        }

        /**
         * Registra un fallo del motor. El código es propio y estable: el mensaje
         * de Media3 puede incluir la URL y no se propaga (S57a).
         */
        fun fallo(codigo: String) {
            _estado.value = ReproductorUiState.Error(codigo)
        }
    }
