package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.data.EjecutorActualizacionFuente
import com.copernicotv.app.data.IndicadoresFuente
import com.copernicotv.app.data.IndicadoresFuenteRepositorio
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
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
 * persistido. `cargando` vuelve a `false` al terminar tanto en éxito como en
 * fallo, porque vive en el `finally` de la corrutina.
 */
@HiltViewModel
class FuentesViewModel
    @Inject
    constructor(
        private val repositorio: IndicadoresFuenteRepositorio,
        private val ejecutor: EjecutorActualizacionFuente,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(FuentesUiState())
        val estado: StateFlow<FuentesUiState> = _estado.asStateFlow()

        /** Fuentes con una Actualización en curso. Solo se toca desde el hilo principal. */
        private val enCurso = mutableSetOf<Long>()

        init {
            viewModelScope.launch {
                try {
                    repositorio.observar().collect { fuentes ->
                        _estado.update { actual ->
                            actual.copy(
                                cargando = false,
                                fuentes = fuentes.conCargando(enCurso),
                                error = null,
                            )
                        }
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(cargando = false, error = ERROR_CARGA) }
                }
            }
        }

        /** Actualiza todas las Listas de la Fuente; ignora la repetición mientras corre. */
        fun actualizar(fuenteId: Long) {
            if (!enCurso.add(fuenteId)) return
            marcarCargando(fuenteId, cargando = true)
            viewModelScope.launch {
                try {
                    ejecutor.actualizarFuente(fuenteId)
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_ACTUALIZACION) }
                } finally {
                    enCurso.remove(fuenteId)
                    marcarCargando(fuenteId, cargando = false)
                }
            }
        }

        private fun marcarCargando(
            fuenteId: Long,
            cargando: Boolean,
        ) {
            _estado.update { actual ->
                actual.copy(
                    fuentes =
                        actual.fuentes.map { fuente ->
                            if (fuente.fuenteId == fuenteId) {
                                fuente.copy(cargando = cargando)
                            } else {
                                fuente
                            }
                        },
                )
            }
        }

        private fun List<IndicadoresFuente>.conCargando(ids: Set<Long>): List<IndicadoresFuente> =
            map { fuente -> fuente.copy(cargando = fuente.fuenteId in ids) }

        private companion object {
            const val ERROR_CARGA = "fuentes_carga_fallida"
            const val ERROR_ACTUALIZACION = "fuentes_actualizacion_fallida"
        }
    }
