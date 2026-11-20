package com.copernicotv.core.ota

/** Versión que la app reporta como instalada. */
data class VersionInstalada(
    val versionCode: Long,
    val versionName: String,
)

/** Motivo por el que una actualización más nueva no puede instalarse. */
enum class MotivoIncompatibilidad {
    ANDROID_DEMASIADO_ANTIGUO,
}

/** Decisión de la comprobación de actualizaciones. */
sealed interface DecisionActualizacion {
    /** No hay una versión publicada más nueva para este canal. */
    data object AlDia : DecisionActualizacion

    /** Hay una versión más nueva y compatible. */
    data class Disponible(val manifiesto: ManifiestoActualizacion) : DecisionActualizacion {
        /** La actualización no puede posponerse. */
        val obligatoria: Boolean get() = manifiesto.obligatoria
    }

    /** Hay una versión más nueva pero el dispositivo no la soporta. */
    data class Incompatible(
        val motivo: MotivoIncompatibilidad,
        val manifiesto: ManifiestoActualizacion,
    ) : DecisionActualizacion
}

/**
 * Elige la mejor actualización disponible.
 *
 * Reglas:
 * - Solo cuentan los manifiestos que el canal consultado acepta.
 * - Solo cuentan los `versionCode` estrictamente mayores al instalado: igual o
 *   menor no se ofrece, para no entrar en un ciclo de reinstalación.
 * - Si hay empate de `versionCode`, gana la publicación estable.
 * - Un candidato con `minSdk` mayor que el dispositivo se reporta como
 *   incompatible, nunca se descarta en silencio.
 */
fun elegirActualizacion(
    instalada: VersionInstalada,
    canal: CanalActualizacion,
    sdkInt: Int,
    manifiestos: List<ManifiestoActualizacion>,
): DecisionActualizacion {
    val candidato =
        manifiestos
            .asSequence()
            .filter { canal.acepta(it.canal) }
            .filter { it.versionCode > instalada.versionCode }
            .maxWithOrNull(comparadorCandidatos())
            ?: return DecisionActualizacion.AlDia
    if (candidato.minSdk > sdkInt) {
        return DecisionActualizacion.Incompatible(MotivoIncompatibilidad.ANDROID_DEMASIADO_ANTIGUO, candidato)
    }
    return DecisionActualizacion.Disponible(candidato)
}

private fun comparadorCandidatos(): Comparator<ManifiestoActualizacion> =
    compareBy<ManifiestoActualizacion> { it.versionCode }
        .thenBy { if (it.canal == CanalActualizacion.ESTABLE) 1 else 0 }
