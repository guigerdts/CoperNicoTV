package com.copernicotv.app.ui

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.copernicotv.app.ota.AlmacenManifiestoOta
import com.copernicotv.app.ota.ConfiguracionOta
import com.copernicotv.app.ota.DescargadorApk
import com.copernicotv.app.ota.DesenlaceInstalacion
import com.copernicotv.app.ota.EstadoInstalacionOta
import com.copernicotv.app.ota.FalloInstalacion
import com.copernicotv.app.ota.FalloOta
import com.copernicotv.app.ota.InstaladorApk
import com.copernicotv.app.ota.PasadaOta
import com.copernicotv.app.ota.ResultadoInstalacion
import com.copernicotv.app.ota.ResultadoOta
import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.ManifiestoActualizacion
import com.copernicotv.core.ota.ResultadoManifiesto
import com.copernicotv.core.ota.VersionInstalada
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.io.File
import javax.inject.Inject

/**
 * Última acción del OTA, en forma tipada.
 *
 * El ViewModel no conoce recursos de Android: la pantalla traduce cada variante
 * a su texto. Así el estado se puede probar en JVM y el mismo mensaje sirve en
 * móvil y en TV.
 */
sealed interface AvisoAjustes {
    /** No hay versión más nueva que la instalada. */
    object AlDia : AvisoAjustes

    /** Hay una publicación más nueva pero no se puede instalar en este equipo. */
    data class Incompatible(val versionName: String) : AvisoAjustes

    /** La comprobación o la descarga no terminaron. */
    data class Fallo(val tipo: FalloOta) : AvisoAjustes

    /** La app no tiene autorización para instalar paquetes. */
    data class SinPermiso(val detalle: String?) : AvisoAjustes

    /** El sistema rechazó la sesión de instalación antes de mostrar su diálogo. */
    data class InstalacionFallida(val tipo: FalloInstalacion) : AvisoAjustes

    /** El diálogo del sistema terminó sin instalar. */
    data class InstalacionRechazada(val detalle: String?) : AvisoAjustes

    /** El sistema confirmó la instalación. */
    object Instalada : AvisoAjustes

    /** El usuario cerró el diálogo del sistema. */
    object Cancelada : AvisoAjustes

    /** El diálogo del sistema está abierto. */
    object ConfirmacionPendiente : AvisoAjustes
}

/** Estado de la pantalla Ajustes > Actualizaciones (S9). */
data class AjustesUiState(
    val versionInstalada: String = "",
    val canal: CanalActualizacion = CanalActualizacion.POR_DEFECTO,
    val ultimaComprobacionMs: Long? = null,
    val disponible: ManifiestoActualizacion? = null,
    val archivoListo: File? = null,
    val comprobando: Boolean = false,
    val progreso: Int? = null,
    val permisoInstalacion: Boolean = true,
    val aviso: AvisoAjustes? = null,
) {
    /** True cuando hay un APK descargado y verificado listo para instalar. */
    val puedeInstalar: Boolean
        get() = disponible != null && archivoListo != null
}

/**
 * Pantalla Ajustes > Actualizaciones (S9).
 *
 * Reúne lo que el usuario necesita para decidir: versión instalada, canal,
 * última comprobación, versión disponible con sus notas, avance de la descarga
 * y la acción de instalar. La comprobación manual corre la misma pasada que el
 * trabajo periódico, sin esperar a WorkManager, para dar respuesta inmediata.
 */
