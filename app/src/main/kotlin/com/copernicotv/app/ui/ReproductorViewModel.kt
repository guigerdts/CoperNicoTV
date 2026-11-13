package com.copernicotv.app.ui

import android.content.Context
import androidx.annotation.VisibleForTesting
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.exoplayer.ExoPlayer
import com.copernicotv.app.data.BusquedaCatalogoRepositorio
import com.copernicotv.app.data.PreferenciasReproduccion
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.RepositorioPreferenciasReproduccion
import com.copernicotv.app.data.ReproductorRepositorio
import com.copernicotv.app.data.ResultadoStream
import com.copernicotv.app.player.DiagnosticoReproductor
import com.copernicotv.app.player.DireccionZapeo
import com.copernicotv.app.player.EstadoPistas
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.player.ModoAspecto
import com.copernicotv.app.player.PASO_VOLUMEN
import com.copernicotv.app.player.PistaDisponible
import com.copernicotv.app.player.PoliticaReintentos
import com.copernicotv.app.player.PreferenciaPista
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.player.SesionMedia3
import com.copernicotv.app.player.canalVecino
import com.copernicotv.app.player.construirDiagnostico
import com.copernicotv.app.player.limitarVolumen
import com.copernicotv.app.player.sanearUrl
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
     * La URL original no vive en este estado: solo se conserva su versión
     * saneada (esquema, host y puerto). Un log accidental del estado no puede
     * exponer la credencial del flujo (S57a).
     */
    data class Listo(
        val canalId: Long,
        val nombre: String?,
        val urlSaneada: String,
        val protocolo: ProtocoloStream,
    ) : ReproductorUiState {
        override fun toString(): String = "Listo(canalId=$canalId, protocolo=$protocolo)"
    }

    data class Error(val codigo: String) : ReproductorUiState
}

/**
 * Disponibilidad de zapeo del Canal activo (E6-T6).
 *
 * Se calcula contra el universo visible del Catálogo: un Canal sin anterior o
 * sin siguiente deshabilita su botón en lugar de envolver la navegación.
 */
