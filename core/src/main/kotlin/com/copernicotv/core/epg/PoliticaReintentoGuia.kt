package com.copernicotv.core.epg

import java.time.Duration
import java.time.Instant

/**
 * Política de reintento controlado de una guía EPG (S7).
 *
 * Una fuente caída no se reintenta en cada apertura ni en cada arranque: las
 * esperas crecen y, agotado el tope de intentos automáticos, el reintento pasa
 * a ser manual. Así un proveedor caído no recibe una petición por minuto y el
 * usuario conserva la última guía válida sin que la app lo martille.
 *
 * El número de intentos automáticos es el tamaño de [esperasMinutos]: con el
 * valor por defecto son cuatro intentos (15 min, 1 h, 6 h y 24 h) y después el
 * estado queda a la espera de una acción explícita del usuario.
 */
data class PoliticaReintentoGuia(
    val esperasMinutos: List<Long> = ESPERAS_PREDETERMINADAS,
) {
    init {
        require(esperasMinutos.isNotEmpty()) { "La política necesita al menos una espera" }
        require(esperasMinutos.all { it > 0 }) { "Las esperas deben ser positivas" }
    }

    /** Intentos automáticos permitidos antes de exigir reintento manual. */
    val maxIntentos: Int get() = esperasMinutos.size

    /**
     * ¿Corresponde reintentar ahora?
     *
     * - Sin fallos previos: sí.
     * - Sin fecha de último intento: sí, no hay espera que respetar.
     * - Intentos agotados: no, el reintento es manual.
     * - En otro caso: solo cuando ya pasó la espera del intento consumido.
     */
    fun puedeReintentar(
        intentosFallidos: Long,
        ultimoIntento: Instant?,
        ahora: Instant,
    ): Boolean {
        if (intentosFallidos <= 0L) return true
        if (intentosFallidos >= maxIntentos) return false
        val ultimo = ultimoIntento ?: return true
        val espera = esperasMinutos[(intentosFallidos - 1).toInt()]
        return !ahora.isBefore(ultimo.plus(Duration.ofMinutes(espera)))
    }

    /** Espera aplicable al próximo reintento; `null` si ya no hay automático. */
    fun esperaTras(intentosFallidos: Long): Duration? = esperasMinutos.getOrNull(intentosFallidos.toInt())?.let(Duration::ofMinutes)

    companion object {
        /** 15 min, 1 h, 6 h y 24 h. */
        val ESPERAS_PREDETERMINADAS: List<Long> = listOf(15L, 60L, 360L, 1_440L)

        /** Política de producción. */
        val PREDETERMINADA: PoliticaReintentoGuia = PoliticaReintentoGuia()
    }
}
