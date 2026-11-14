package com.copernicotv.app.data

/**
 * Acumulador del tiempo y del número de operaciones de cifrado AES (E7-T7
 * ronda 7, S50).
 *
 * Se recorre en el mismo hilo que prepara las entidades: mide cada
 * `CifradoAes.cifrar` por separado y devuelve el total, para que el informe
 * reporte el tiempo de cifrado sin atribuirle nada de la inserción Room.
 */
internal class ContadorCifrado {
    private var nanos = 0L
    private var operaciones = 0

    /** Ejecuta [bloque] y acumula su duración como una operación cifrada. */
    fun <T> medir(bloque: () -> T): T {
        val inicio = System.nanoTime()
        try {
            return bloque()
        } finally {
            nanos += System.nanoTime() - inicio
            operaciones++
        }
    }

    fun ms(): Long = nanos / NANOS_POR_MS

    fun total(): Int = operaciones

    private companion object {
        const val NANOS_POR_MS = 1_000_000L
    }
}
