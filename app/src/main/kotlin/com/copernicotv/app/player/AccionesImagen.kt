package com.copernicotv.app.player

/**
 * Acciones de imagen que la sesión no posee (E7-T10, S5).
 *
 * La sesión Media3 puede reponer la selección y repreparar el recurso con su
 * propio motor, pero la superficie vive en la composición y la reconstrucción
 * completa vive en el ViewModel. La escalera de imagen llama por aquí cuando
 * el nivel es externo; sin vista activa los niveles se ejecutan con la
 * variante nula y no hacen nada.
 */
interface AccionesImagen {
    /** Vuelve a conectar la superficie del motor en la vista de reproducción. */
    fun restaurarSuperficie()

    /** Reconstruye la sesión completa preservando la posición dada (último nivel). */
    fun reconstruirSesion(posicionMs: Long)
}

/** Variante sin acciones: los niveles externos de la escalera no hacen nada. */
object AccionesImagenNula : AccionesImagen {
    override fun restaurarSuperficie() = Unit

    override fun reconstruirSesion(posicionMs: Long) = Unit
}
