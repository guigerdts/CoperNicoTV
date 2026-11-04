package com.copernicotv.app.ui

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.ResumenImportacion
import com.copernicotv.app.diagnostico.detalleTecnico
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Estado observable de la pantalla de catálogo.
 *
 * `mensaje` lleva un código tipado, no texto: la capa de UI decide cómo se
 * presenta. Así el ViewModel no fija idioma ni copy.
 */
data class CatalogoUiState(
    val canales: List<String> = emptyList(),
    val importando: Boolean = false,
    val listaId: Long? = null,
    val mensaje: String? = null,
)

/**
 * ViewModel del canario de E1-C.
 *
 * Observa el catálogo de la Lista activa desde que se construye, y no solo tras
 * importar: la Lista se resuelve en Room, así que un proceso nuevo muestra el
 * catálogo ya persistido sin depender de ningún estado en memoria. E2/E3
 * reemplazan el disparador por el flujo de alta de Fuente, pero el camino de
 * persistencia es el mismo.
 */
@HiltViewModel
class CatalogoViewModel
    @Inject
    constructor(
        private val repositorio: CatalogoRepositorio,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(CatalogoUiState())
        val estado: StateFlow<CatalogoUiState> = _estado.asStateFlow()
        private var observacion: Job? = null

        init {
            observarCatalogo()
        }

        fun importarFixture() {
            importar { repositorio.importarFixture() }
        }

        /**
         * Importa una Lista desde un documento SAF elegido por el usuario.
         *
         * La selección ocurre en la capa de UI con `ACTION_OPEN_DOCUMENT`, que
         * es la única que produce un permiso persistible; aquí solo se resuelve
         * el nombre de la Fuente y se delega en el repositorio.
         */
        fun importarDesdeSaf(uriTexto: String) {
            importar {
                val nombre = repositorio.nombreDeDocumento(uriTexto)
                repositorio.importarDesdeSaf(nombre, uriTexto)
            }
        }

        private fun importar(bloque: suspend () -> ResumenImportacion) {
            if (_estado.value.importando) return
            _estado.value = _estado.value.copy(importando = true, mensaje = null)
            viewModelScope.launch {
                val resumen =
                    runCatching { bloque() }
                        .getOrElse { fallo ->
                            // El código de UI no distingue causas a propósito: es
                            // texto seguro y estable. El detalle técnico va al log
                            // sin mensajes de excepción, para no filtrar la URL de
                            // la Lista ni credenciales (S57a).
                            Log.e(TAG, "Importación fallida\n${detalleTecnico(fallo)}")
                            _estado.value =
                                _estado.value.copy(
                                    importando = false,
                                    mensaje = CODIGO_FALLO,
                                )
                            return@launch
                        }
                val listaId = resumen.listaId
                if (listaId == null) {
                    _estado.value =
                        _estado.value.copy(
                            importando = false,
                            mensaje = resumen.error ?: CODIGO_SIN_LISTA,
                        )
                    return@launch
                }
                _estado.value = _estado.value.copy(importando = false, listaId = listaId, mensaje = null)
                observarCatalogo()
            }
        }

        /**
         * Observa el catálogo de la Lista activa.
         *
         * Se suscribe al construir el ViewModel, no solo después de importar: la
         * Lista se resuelve en Room, así que un proceso nuevo carga el catálogo ya
         * persistido. Antes, `listaId` solo existía en memoria y, al reiniciar, la
         * pantalla quedaba vacía aunque los datos estuvieran intactos.
         */
        private fun observarCatalogo() {
            observacion?.cancel()
            observacion =
                viewModelScope.launch {
                    repositorio.canalesDeListaActiva().collectLatest { nombres ->
                        _estado.value = _estado.value.copy(canales = nombres.mapNotNull { it })
                    }
                }
        }

        private companion object {
            const val CODIGO_FALLO = "importacion_fallida"
            const val CODIGO_SIN_LISTA = "importacion_sin_lista"
            const val TAG = "CatalogoViewModel"
        }
    }
