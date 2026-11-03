package com.copernicotv.app.player

/** Ajuste del video dentro de la superficie (S18 `resizeMode`). */
enum class ModoAspecto {
    AJUSTAR,
    RELLENAR,
    ZOOM,
    ;

    /** Ciclo de la UI: ajustar, rellenar, zoom y de nuevo ajustar. */
    fun siguiente(): ModoAspecto =
        when (this) {
            AJUSTAR -> RELLENAR
            RELLENAR -> ZOOM
            ZOOM -> AJUSTAR
        }
}
