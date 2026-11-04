package com.copernicotv.app.diagnostico

/**
 * Detalle técnico de un fallo, apto para logs (S57a).
 *
 * Registra la cadena de tipos y los fotogramas de la pila, pero nunca los
 * mensajes de las excepciones: un mensaje puede arrastrar la URL original de la
 * Lista, credenciales o contenido de la fuente. El tipo y el punto de origen
 * bastan para distinguir un fallo de Keystore, de Room o del parser sin
 * filtrar datos.
 */
internal fun detalleTecnico(
    fallo: Throwable,
    maximoFotogramas: Int = MAXIMO_FOTOGRAMAS,
): String {
    val tipos = cadenaDeTipos(fallo)
    val fotogramas =
        fallo.stackTrace
            .take(maximoFotogramas)
            .joinToString(separator = "\n") { "    at $it" }
    return if (fotogramas.isEmpty()) tipos else "$tipos\n$fotogramas"
}

/**
 * Nombres de los tipos desde [fallo] hasta su causa raíz.
 *
 * El límite corta las causas circulares o encadenadas sin fin, que de otro modo
 * colgarían el registro del fallo.
 */
private fun cadenaDeTipos(fallo: Throwable): String {
    val tipos = mutableListOf<String>()
    var actual: Throwable? = fallo
    while (actual != null && tipos.size < LIMITE_CAUSAS) {
        tipos += actual::class.java.name
        actual = actual.cause
    }
    return tipos.joinToString(separator = " <- ")
}

private const val MAXIMO_FOTOGRAMAS = 12
private const val LIMITE_CAUSAS = 5
