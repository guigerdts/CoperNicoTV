package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.data.AdministracionGuiaRepositorio
import com.copernicotv.app.data.ConexionRepositorio
import com.copernicotv.app.data.ConexionVista
import com.copernicotv.app.data.DetalleConexion
import com.copernicotv.app.data.FormularioM3U
import com.copernicotv.app.data.FormularioXtream
import com.copernicotv.app.data.GuiaAdministrada
import com.copernicotv.app.data.ResultadoConexion
import com.copernicotv.app.data.ResultadoSonda
import com.copernicotv.app.data.SondaConexion
import com.copernicotv.app.data.actualizacion.ProgramadorActualizaciones
import com.copernicotv.app.data.actualizacion.ProgramadorGuias
import com.copernicotv.core.conexion.ConexionXtream
import com.copernicotv.core.conexion.TipoConexion
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/** Estado de la pantalla de administración de conexiones (S4, S5, S6). */
data class ConexionesUiState(
    val cargando: Boolean = true,
    val conexiones: List<ConexionVista> = emptyList(),
    val guias: List<GuiaAdministrada> = emptyList(),
    /** Código o mensaje del último error, ya redactado para mostrar. */
    val error: String? = null,
    /** Código del último desenlace correcto (prueba, guardado, actualización). */
    val aviso: String? = null,
    /** Conexión cuya prueba está en curso. */
    val probandoId: Long? = null,
    /** Detalle editable cargado bajo demanda; nunca incluye la contraseña. */
    val detalle: DetalleConexion? = null,
)

/**
 * Administración de conexiones IPTV (S4, S5, S6).
 *
 * La pantalla no duplica el pipeline: crear o editar materializa `Fuente`,
 * `Lista` y `FuenteGuia` a través de [ConexionRepositorio], y actualizar una
 * lista o una guía encola el trabajo existente de WorkManager. Los secretos
 * nunca pasan por el estado: el detalle editable solo informa si existen.
 */
