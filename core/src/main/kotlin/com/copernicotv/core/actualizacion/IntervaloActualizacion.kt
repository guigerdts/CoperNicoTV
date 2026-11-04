package com.copernicotv.core.actualizacion

/**
 * Intervalo de la Actualización automática (DC-07).
 *
 * El contrato fija el valor por defecto en 720 minutos, un mínimo de 15 y un
 * máximo de 10080. Un valor fuera de rango se rechaza con un motivo tipado; no
 * se recorta en silencio, porque un recorte silencioso haría que la UI mostrara
 * un valor distinto del que el planificador aplica.
 */
@JvmInline
value class IntervaloActualizacion private constructor(
    val minutos: Long,
) {
    companion object {
        /** Valor por defecto del intervalo global, en minutos. */
        const val POR_DEFECTO: Long = 720L

        /** Mínimo admitido, en minutos. */
        const val MINIMO: Long = 15L

        /** Máximo admitido, en minutos. */
        const val MAXIMO: Long = 10080L

        /** Valida [minutos] y devuelve el intervalo o el motivo del rechazo. */
        fun de(minutos: Long): ResultadoIntervalo =
            when {
                minutos < MINIMO -> ResultadoIntervalo.Rechazado(MotivoRechazo.DEBAJO_DEL_MINIMO)
                minutos > MAXIMO -> ResultadoIntervalo.Rechazado(MotivoRechazo.SOBRE_EL_MAXIMO)
                else -> ResultadoIntervalo.Aceptado(IntervaloActualizacion(minutos))
            }
    }
}

/** Motivo por el que un intervalo no se admite. */
enum class MotivoRechazo {
    DEBAJO_DEL_MINIMO,
    SOBRE_EL_MAXIMO,
}

/** Resultado de validar un intervalo. */
sealed interface ResultadoIntervalo {
    data class Aceptado(
        val intervalo: IntervaloActualizacion,
    ) : ResultadoIntervalo

    data class Rechazado(
        val motivo: MotivoRechazo,
    ) : ResultadoIntervalo
}

/**
 * Resuelve el intervalo efectivo de una Fuente (DC-07 punto 2).
 *
 * La bandera «usar global» manda sobre el valor propio. Si no hay valor propio
 * y la bandera está apagada, se usa el global: la ausencia de override no puede
 * dejar la Fuente sin programación.
 */
fun intervaloEfectivo(
    globalMinutos: Long,
    propioMinutos: Long?,
    usarGlobal: Boolean,
): ResultadoIntervalo = IntervaloActualizacion.de(if (usarGlobal || propioMinutos == null) globalMinutos else propioMinutos)
