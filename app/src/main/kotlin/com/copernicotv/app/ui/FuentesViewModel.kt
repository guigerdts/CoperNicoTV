package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.data.IndicadoresFuente
import com.copernicotv.app.data.IndicadoresFuenteRepositorio
import com.copernicotv.app.data.actualizacion.ProgramadorActualizaciones
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de la pantalla de Fuentes (E4-T8). */
data class FuentesUiState(
    val cargando: Boolean = true,
    val fuentes: List<IndicadoresFuente> = emptyList(),
    val error: String? = null,
)

/**
 * Pantalla de Fuentes (E4-T8).
 *
 * Mantiene separados los tres indicadores de S7: [IndicadoresFuente.cargando]
 * refleja una Actualización en curso, [IndicadoresFuente.actualizacion] el
 * último intento completado y [IndicadoresFuente.disponibilidad] el catálogo
 * persistido.
 *
 * `cargando` ya no vive en un conjunto en memoria: se deriva del estado del
 * trabajo de WorkManager. Así vuelve a `false` también cuando el proceso se
 * recrea a mitad de una ejecución, y sigue siendo `false` cuando el intento
 * terminó en fallo (S7a). `actualizacion` y `disponibilidad` siguen viniendo de
 * Room y no dependen del trabajo.
 */
@HiltViewModel
class FuentesViewModel
    @Inject
    constructor(
        private val repositorio: IndicadoresFuenteRepositorio,
        private val programador: ProgramadorActualizaciones,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(FuentesUiState())
        val estado: StateFlow<FuentesUiState> = _estado.asStateFlow()

        init {
            viewModelScope.launch {
                try {
                    combine(repositorio.observar(), programador.observar()) { fuentes, trabajos ->
                        fuentes.map { fuente ->
                            fuente.copy(cargando = trabajos[fuente.fuenteId]?.enCurso == true)
                        }
                    }.collect { fuentes ->
                        _estado.update { actual ->
                            actual.copy(cargando = false, fuentes = fuentes, error = null)
                        }
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(cargando = false, error = ERROR_CARGA) }
                }
            }
        }

        /**
         * Encola la Actualización manual de la Fuente (DC-07 punto 6).
         *
         * No espera a que termine: el progreso y el resultado llegan por los
         * flujos observados. Un trabajo ya encolado para la misma Fuente no se
         * duplica porque el nombre único lo impide.
         */
        fun actualizar(fuenteId: Long) {
            viewModelScope.launch {
                try {
                    programador.solicitarFuente(fuenteId)
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_ACTUALIZACION) }
                }
            }
        }

        private companion object {
            const val ERROR_CARGA = "fuentes_carga_fallida"
            const val ERROR_ACTUALIZACION = "fuentes_actualizacion_fallida"
        }
    }
