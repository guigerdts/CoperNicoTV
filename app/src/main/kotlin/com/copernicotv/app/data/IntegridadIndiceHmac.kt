package com.copernicotv.app.data

import com.copernicotv.core.hmac.EstadoIndiceHmac

/**
 * Resultado de conciliar la fila del índice, sus sobres y la clave HMAC
 * disponible (DC-04 §7, E2-T6).
 *
 * Es la lectura con efectos que verifica la integridad antes de permitir sellar
 * o comparar. [Operativo] e [Inicializado] garantizan que la clave de [estado]
 * está en la bóveda; [Perdida] y [Recuperacion] garantizan lo contrario.
 */
sealed interface IntegridadIndiceHmac {
    /** Estado persistido que respalda el resultado. */
    val estado: EstadoIndiceHmac

    /** Índice operativo y clave de la generación activa presente. */
    data class Operativo(
        override val estado: EstadoIndiceHmac,
    ) : IntegridadIndiceHmac

    /** Fila y clave materializadas ahora; no había sobres que quedaran inverificables. */
    data class Inicializado(
        override val estado: EstadoIndiceHmac,
    ) : IntegridadIndiceHmac

    /** Clave perdida con sobres persistidos: se declaró `recuperacion_pendiente`. */
    data class Perdida(
        override val estado: EstadoIndiceHmac,
    ) : IntegridadIndiceHmac

    /** Índice ya en recuperación o bloqueado; la conciliación no lo toca. */
    data class Recuperacion(
        override val estado: EstadoIndiceHmac,
    ) : IntegridadIndiceHmac
}
