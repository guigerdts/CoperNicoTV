package com.copernicotv.app.ota

import com.copernicotv.core.ota.CanalActualizacion
import com.copernicotv.core.ota.DecisionActualizacion
import com.copernicotv.core.ota.ManifiestoActualizacion
import com.copernicotv.core.ota.VersionInstalada
import com.copernicotv.core.ota.elegirActualizacion
import kotlinx.coroutines.flow.first

/** Motivo tipado de un fallo de comprobación. */
enum class FalloComprobacion {
    CONFIGURACION,
    SIN_RED,
    SERVIDOR,
    FIRMA_INVALIDA,
    MANIFIESTO_INVALIDO,
    SIN_PUBLICACIONES,
}

/** Resultado visible de "Buscar actualizaciones". */
sealed interface ResultadoComprobacion {
    data class Disponible(val manifiesto: ManifiestoActualizacion) : ResultadoComprobacion

    data object AlDia : ResultadoComprobacion

    data class Incompatible(val manifiesto: ManifiestoActualizacion) : ResultadoComprobacion

    data class Fallo(
        val tipo: FalloComprobacion,
        val detalle: String? = null,
    ) : ResultadoComprobacion
}

/**
 * Comprobación del canal y decisión de actualización.
 *
 * Orquesta fuente, preferencias y reglas puras de `:core`. No descarga ni
 * instala: eso queda en capas posteriores, que reutilizan el manifiesto ya
 * verificado. La última comprobación exitosa se persiste para que la UI pueda
 * mostrar "última comprobación" sin repetir la consulta.
 *
 * Una publicación que el usuario decidió omitir se reporta como al día, salvo
 * que sea obligatoria.
 */
class ComprobadorActualizaciones(
    private val fuente: FuenteManifiestos,
    private val almacen: AlmacenManifiestoOta,
    private val configuracion: ConfiguracionOta,
    private val versionInstalada: VersionInstalada,
    private val sdkInt: Int,
    private val ahora: () -> Long = System::currentTimeMillis,
) {
    suspend fun comprobar(): ResultadoComprobacion {
        if (!configuracion.configurada) return ResultadoComprobacion.Fallo(FalloComprobacion.CONFIGURACION)
        val canal = almacen.canalActual()
        return when (val resultado = fuente.buscar()) {
            is ResultadoFuente.SinPublicaciones -> alDia()
            is ResultadoFuente.Fallo -> ResultadoComprobacion.Fallo(tipoDe(resultado.tipo), resultado.detalle)
            is ResultadoFuente.Manifiestos -> decidir(canal, resultado.verificados)
        }
    }

    private suspend fun decidir(
        canal: CanalActualizacion,
        verificados: List<ManifiestoVerificado>,
    ): ResultadoComprobacion {
        val decision = elegirActualizacion(versionInstalada, canal, sdkInt, verificados.map { it.manifiesto })
        return when (decision) {
            DecisionActualizacion.AlDia -> alDia()
            is DecisionActualizacion.Incompatible -> guardarYDevolver(verificados, decision.manifiesto) {
                ResultadoComprobacion.Incompatible(decision.manifiesto)
            }
            is DecisionActualizacion.Disponible -> {
                val omitida = almacen.versionOmitida().first()
                if (!decision.obligatoria && omitida == decision.manifiesto.versionCode) {
                    alDia()
                } else {
                    guardarYDevolver(verificados, decision.manifiesto) {
                        ResultadoComprobacion.Disponible(decision.manifiesto)
                    }
                }
            }
        }
    }

    private suspend fun guardarYDevolver(
        verificados: List<ManifiestoVerificado>,
        manifiesto: ManifiestoActualizacion,
        resultado: () -> ResultadoComprobacion,
    ): ResultadoComprobacion {
        val verificado =
            verificados.firstOrNull {
                it.manifiesto.versionCode == manifiesto.versionCode && it.manifiesto.canal == manifiesto.canal
            }
        if (verificado != null) {
            almacen.guardarComprobacion(verificado, ahora())
        } else {
            almacen.marcarComprobacion(ahora())
        }
        return resultado()
    }

    private suspend fun alDia(): ResultadoComprobacion {
        almacen.marcarComprobacion(ahora())
        return ResultadoComprobacion.AlDia
    }

    private fun tipoDe(fallo: FalloFuente): FalloComprobacion =
        when (fallo) {
            FalloFuente.CONFIGURACION -> FalloComprobacion.CONFIGURACION
            FalloFuente.SIN_RED -> FalloComprobacion.SIN_RED
            FalloFuente.SERVIDOR -> FalloComprobacion.SERVIDOR
            FalloFuente.FIRMA_INVALIDA -> FalloComprobacion.FIRMA_INVALIDA
            FalloFuente.RESPUESTA_INVALIDA -> FalloComprobacion.MANIFIESTO_INVALIDO
            FalloFuente.SIN_PUBLICACIONES -> FalloComprobacion.SIN_PUBLICACIONES
        }
}
