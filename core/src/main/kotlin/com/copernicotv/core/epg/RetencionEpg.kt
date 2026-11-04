package com.copernicotv.core.epg

import com.copernicotv.core.identity.longitudUtf8
import java.time.Instant
import java.time.temporal.ChronoUnit

/** Límites efectivos de retención de una `FuenteGuia` (E5-T3). */
data class LimitesRetencion(
    val ventanaPasadoDias: Long = VENTANA_PASADO_DIAS,
    val ventanaFuturoDias: Long = VENTANA_FUTURO_DIAS,
    val maxProgramasPorCanal: Int = MAX_PROGRAMAS_POR_CANAL,
    val presupuestoBytes: Long = PRESUPUESTO_BYTES,
) {
    init {
        require(ventanaPasadoDias >= 0) { "ventana_pasado_dias inválida" }
        require(ventanaFuturoDias >= 0) { "ventana_futuro_dias inválida" }
        require(maxProgramasPorCanal > 0) { "max_programas inválido" }
        require(presupuestoBytes > 0) { "presupuesto_bytes inválido" }
    }

    companion object {
        const val VENTANA_PASADO_DIAS: Long = 1L
        const val VENTANA_FUTURO_DIAS: Long = 7L
        const val MAX_PROGRAMAS_POR_CANAL: Int = 500
        const val PRESUPUESTO_BYTES: Long = 200L * 1024L * 1024L

        /** Valores iniciales del producto. */
        val GLOBAL: LimitesRetencion = LimitesRetencion()
    }
}

/**
 * Configuración persistida por `FuenteGuia`.
 *
 * Cada campo `null` hereda el global; [usarGlobal] ignora todos los explícitos.
 */
data class RetencionFuente(
    val usarGlobal: Boolean = true,
    val ventanaPasadoDias: Long? = null,
    val ventanaFuturoDias: Long? = null,
    val maxProgramasPorCanal: Long? = null,
    val presupuestoBytes: Long? = null,
)

/** Programa con los metadatos que la retención necesita. */
data class ProgramaRetenible<T>(
    val valor: T,
    val canal: String,
    val inicio: Instant,
    val fin: Instant,
    val tamanoLogico: Long,
)

/** Resultado de aplicar retención: conservados y conteos de evicción. */
data class ResultadoRetencion<T>(
    val conservados: List<T>,
    val fueraDeVentana: Int,
    val evictosPorCanal: Int,
    val evictosPorPresupuesto: Int,
    val bytesConservados: Long,
)

/**
 * Retención de EPG (E5-T3, ADR-0003).
 *
 * Orden de aplicación:
 * 1. ventana temporal `[ahora - pasado, ahora + futuro)`, conservando el
 *    programa que solapa el borde (uno que empezó antes y sigue en emisión);
 * 2. máximo por `channel_id_texto`, evictando primero el más antiguo;
 * 3. presupuesto lógico global, evictando primero la Programación más antigua
 *    de la fuente.
 *
 * Los empates se resuelven por canal y luego por orden de entrada, para que el
 * resultado sea determinista.
 */
object RetencionEpg {
    fun resolver(
        global: LimitesRetencion,
        fuente: RetencionFuente,
    ): LimitesRetencion {
        if (fuente.usarGlobal) return global
        val maximo = fuente.maxProgramasPorCanal ?: global.maxProgramasPorCanal.toLong()
        require(maximo in 1..Int.MAX_VALUE.toLong()) { "max_programas inválido" }
        return LimitesRetencion(
            ventanaPasadoDias = fuente.ventanaPasadoDias ?: global.ventanaPasadoDias,
            ventanaFuturoDias = fuente.ventanaFuturoDias ?: global.ventanaFuturoDias,
            maxProgramasPorCanal = maximo.toInt(),
            presupuestoBytes = fuente.presupuestoBytes ?: global.presupuestoBytes,
        )
    }

    /**
     * Costo lógico de una fila `Programa`: tres longitudes UTF-8 con prefijo de
     * 4 bytes cada una, más 8 bytes por cada instante (`inicio`, `fin`).
     */
    fun tamanoLogico(
        channelIdTexto: String,
        titulo: String,
        descripcion: String?,
    ): Long =
        4L + longitudUtf8(channelIdTexto) +
            4L + longitudUtf8(titulo) +
            4L + longitudUtf8(descripcion.orEmpty()) +
            8L + 8L

    fun <T> aplicar(
        programas: List<T>,
        ahora: Instant,
        limites: LimitesRetencion,
        canal: (T) -> String,
        inicio: (T) -> Instant,
        fin: (T) -> Instant,
        tamano: (T) -> Long,
    ): ResultadoRetencion<T> {
        val desde = ahora.minus(limites.ventanaPasadoDias, ChronoUnit.DAYS)
        val hasta = ahora.plus(limites.ventanaFuturoDias, ChronoUnit.DAYS)

        val conIndice =
            programas.mapIndexed { indice, valor ->
                ProgramaRetenible(
                    valor = valor,
                    canal = canal(valor),
                    inicio = inicio(valor),
                    fin = fin(valor),
                    tamanoLogico = tamano(valor),
                ) to indice
            }
        val enVentana = conIndice.filter { (programa, _) -> programa.fin.isAfter(desde) && programa.inicio.isBefore(hasta) }
        val fueraDeVentana = programas.size - enVentana.size

        val porCanal = enVentana.groupBy { (programa, _) -> programa.canal }
        val conservadosPorCanal = mutableListOf<Pair<ProgramaRetenible<T>, Int>>()
        var evictosPorCanal = 0
        for ((_, grupo) in porCanal) {
            val ordenado =
                grupo.sortedWith(
                    compareBy<Pair<ProgramaRetenible<T>, Int>> { it.first.inicio }
                        .thenBy { it.first.canal }
                        .thenBy { it.second },
                )
            val exceso = (ordenado.size - limites.maxProgramasPorCanal).coerceAtLeast(0)
            evictosPorCanal += exceso
            conservadosPorCanal += ordenado.drop(exceso)
        }

        var evictosPorPresupuesto = 0
        var bytes = conservadosPorCanal.sumOf { it.first.tamanoLogico }
        val ordenadosGlobal =
            conservadosPorCanal.sortedWith(
                compareBy<Pair<ProgramaRetenible<T>, Int>> { it.first.inicio }
                    .thenBy { it.first.canal }
                    .thenBy { it.second },
            )
        val conservados = ordenadosGlobal.toMutableList()
        var cursor = 0
        while (bytes > limites.presupuestoBytes && cursor < conservados.size) {
            bytes -= conservados[cursor].first.tamanoLogico
            evictosPorPresupuesto++
            cursor++
        }
        val finales = conservados.drop(cursor).map { it.first.valor }

        return ResultadoRetencion(
            conservados = finales,
            fueraDeVentana = fueraDeVentana,
            evictosPorCanal = evictosPorCanal,
            evictosPorPresupuesto = evictosPorPresupuesto,
            bytesConservados = bytes,
        )
    }
}
