package com.copernicotv.core.tema

/** Tema visual del canario (S43: temas claro y oscuro). */
enum class TemaUi(val codigo: String) {
    CLARO("claro"),
    OSCURO("oscuro"),
    ;

    /** El tema por defecto es el oscuro, el comportamiento previo a E7-T4. */
    companion object {
        val POR_DEFECTO: TemaUi = OSCURO

        /** Parsea un código persistido; el valor ausente o desconocido cae al oscuro. */
        fun desde(valor: String?): TemaUi = entries.firstOrNull { it.codigo == valor } ?: POR_DEFECTO
    }

    /** Invierte el tema sin estado intermedio. */
    fun alternar(): TemaUi = if (this == CLARO) OSCURO else CLARO
}
