package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.data.CatalogoRepositorio
import dagger.hilt.android.lifecycle.HiltViewModel
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
 * `importarFixture` ejecuta el camino real de importación y luego observa el
 * catálogo de la Lista resultante. E2/E3 reemplazan el disparador por el flujo
 * de alta de Fuente, pero el camino de persistencia es el mismo.
 */
@HiltViewModel
class CatalogoViewModel
    @Inject
    constructor(
        private val repositorio: CatalogoRepositorio,
    ) : ViewModel() {
        private val _estado = MutableStateFlow(CatalogoUiState())
        val estado: StateFlow<CatalogoUiState> = _estado.asStateFlow()

        fun importarFixture() {
            if (_estado.value.importando) return
            _estado.value = _estado.value.copy(importando = true, mensaje = null)
            viewModelScope.launch {
                val resumen =
                    runCatching { repositorio.importarFixture() }
                        .getOrElse { error ->
                            _estado.value =
                                _estado.value.copy(
                                    importando = false,
                                    mensaje = error.message ?: CODIGO_FALLO,
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
                observar(listaId)
            }
        }

        private fun observar(listaId: Long) {
            viewModelScope.launch {
                repositorio.canalesDeLista(listaId).collectLatest { nombres ->
                    _estado.value = _estado.value.copy(canales = nombres.mapNotNull { it })
                }
            }
        }

        private companion object {
            const val CODIGO_FALLO = "importacion_fallida"
            const val CODIGO_SIN_LISTA = "importacion_sin_lista"
        }
    }