@HiltViewModel
class AjustesViewModel
    @Inject
    constructor(
        private val pasada: PasadaOta,
        private val almacen: AlmacenManifiestoOta,
        private val instalador: InstaladorApk,
        private val descargador: DescargadorApk,
        private val version: VersionInstalada,
        private val configuracion: ConfiguracionOta,
    ) : ViewModel() {
        private val mutable =
            MutableStateFlow(
                AjustesUiState(
                    versionInstalada = version.versionName,
                    permisoInstalacion = instalador.permisoConcedido(),
                ),
            )

        val estado: StateFlow<AjustesUiState> = mutable.asStateFlow()

        init {
            viewModelScope.launch {
                almacen.canal().collect { canal -> mutable.update { it.copy(canal = canal) } }
            }
            viewModelScope.launch {
                almacen.ultimaComprobacionMs().collect { ms ->
                    mutable.update { it.copy(ultimaComprobacionMs = ms) }
                }
            }
            viewModelScope.launch {
                EstadoInstalacionOta.estado.collect { desenlace ->
                    if (desenlace != null) mutable.update { it.copy(aviso = desenlace.aAviso()) }
                }
            }
            viewModelScope.launch { recuperarPendiente() }
        }

        /** Comprueba, descarga y verifica; informa el avance mientras descarga. */
        fun buscar() {
            if (mutable.value.comprobando) return
            mutable.update { it.copy(comprobando = true, progreso = null, aviso = null) }
            viewModelScope.launch {
                val resultado =
                    try {
                        pasada.ejecutar { porcentaje ->
                            mutable.update { it.copy(progreso = porcentaje) }
                        }
                    } catch (cancelacion: CancellationException) {
                        throw cancelacion
                    } catch (fallo: Exception) {
                        ResultadoOta.Fallo(FalloOta.SERVIDOR, fallo.message)
                    }
                aplicar(resultado)
            }
        }

        /** Pide al sistema instalar el APK ya verificado. */
        fun instalar() {
            val actual = mutable.value
            val manifiesto = actual.disponible ?: return
            val archivo = actual.archivoListo ?: return
            if (!instalador.permisoConcedido()) {
                mutable.update {
                    it.copy(permisoInstalacion = false, aviso = AvisoAjustes.SinPermiso(null))
                }
                return
            }
            mutable.update { it.copy(aviso = null) }
            viewModelScope.launch {
                when (val resultado = instalador.instalar(archivo, manifiesto)) {
                    is ResultadoInstalacion.ConfirmacionPendiente ->
                        mutable.update { it.copy(aviso = AvisoAjustes.ConfirmacionPendiente) }
                    is ResultadoInstalacion.Fallo ->
                        mutable.update { it.copy(aviso = AvisoAjustes.InstalacionFallida(resultado.tipo)) }
                }
            }
        }

        /**
         * Cambia el canal y descarta la oferta anterior.
         *
         * No fuerza una comprobación: el usuario decide cuándo buscar y así el
         * cambio de canal no gasta red por sí solo.
         */
        fun cambiarCanal(canal: CanalActualizacion) {
            viewModelScope.launch {
                almacen.fijarCanal(canal)
                mutable.update { it.copy(disponible = null, archivoListo = null, aviso = null) }
            }
        }

        /** Relee la autorización de instalación al volver de los ajustes del sistema. */
        fun actualizarPermiso() {
            mutable.update { it.copy(permisoInstalacion = instalador.permisoConcedido()) }
        }

        /** Limpia el aviso, incluido el desenlace de instalación del sistema. */
        fun olvidarAviso() {
            EstadoInstalacionOta.olvidar()
            mutable.update { it.copy(aviso = null) }
        }

        /**
         * Recupera la oferta que sobrevivió al reinicio.
         *
         * El manifiesto guardado ya se verificó antes de persistirse; se vuelve
         * a parsear y solo se ofrece si el APK en disco sigue coincidiendo con
         * su hash. Un documento ilegible se descarta en silencio: la próxima
         * comprobación lo reemplaza.
         */
        private suspend fun recuperarPendiente() {
            val guardado = almacen.cargarManifiesto() ?: return
            val manifiesto =
                when (
                    val parseado =
                        ManifiestoActualizacion.parse(
                            String(guardado.json, Charsets.UTF_8),
                            configuracion.paquete,
                            configuracion.huellaFirmaApk,
                        )
                ) {
                    is ResultadoManifiesto.Invalido -> return
                    is ResultadoManifiesto.Valido -> parseado.manifiesto
                }
            if (!descargador.estaVerificado(manifiesto)) return
            mutable.update {
                it.copy(
                    disponible = manifiesto,
                    archivoListo = descargador.archivoFinal(manifiesto),
                )
            }
        }

        private fun aplicar(resultado: ResultadoOta) {
            when (resultado) {
                is ResultadoOta.Descargada ->
                    mutable.update {
                        it.copy(
                            comprobando = false,
                            progreso = null,
                            disponible = resultado.manifiesto,
                            archivoListo = resultado.archivo,
                        )
                    }
                is ResultadoOta.SinNovedad ->
                    mutable.update {
                        it.copy(
                            comprobando = false,
                            progreso = null,
                            disponible = null,
                            archivoListo = null,
                            aviso = AvisoAjustes.AlDia,
                            ultimaComprobacionMs = resultado.ultimaComprobacionMs ?: it.ultimaComprobacionMs,
                        )
                    }
                is ResultadoOta.Incompatible ->
                    mutable.update {
                        it.copy(
                            comprobando = false,
                            progreso = null,
                            disponible = null,
                            archivoListo = null,
                            aviso = AvisoAjustes.Incompatible(resultado.manifiesto.versionName),
                        )
                    }
                is ResultadoOta.Fallo ->
                    mutable.update {
                        it.copy(comprobando = false, progreso = null, aviso = AvisoAjustes.Fallo(resultado.tipo))
                    }
            }
        }

        private fun DesenlaceInstalacion.aAviso(): AvisoAjustes =
            when (this) {
                DesenlaceInstalacion.INSTALADA -> AvisoAjustes.Instalada
                DesenlaceInstalacion.CANCELADA -> AvisoAjustes.Cancelada
                DesenlaceInstalacion.FALLIDA -> AvisoAjustes.InstalacionRechazada(EstadoInstalacionOta.detalle)
            }
    }
