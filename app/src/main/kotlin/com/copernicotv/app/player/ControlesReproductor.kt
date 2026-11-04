package com.copernicotv.app.player

/**
 * Formatea milisegundos como `mm:ss` o `h:mm:ss`.
 *
 * Un valor negativo representa una duración desconocida y se muestra como
 * `--:--`; el resto de valores se trunca a segundos.
 */
fun formatearTiempo(ms: Long): String {
    if (ms < 0L) return "--:--"
    val total = ms / 1000L
    val horas = total / 3600L
    val minutos = (total % 3600L) / 60L
    val segundos = total % 60L
    return if (horas > 0L) {
        "$horas:${minutos.dosDigitos()}:${segundos.dosDigitos()}"
    } else {
        "${minutos.dosDigitos()}:${segundos.dosDigitos()}"
    }
}

private fun Long.dosDigitos(): String = toString().padStart(2, '0')

/**
 * Limita una posición de búsqueda al rango conocido del contenido.
 *
 * Sin duración confirmada (`duracionMs <= 0`) no hay búsqueda válida: devolver
 * cero mantiene al motor en el inicio en lugar de saltar a un valor inventado.
 */
fun limitarBusqueda(
    posicionMs: Long,
    duracionMs: Long,
): Long = if (duracionMs <= 0L) 0L else posicionMs.coerceIn(0L, duracionMs)

/** Los controles solo se auto-ocultan mientras el contenido avanza. */
fun debeOcultarse(fase: EstadoReproductor): Boolean = fase == EstadoReproductor.REPRODUCIENDO

/** Salto de búsqueda de los botones y del D-pad (S17a). */
const val SALTO_BUSQUEDA_MS: Long = 10_000L

/** Tiempo sin interacción tras el cual se ocultan los controles (E6-T2 A). */
const val TIEMPO_AUTO_OCULTAR_MS: Long = 3_000L