@HiltViewModel
class ConexionesViewModel
    @Inject
    constructor(
        private val conexiones: ConexionRepositorio,
        private val guias: AdministracionGuiaRepositorio,
        private val programador: ProgramadorActualizaciones,
        private val programadorGuias: ProgramadorGuias,
        private val sonda: SondaConexion,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(ConexionesUiState())
        val estado: StateFlow<ConexionesUiState> = _estado.asStateFlow()

        init {
            viewModelScope.launch {
                try {
                    combine(conexiones.observarTodas(), guias.observarTodas()) { perfiles, fuentes ->
                        perfiles to fuentes
                    }.collect { (perfiles, fuentes) ->
                        _estado.update {
                            it.copy(cargando = false, conexiones = perfiles, guias = fuentes, error = null)
                        }
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(cargando = false, error = ERROR_CARGA) }
                }
            }
        }

        /** Método A: alta de un perfil Xtream Codes. */
        fun crearXtream(formulario: FormularioXtream) = ejecutar { conexiones.crearXtream(formulario) }

        /** Método A: edición conservando la Lista y sus asociaciones. */
        fun editarXtream(
            id: Long,
            formulario: FormularioXtream,
        ) = ejecutar { conexiones.editarXtream(id, formulario) }

        /** Método B: alta de una lista M3U/M3U8. */
        fun crearM3U(formulario: FormularioM3U) = ejecutar { conexiones.crearM3U(formulario) }

        /** Método B: edición conservando la Lista y sus asociaciones. */
        fun editarM3U(
            id: Long,
            formulario: FormularioM3U,
        ) = ejecutar { conexiones.editarM3U(id, formulario) }

        /** Baja con confirmación previa desde la UI; borra los secretos con la fila. */
        fun eliminar(id: Long) = ejecutar { conexiones.eliminar(id) }

        /** Activa la conexión y su Lista; desactiva la anterior. */
        fun activar(id: Long) = ejecutar { conexiones.activar(id) }

        /** Desactiva la conexión sin borrar catálogo ni asociaciones. */
        fun desactivar(id: Long) = ejecutar { conexiones.desactivar(id) }

        /** Carga el detalle editable de una conexión; no revela secretos. */
        fun cargarDetalle(id: Long) {
            viewModelScope.launch {
                try {
                    val detalle = conexiones.detalle(id)
                    _estado.update { it.copy(detalle = detalle, error = null) }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_CARGA) }
                }
            }
        }

        /** Descarta el detalle cargado al cerrar el formulario. */
        fun limpiarDetalle() {
            _estado.update { it.copy(detalle = null) }
        }

        /** Limpia el aviso o error visible sin tocar la lista. */
        fun limpiarMensajes() {
            _estado.update { it.copy(error = null, aviso = null) }
        }

        /**
         * Prueba la conexión con una petición real y persiste el resultado (S4).
         *
         * Xtream se prueba contra `player_api.php`; M3U contra la URL de lista.
         * La URL se descifra solo en memoria y nunca entra al estado de la UI.
         */
        fun probar(id: Long) {
            viewModelScope.launch {
                _estado.update { it.copy(probandoId = id, error = null, aviso = null) }
                try {
                    val resultado = sondear(id)
                    if (resultado == null) {
                        _estado.update { it.copy(probandoId = null, error = ERROR_SIN_URL) }
                        return@launch
                    }
                    conexiones.fijarEstado(id, resultado.estado, resultado.codigo)
                    _estado.update { it.copy(probandoId = null, aviso = resultado.codigo) }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(probandoId = null, error = ERROR_PRUEBA) }
                }
            }
        }

        /** Encola la actualización de la lista de canales de la conexión. */
        fun actualizarLista(id: Long) {
            viewModelScope.launch {
                try {
                    val fuenteId = conexiones.fuenteId(id)
                    if (fuenteId == null) {
                        _estado.update { it.copy(error = ERROR_SIN_FUENTE) }
                        return@launch
                    }
                    programador.solicitarFuente(fuenteId)
                    _estado.update { it.copy(aviso = AVISO_ACTUALIZACION_ENCOLADA, error = null) }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_ACTUALIZACION) }
                }
            }
        }

        /** Encola la actualización de la guía asociada a la conexión. */
        fun actualizarGuia(id: Long) {
            viewModelScope.launch {
                try {
                    val guia = _estado.value.guias.firstOrNull { it.conexionId == id }
                    if (guia == null) {
                        _estado.update { it.copy(error = ERROR_SIN_GUIA) }
                        return@launch
                    }
                    programadorGuias.solicitar(guia.id)
                    _estado.update { it.copy(aviso = AVISO_ACTUALIZACION_ENCOLADA, error = null) }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_ACTUALIZACION) }
                }
            }
        }

        private suspend fun sondear(id: Long): ResultadoSonda? =
            when (conexiones.detalle(id)?.tipo) {
                TipoConexion.XTREAM -> {
                    val url = conexiones.datosXtream(id)?.let(ConexionXtream::urlPlayerApi)
                    url?.let { sonda.probar(it, TipoConexion.XTREAM) }
                }
                TipoConexion.M3U -> {
                    val url = conexiones.urlListaDescifrada(id)
                    url?.let { sonda.probar(it, TipoConexion.M3U) }
                }
                null -> null
            }

        private fun ejecutar(bloque: suspend () -> ResultadoConexion) {
            viewModelScope.launch {
                try {
                    when (val resultado = bloque()) {
                        is ResultadoConexion.Invalida ->
                            _estado.update {
                                it.copy(error = resultado.errores.joinToString(separator = " ") { error -> error.mensaje })
                            }
                        is ResultadoConexion.Error ->
                            _estado.update { it.copy(error = resultado.codigo) }
                        is ResultadoConexion.Creada -> exito(AVISO_GUARDADA, reconciliar = true)
                        ResultadoConexion.Actualizada -> exito(AVISO_GUARDADA, reconciliar = true)
                        ResultadoConexion.Eliminada -> exito(AVISO_ELIMINADA, reconciliar = true)
                    }
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (_: Exception) {
                    _estado.update { it.copy(error = ERROR_GENERICO) }
                }
            }
        }

        private suspend fun exito(
            aviso: String,
            reconciliar: Boolean,
        ) {
            _estado.update { it.copy(aviso = aviso, error = null) }
            if (reconciliar) {
                programador.reconciliar()
                programadorGuias.reconciliar()
            }
        }

        private companion object {
            const val ERROR_CARGA = "conexiones_carga_fallida"
            const val ERROR_PRUEBA = "conexiones_prueba_fallida"
            const val ERROR_GENERICO = "conexiones_operacion_fallida"
            const val ERROR_ACTUALIZACION = "conexiones_actualizacion_fallida"
            const val ERROR_SIN_URL = "conexiones_sin_url"
            const val ERROR_SIN_FUENTE = "conexiones_sin_fuente"
            const val ERROR_SIN_GUIA = "conexiones_sin_guia"
            const val AVISO_GUARDADA = "conexiones_guardada"
            const val AVISO_ELIMINADA = "conexiones_eliminada"
            const val AVISO_ACTUALIZACION_ENCOLADA = "conexiones_actualizacion_en_cola"
        }
    }
