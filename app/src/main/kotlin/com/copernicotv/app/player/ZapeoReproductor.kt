package com.copernicotv.app.player

/**
 * Paso de volumen de los botones de la barra, en la escala 0..1 de Media3.
 */
const val PASO_VOLUMEN = 0.1f

/**
 * Devuelve el Canal vecino de [actual] en el universo ordenado de zapeo
 * (E6-T6).
 *
 * El universo es la lista de identificadores en el orden del Catálogo visible.
 * En los límites de navegación, con un catálogo vacío o con un Canal ausente
 * del universo devuelve `null`: no hay destino y el zapeo no debe inventar uno
 * ni envolver la navegación.
 */
fun canalVecino(
    canales: List<Long>,
    actual: Long,
    direccion: DireccionZapeo,
): Long? {
    val indice = canales.indexOf(actual)
    if (indice < 0) return null
    return when (direccion) {
        DireccionZapeo.SIGUIENTE -> canales.getOrNull(indice + 1)
        DireccionZapeo.ANTERIOR -> canales.getOrNull(indice - 1)
    }
}

/**
 * Acota el volumen a la escala válida de Media3 (0..1) y lo redondea a un
 * decimal para que los pasos consecutivos no arrastren el punto flotante.
 *
 * Un valor inválido (`NaN`) no puede inventar un nivel audible: cae a silencio.
 */
fun limitarVolumen(volumen: Float): Float {
    if (volumen.isNaN()) return 0f
    val acotado = volumen.coerceIn(0f, 1f)
    return Math.round(acotado * 10f) / 10f
}
