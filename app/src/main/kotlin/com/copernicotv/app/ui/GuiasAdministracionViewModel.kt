package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.data.AdministracionGuiaRepositorio
import com.copernicotv.app.data.ConexionRepositorio
import com.copernicotv.app.data.ConexionVista
import com.copernicotv.app.data.FormularioGuia
import com.copernicotv.app.data.GuiaAdministrada
import com.copernicotv.app.data.ResultadoGuia
import com.copernicotv.app.data.actualizacion.ProgramadorGuias
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de la administración de guías EPG (S7, H-06). */
data class GuiasAdministracionUiState(
    val cargando: Boolean = true,
    val guias: List<GuiaAdministrada> = emptyList(),
    val conexiones: List<ConexionVista> = emptyList(),
    val error: String? = null,
    val aviso: String? = null,
)

/**
 * Alta, edición, baja y actualización de guías EPG (S7, H-06).
 *
 * Antes de esta pantalla no existía ninguna ruta de alta de `FuenteGuia` en
 * producción: la guía solo se sembraba desde pruebas y por eso el usuario nunca
 * podía publicarla. Aquí se crea con URL propia o asociada a una conexión, se
 * activa o desactiva y se actualiza a demanda; la programación periódica la
 * reconcilia [ProgramadorGuias].
 */
@HiltViewModel
class GuiasAdministracionViewModel
    @Inject
    constructor(
        private val guias: AdministracionGuiaRepositorio,
        private val conexiones: ConexionRepositorio,
        private val programador: ProgramadorGuias,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(GuiasAdministracionUiState())
        val estado: StateFlow<GuiasAdministracionUiState> = _estado.asStateFlow()

        init {
            viewModelScope.launch {
                try {
                    combine(guias.observarTodas(), conexiones.observarTodas()) { fuentes, perfiles ->
                        fuentes to perfiles
                    }.collect { (fuentes, perfiles) ->
                        _estado.update {
                            it.copy(cargando = false, guias = fuentes, conexiones = perfiles, error = null)
                        }
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(cargando = false, error = ERROR_CARGA) }
                }
            }
        }

        /** Alta de una guía manual con URL propia o vinculada a una conexión. */
        fun crear(formulario: FormularioGuia) = ejecutar { guias.crear(formulario) }

        /** Edición sin revelar la URL guardada; vacía la conserva. */
        fun editar(
            id: Long,
            formulario: FormularioGuia,
        ) = ejecutar { guias.editar(id, formulario) }

        /** Baja definitiva de la guía y sus generaciones publicadas. */
        fun eliminar(id: Long) {
            viewModelScope.launch {
                try {
                    if (guias.eliminar(id)) {
                        _estado.update { it.copy(aviso = AVISO_ELIMINADA, error = null) }
                        programador.reconciliar()
                    } else {
                        _estado.update { it.copy(error = ERROR_NO_ENCONTRADA) }
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_GENERICO) }
                }
            }
        }

        /** Activa o desactiva la guía para las pasadas automáticas. */
        fun cambiarActiva(
            id: Long,
            activa: Boolean,
        ) {
            viewModelScope.launch {
                try {
                    if (guias.cambiarActiva(id, activa)) {
                        _estado.update { it.copy(aviso = AVISO_GUARDADA, error = null) }
                        programador.reconciliar()
                    } else {
                        _estado.update { it.copy(error = ERROR_NO_ENCONTRADA) }
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_GENERICO) }
                }
            }
        }

        /** Encola una actualización inmediata de la guía. */
        fun actualizarAhora(id: Long) {
            viewModelScope.launch {
                try {
                    programador.solicitar(id)
                    _estado.update { it.copy(aviso = AVISO_ENCOLADA, error = null) }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_GENERICO) }
                }
            }
        }

        /** Limpia los mensajes visibles sin tocar la lista. */
        fun limpiarMensajes() {
            _estado.update { it.copy(error = null, aviso = null) }
        }

        private fun ejecutar(bloque: suspend () -> ResultadoGuia) {
            viewModelScope.launch {
                try {
                    when (val resultado = bloque()) {
                        is ResultadoGuia.Ok -> {
                            _estado.update { it.copy(aviso = AVISO_GUARDADA, error = null) }
                            programador.reconciliar()
                        }
                        is ResultadoGuia.Error ->
                            _estado.update { it.copy(error = resultado.codigo) }
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_GENERICO) }
                }
            }
        }

        private companion object {
            const val ERROR_CARGA = "guias_carga_fallida"
            const val ERROR_GENERICO = "guias_operacion_fallida"
            const val ERROR_NO_ENCONTRADA = "guias_no_encontrada"
            const val AVISO_GUARDADA = "guias_guardada"
            const val AVISO_ELIMINADA = "guias_eliminada"
            const val AVISO_ENCOLADA = "guias_actualizacion_en_cola"
        }
    }
