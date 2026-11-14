package com.copernicotv.app.data

import android.os.SystemClock
import com.copernicotv.core.identity.AvanceEtapaImportacion
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicReference

/**
 * Foto de avance visible de una importación por URL (E7-T7).
 *
 * Contadores técnicos sin datos de la lista: entradas emitidas por el parser,
 * canales con sobres HMAC calculados, bytes descargados y tiempo transcurrido.
 */
data class AvanceVisible(
    val entradas: Int,
    val agrupadas: Int = 0,
    val canales: Int,
    val preparando: Int,
    val bytes: Long,
    val msTranscurrido: Long,
)

/**
 * El progreso se detuvo más allá del umbral razonable (E7-T7, S22).
 *
 * [msSinAvance] es el tiempo observado sin ningún avance; el código tipado
 * asociado es `avance_detenido`.
 */
class ImportacionAvanceDetenido(
    val msSinAvance: Long,
) : RuntimeException("avance_detenido")

/**
 * Control de avance de una importación por URL (E7-T7).
 *
 * Los ticks son contadores atómicos invocados por cada entrada del parser y
 * por cada canal del procesamiento: el callback de avance de `:core` los
 * alimenta. [verificar] lanza [ImportacionAvanceDetenido] cuando pasó el
 * umbral sin ningún tick desde el primer avance: el arranque lento de un
 * servidor (sin bytes todavía) queda cubierto por el timeout de lectura, no
 * por el vigilante. El chequeo corre en el flujo de la importación, en los
 * puntos de tick y de transición de fase: sin hilos ni corrutinas extra.
 *
 * Vigilancia de doble reloj (E7-T7 ronda 6, S43): el silencio se mide en wall
 * ([reloj]) y en uptime ([relojUptime]); solo se aborta cuando AMBOS relojes
 * muestran el mismo silencio prolongado. Un sueño del dispositivo pausa el
 * uptime mientras el wall avanza (Android suspende el hilo y `uptimeMillis`
 * no cuenta el sueño profundo): ese silencio es del SO, no del importador, y
 * no debe abortar la importación — la evidencia física de la ronda 6 abortó
 * con 934,8 s de wall sin actividad y uptime pausado. Un cuelgue real del
 * pipeline avanza ambos relojes y aborta igual que antes. El umbral no cambia.
 */
internal class AvanceImportacion(
    private val umbralSinAvanceMs: Long = UMBRAL_SIN_AVANCE_MS,
    private val reloj: () -> Long = System::currentTimeMillis,
    private val relojUptime: () -> Long = SystemClock::uptimeMillis,
) {
    private val entradas = AtomicLong(0)
    private val agrupadas = AtomicLong(0)
    private val canales = AtomicLong(0)
    private val preparando = AtomicLong(0)
    private val ultimoAvanceMs = AtomicLong(SIN_AVANCE)
    private val ultimoAvanceUptimeMs = AtomicLong(SIN_AVANCE)
    private val etapa = AtomicReference<AvanceEtapaImportacion?>(null)
    private val inicioMs = reloj()

    /** Proveedor de bytes leídos; lo fija la descarga con su stream contado. */
    @Volatile
    var bytesProveedor: (() -> Long)? = null

    /** Registra el total de entradas emitidas por el parser. */
    fun ticEntrada(total: Int) {
        entradas.set(total.toLong())
        tic()
    }

    /**
     * Registra el total de declaraciones canonizadas por la agrupación.
     *
     * Es la etapa más larga de una importación grande (E7-T7 ronda 3, L13):
     * sin este tick el vigilante abortaba durante la agrupación con
     * `canales=0`.
     */
    fun ticAgrupacion(total: Int) {
        agrupadas.set(total.toLong())
        tic()
    }

    /** Registra el total de canales con sobres calculados. */
    fun ticCanal(total: Int) {
        canales.set(total.toLong())
        tic()
    }

    /** Registra el total de canales preparados para persistir. */
    fun ticPreparacion(total: Int) {
        preparando.set(total.toLong())
        tic()
    }

    /**
     * Registra el avance de una etapa del pipeline (E7-T7 ronda 4, S34) y
     * reinicia el vigilante: todas las operaciones largas alimentan al
     * watchdog, no solo el parser y la agrupación.
     */
    fun ticEtapa(avanceEtapa: AvanceEtapaImportacion) {
        etapa.set(avanceEtapa)
        tic()
    }

    /** Última etapa emitida, para el informe copiable (S34). */
    fun ultimaEtapa(): AvanceEtapaImportacion? = etapa.get()

    /**
     * Verifica que haya avance reciente en AMBOS relojes; si no, aborta con
     * el error tipado (ronda 6, S43). [ImportacionAvanceDetenido.msSinAvance]
     * reporta el silencio de wall, como siempre.
     */
    fun verificar() {
        val ultimo = ultimoAvanceMs.get()
        if (ultimo == SIN_AVANCE) return
        val silencio = reloj() - ultimo
        if (silencio <= umbralSinAvanceMs) return
        val ultimoUptime = ultimoAvanceUptimeMs.get()
        if (ultimoUptime == SIN_AVANCE) return
        val silencioUptime = relojUptime() - ultimoUptime
        if (silencioUptime > umbralSinAvanceMs) throw ImportacionAvanceDetenido(silencio)
    }

    /** Foto del avance para la UI y el informe. */
    fun foto(): AvanceVisible =
        AvanceVisible(
            entradas = entradas.get().toInt(),
            agrupadas = agrupadas.get().toInt(),
            canales = canales.get().toInt(),
            preparando = preparando.get().toInt(),
            bytes = bytesProveedor?.invoke() ?: 0L,
            msTranscurrido = reloj() - inicioMs,
        )

    /** Línea técnica resumida, acotada y sin datos de la lista. */
    fun texto(): String =
        listOfNotNull(
            "entradas=${entradas.get()}",
            "agrupadas=${agrupadas.get()}",
            "canales=${canales.get()}",
            "preparando=${preparando.get()}",
            "bytes=${bytesProveedor?.invoke() ?: 0L}",
            "t=${reloj() - inicioMs}ms",
            textoEtapa(),
        ).joinToString(" ")

    /** Fragmento de la última etapa, o nulo si todavía no hay etapas (S34). */
    private fun textoEtapa(): String? =
        etapa.get()?.let { avanceEtapa ->
            "etapa=${avanceEtapa.etapa.codigo}" +
                " etapa_elementos=${avanceEtapa.elementos}" +
                " etapa_ms=${avanceEtapa.msEtapa}" +
                " etapa_gap_ms=${avanceEtapa.msDesdeUltimoAvance}" +
                " etapa_ops=${avanceEtapa.opsCriptograficas}" +
                " etapa_canales_validos=${avanceEtapa.canalesValidos}"
        }

    private fun tic() {
        ultimoAvanceMs.set(reloj())
        ultimoAvanceUptimeMs.set(relojUptime())
    }

    companion object {
        /** Umbral de ausencia de progreso (S22: «intervalo razonable»). */
        const val UMBRAL_SIN_AVANCE_MS = 90_000L

        /** Intervalo de publicación del avance a la UI. */
        const val INTERVALO_PUBLICACION_MS = 500L

        /** Centinela de «ningún tick todavía», válido incluso con reloj en 0. */
        private const val SIN_AVANCE = Long.MIN_VALUE
    }
}
