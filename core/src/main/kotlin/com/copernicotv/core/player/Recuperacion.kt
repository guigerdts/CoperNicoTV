package com.copernicotv.core.player

import kotlin.math.min
import kotlin.random.Random

/** Clasificación de un fallo del motor respecto de la política de recuperación (E7-T10, S4). */
enum class ClaseFallo {
    TRANSITORIO,
    DEFINITIVO,
}

/** Acción que la sesión debe ejecutar ante un fallo del motor. */
sealed interface DecisionReintento {
    /** Programa el reintento número [numero] tras [esperaMs] milisegundos. */
    data class Reintentar(
        val numero: Int,
        val esperaMs: Long,
    ) : DecisionReintento

    /** Ya hay un reintento pendiente: el fallo duplicado no programa otro. */
    data object Ignorar : DecisionReintento

    /** El presupuesto se agotó o el fallo no es recuperable. */
    data object Definitivo : DecisionReintento
}

/**
 * Parámetros configurables de la recuperación de E7-T10 (S4).
 *
 * La espera entre reintentos es exponencial con jitter: [esperaBaseMs] se
 * duplica por intento hasta [esperaMaximaMs], y a ese valor se le agrega una
 * fracción aleatoria acotada por [fraccionJitter] para evitar reintentos
 * sincronizados. [maximoIntentos] es el presupuesto de una sesión: se repone
 * tras [periodoEstabilidadMs] de reproducción estable, nunca antes.
 *
 * [azar] produce valores en `0.0..1.0`; es inyectable para que las pruebas
 * sean deterministas. La producción usa [Random].
 */
data class ConfiguracionRecuperacion(
    val esperaBaseMs: Long = 1_000L,
    val esperaMaximaMs: Long = 30_000L,
    val maximoIntentos: Int = 6,
    val periodoEstabilidadMs: Long = 10_000L,
    val fraccionJitter: Double = 0.5,
    val azar: () -> Double = { Random.nextDouble() },
) {
    init {
        require(esperaBaseMs > 0L) { "esperaBaseMs debe ser positivo" }
        require(esperaMaximaMs >= esperaBaseMs) { "esperaMaximaMs no puede ser menor que esperaBaseMs" }
        require(maximoIntentos > 0) { "maximoIntentos debe ser positivo" }
        require(periodoEstabilidadMs > 0L) { "periodoEstabilidadMs debe ser positivo" }
        require(fraccionJitter in 0.0..1.0) { "fraccionJitter debe estar en 0.0..1.0" }
    }
}

/**
 * Espera del reintento [intento]: exponencial con jitter, acotada al techo.
 *
 * La duplicación se calcula paso a paso para no desbordar `Long` con
 * intentos altos: si el valor ya llega al techo o duplicar lo desborda,
 * el cálculo termina en [ConfiguracionRecuperacion.esperaMaximaMs]. El
 * jitter agrega una fracción aleatoria del exponencial y el resultado se
 * limita de nuevo al techo; un azar fuera de rango se recorta en lugar de
 * propagar valores negativos.
 */
fun esperaRecuperacion(
    intento: Int,
    configuracion: ConfiguracionRecuperacion,
): Long = esperaRecuperacion(intento, configuracion, configuracion.azar)

fun esperaRecuperacion(
    intento: Int,
    configuracion: ConfiguracionRecuperacion,
    azar: () -> Double,
): Long {
    val techo = configuracion.esperaMaximaMs
    val base = potenciaAcotada(configuracion.esperaBaseMs, intento.coerceAtLeast(1) - 1, techo)
    val fraccion = azar().coerceIn(0.0, 1.0)
    val jitter = (base.toDouble() * configuracion.fraccionJitter * fraccion).toLong()
    // Suma sin desbordamiento: base y jitter son no negativos y base <= techo.
    return if (jitter >= techo - base) techo else base + jitter
}

/** Base elevada a `2^exponente`, deteniéndose en [techo] antes de desbordar. */
private fun potenciaAcotada(
    base: Long,
    exponente: Int,
    techo: Long,
): Long {
    var valor = base
    repeat(exponente) {
        if (valor >= techo) return techo
        valor = if (valor > Long.MAX_VALUE / 2) techo else valor * 2
    }
    return min(valor, techo)
}

/**
 * Estado puro de la recuperación de una sesión de reproducción.
 *
 * Con los fallos recibidos decide si corresponde reintentar, ignorar un
 * duplicado o rendirse. El presupuesto [ConfiguracionRecuperacion.
 * maximoIntentos] solo se repone con [alEstabilizar]: alcanzar `READY` no
 * basta, la sesión llama a este método después de un periodo de
 * reproducción estable con evidencia de renderizado (S4).
 */
class MaquinaRecuperacion(
    private val configuracion: ConfiguracionRecuperacion = ConfiguracionRecuperacion(),
) {
    private var intentos = 0
    private var pendiente = false

    /** Reintentos configurados para la sesión. */
    val maximo: Int
        get() = configuracion.maximoIntentos

    /** Reintentos ya enviados al motor desde la última estabilidad. */
    val realizados: Int
        get() = intentos

    /** Decide el siguiente paso ante un fallo del motor. */
    fun alFallar(clasificacion: ClaseFallo): DecisionReintento {
        if (clasificacion == ClaseFallo.DEFINITIVO) {
            pendiente = false
            return DecisionReintento.Definitivo
        }
        if (pendiente) return DecisionReintento.Ignorar
        if (intentos >= configuracion.maximoIntentos) {
            pendiente = false
            return DecisionReintento.Definitivo
        }
        pendiente = true
        return DecisionReintento.Reintentar(
            numero = intentos + 1,
            esperaMs = esperaRecuperacion(intentos + 1, configuracion),
        )
    }

    /** Confirma que el reintento pendiente ya se envió al motor. */
    fun alEjecutar() {
        if (!pendiente) return
        pendiente = false
        intentos += 1
    }

    /** Repone el presupuesto tras un periodo de reproducción estable. */
    fun alEstabilizar() {
        intentos = 0
    }
}

/**
 * Evidencia de reproducción estable para reponer el presupuesto (S4).
 *
 * Un stream con video exige el primer frame renderizado y los dos
 * renderizadores del contenido; un stream sin audio declarado basta con
 * el video; un stream sin video usa el audio como criterio aplicable sin
 * clasificarlo como fallo de imagen. El estado `READY` por sí solo no
 * alimenta esta decisión.
 */
fun hayEvidenciaEstable(
    videoActivo: Boolean,
    primerFrameVideoVisto: Boolean,
    hayGrupoVideo: Boolean,
    hayGrupoAudio: Boolean,
    audioActivo: Boolean,
): Boolean =
    when {
        !hayGrupoVideo -> audioActivo
        !hayGrupoAudio -> videoActivo && primerFrameVideoVisto
        else -> videoActivo && primerFrameVideoVisto && audioActivo
    }