data class ZapeoEstado(
    val tieneAnterior: Boolean = false,
    val tieneSiguiente: Boolean = false,
)

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
        private val busqueda: BusquedaCatalogoRepositorio,
        @ApplicationContext private val contexto: Context,
        private val preferenciasPista: RepositorioPreferenciasPista,
        private val preferenciasReproduccion: RepositorioPreferenciasReproduccion,
        private val politicaReintentos: PoliticaReintentos,
    ) : ViewModel() {
        private val _estado = MutableStateFlow<ReproductorUiState>(ReproductorUiState.Cargando)
        val estado: StateFlow<ReproductorUiState> = _estado.asStateFlow()

        private val _sesionEstado = MutableStateFlow(EstadoSesion())
        val sesionEstado: StateFlow<EstadoSesion> = _sesionEstado.asStateFlow()

        private val _estadoPistas = MutableStateFlow(EstadoPistas())
        val estadoPistas: StateFlow<EstadoPistas> = _estadoPistas.asStateFlow()

        private val _diagnostico = MutableStateFlow(DiagnosticoReproductor())
        val diagnostico: StateFlow<DiagnosticoReproductor> = _diagnostico.asStateFlow()

        private val _modoAspecto = MutableStateFlow(ModoAspecto.AJUSTAR)
        val modoAspecto: StateFlow<ModoAspecto> = _modoAspecto.asStateFlow()

        private val _controlesSecundariosVisibles = MutableStateFlow(true)
        val controlesSecundariosVisibles: StateFlow<Boolean> = _controlesSecundariosVisibles.asStateFlow()

        /** Volumen vigente en la escala 0..1 de Media3; no se persiste (E6-T6). */
        private val _volumen = MutableStateFlow(VOLUMEN_INICIAL)
        val volumen: StateFlow<Float> = _volumen.asStateFlow()

        /** Disponibilidad de zapeo del Canal activo (E6-T6). */
        private val _zapeo = MutableStateFlow(ZapeoEstado())
        val zapeo: StateFlow<ZapeoEstado> = _zapeo.asStateFlow()

        /** Motor de la sesión activa; `null` mientras no hay sesión abierta. */
        val motor: ExoPlayer?
            get() = sesion?.reproductor

        private var sesion: SesionMedia3? = null
        private var canalCargado: Long? = null
        private var carga: Job? = null
        private var recolector: Job? = null
        private var recolectorPistas: Job? = null
        private var preferenciasActuales = PreferenciasPista()
        private var protocoloActual: ProtocoloStream? = null
        private var urlSaneadaActual: String? = null
        private var ultimoError: String? = null

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
                            val urlSaneada = sanearUrl(resultado.stream.url)
                            protocoloActual = resultado.stream.protocolo
                            urlSaneadaActual = urlSaneada
                            ultimoError = null
                            val datos =
                                ReproductorUiState.Listo(
                                    canalId = resultado.stream.canalId,
                                    nombre = resultado.stream.nombre,
                                    urlSaneada = urlSaneada,
                                    protocolo = resultado.stream.protocolo,
                                )
                            actualizarDiagnostico(_sesionEstado.value, _estadoPistas.value)
                            preferenciasActuales =
                                runCatching { preferenciasPista.leer() }
                                    .getOrDefault(PreferenciasPista())
                            val reproduccion =
                                runCatching { preferenciasReproduccion.leer() }
                                    .getOrDefault(PreferenciasReproduccion())
                            _modoAspecto.value = reproduccion.modoAspecto
                            _controlesSecundariosVisibles.value = reproduccion.controlesSecundariosVisibles
                            val nueva =
                                abrirSesion(resultado.stream.url, resultado.stream.protocolo, preferenciasActuales)
                            // El volumen vigente sobrevive al zapeo: la sesión
                            // nueva hereda el nivel actual (E6-T6).
                            nueva.fijarVolumen(_volumen.value)
                            // Si la sesión falló antes de publicar el Canal, el
                            // error definitivo ya ganó: no lo pisamos con Listo.
                            if (sesion === nueva) {
                                _estado.value = datos
                                refrescarZapeo(resultado.stream.canalId)
                            }
                        }
                        is ResultadoStream.Error -> {
                            _diagnostico.value =
                                DiagnosticoReproductor(
                                    fase = EstadoReproductor.ERROR,
                                    ultimoError = resultado.codigo,
                                )
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

        /** Zapea al Canal siguiente del Catálogo sin salir de la pantalla (E6-T6). */
        fun zapearSiguiente() = zapear(DireccionZapeo.SIGUIENTE)

        /** Zapea al Canal anterior del Catálogo sin salir de la pantalla (E6-T6). */
        fun zapearAnterior() = zapear(DireccionZapeo.ANTERIOR)

        /** Sube el volumen un paso, acotado a la escala de Media3 (E6-T6). */
        fun subirVolumen() = fijarVolumen(_volumen.value + PASO_VOLUMEN)

        /** Baja el volumen un paso, acotado a la escala de Media3 (E6-T6). */
        fun bajarVolumen() = fijarVolumen(_volumen.value - PASO_VOLUMEN)

        /** Fija el volumen y lo aplica a la sesión activa; no se persiste. */
        fun fijarVolumen(valor: Float) {
            val nuevo = limitarVolumen(valor)
            _volumen.value = nuevo
            sesion?.fijarVolumen(nuevo)
        }

        /**
         * Consulta el universo de zapeo y carga el Canal vecino.
         *
         * El destino se calcula con el Canal vigente al resolverse la consulta,
         * de modo que dos zapeos consecutivos avanzan en orden. En los límites
         * de navegación o sin Canal cargado no hay destino: no se toca la
         * sesión y [cargar] reutiliza toda la maquinaria de sustitución.
         */
        private fun zapear(direccion: DireccionZapeo) {
            viewModelScope.launch {
                val vecinos = runCatching { busqueda.idsZapeo() }.getOrDefault(emptyList())
                val vigente = canalCargado ?: return@launch
                val destino = canalVecino(vecinos, vigente, direccion) ?: return@launch
                cargar(destino)
            }
        }

        /**
         * Recalcula la disponibilidad de zapeo tras una carga Ok.
         *
         * El guard contra `canalCargado` evita que un refresco en vuelo de un
         * zapeo anterior pise el estado del Canal actual (E6-T6).
         */
        private fun refrescarZapeo(canalId: Long) {
            viewModelScope.launch {
                val vecinos = runCatching { busqueda.idsZapeo() }.getOrDefault(emptyList())
                if (canalCargado != canalId) return@launch
                _zapeo.value =
                    ZapeoEstado(
                        tieneAnterior = canalVecino(vecinos, canalId, DireccionZapeo.ANTERIOR) != null,
                        tieneSiguiente = canalVecino(vecinos, canalId, DireccionZapeo.SIGUIENTE) != null,
                    )
            }
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

        /** Avanza al siguiente modo de aspecto y lo guarda como preferencia global (S44b). */
        fun alternarModoAspecto() {
            val nuevo = _modoAspecto.value.siguiente()
            _modoAspecto.value = nuevo
            persistirReproduccion(
                PreferenciasReproduccion(
                    modoAspecto = nuevo,
                    controlesSecundariosVisibles = _controlesSecundariosVisibles.value,
                ),
            )
        }

        /** Fija la visibilidad de los controles secundarios fijos y la guarda (S17b). */
        fun fijarControlesSecundarios(visibles: Boolean) {
            _controlesSecundariosVisibles.value = visibles
            persistirReproduccion(
                PreferenciasReproduccion(
                    modoAspecto = _modoAspecto.value,
                    controlesSecundariosVisibles = visibles,
                ),
            )
        }

        /** Cierra la sesión al salir de la pantalla y permite reabrir el Canal. */
        fun cerrar() {
            carga?.cancel()
            carga = null
            cerrarSesion()
            canalCargado = null
            _zapeo.value = ZapeoEstado()
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
            protocolo: ProtocoloStream,
            preferencias: PreferenciasPista,
        ): SesionMedia3 {
            val nueva = SesionMedia3(contexto, url, protocolo, preferencias, politica = politicaReintentos)
            sesion = nueva
            recolector?.cancel()
            recolector =
                viewModelScope.launch {
                    nueva.estado.collect { estadoSesion ->
                        // Un callback de la sesión anterior no debe tocar la nueva (E6-T4).
                        if (sesion !== nueva) return@collect
                        _sesionEstado.value = estadoSesion
                        estadoSesion.codigoError?.let { ultimoError = it }
                        actualizarDiagnostico(estadoSesion, _estadoPistas.value)
                        if (estadoSesion.estado == EstadoReproductor.ERROR) {
                            _estado.value =
                                ReproductorUiState.Error(estadoSesion.codigoError ?: "error_reproduccion")
                            // La sesión se cierra, pero el diagnóstico queda
                            // congelado para la pantalla de error (E6-T5).
                            cerrarSesion(conservarDiagnostico = true)
                        }
                    }
                }
            recolectorPistas?.cancel()
            recolectorPistas =
                viewModelScope.launch {
                    nueva.estadoPistas.collect { pistas ->
                        if (sesion !== nueva) return@collect
                        _estadoPistas.value = pistas
                        actualizarDiagnostico(_sesionEstado.value, pistas)
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

        private fun persistirReproduccion(preferencias: PreferenciasReproduccion) {
            viewModelScope.launch {
                runCatching { preferenciasReproduccion.guardar(preferencias) }
            }
        }

        private fun actualizarDiagnostico(
            estadoSesion: EstadoSesion,
            pistas: EstadoPistas,
        ) {
            _diagnostico.value =
                construirDiagnostico(
                    protocolo = protocoloActual,
                    url = urlSaneadaActual,
                    estado = estadoSesion,
                    pistas = pistas,
                    ultimoError = ultimoError,
                    canalId = canalCargado,
                )
        }

        private fun cerrarSesion(conservarDiagnostico: Boolean = false) {
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
            if (!conservarDiagnostico) {
                _diagnostico.value = DiagnosticoReproductor()
            }
            protocoloActual = null
            urlSaneadaActual = null
            ultimoError = null
        }

        private companion object {
            const val VOLUMEN_INICIAL = 1.0f
        }
    }
