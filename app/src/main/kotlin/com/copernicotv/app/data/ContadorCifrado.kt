package com.copernicotv.app.data

import com.copernicotv.core.crypto.CifradoAes

/**
 * Acumulador del tiempo y del número de operaciones de cifrado AES (E7-T7
 * ronda 7, S50).
 *
 * Se recorre en el mismo hilo que prepara las entidades: mide cada operación
 * por separado y devuelve el total, para que el informe reporte el tiempo de
 * cifrado sin atribuirle nada de la inserción Room.
 *
 * Ronda 8 (S5): distingue el sobre escrito (`0x01` raíz / `0x02` clave de
 * datos) y mide el descifrado por separado del cifrado.
 */
internal class ContadorCifrado {
    private var nanos = 0L
    private var operaciones = 0
    private var nanosDescifrado = 0L
    private var operacionesDescifrado = 0
    private var sobre0x01 = 0
    private var sobre0x02 = 0

    /** Ejecuta [bloque] y acumula su duración como una operación cifrada. */
    fun <T> medir(
        version: Byte,
        bloque: () -> T,
    ): T {
        val inicio = System.nanoTime()
        try {
            return bloque()
        } finally {
            nanos += System.nanoTime() - inicio
            operaciones++
            if (version == CifradoAes.VERSION_DATOS) sobre0x02++ else sobre0x01++
        }
    }

    /** Ejecuta [bloque] y acumula su duración como una operación de descifrado. */
    fun <T> medirDescifrado(bloque: () -> T): T {
        val inicio = System.nanoTime()
        try {
            return bloque()
        } finally {
            nanosDescifrado += System.nanoTime() - inicio
            operacionesDescifrado++
        }
    }

    fun ms(): Long = nanos / NANOS_POR_MS

    fun total(): Int = operaciones

    /** Sobres `0x02` escritos (clave de datos de sesión). */
    fun sobre0x02(): Int = sobre0x02

    /** Sobres `0x01` escritos (raíz de Keystore; 0 en la importación normal). */
    fun sobre0x01(): Int = sobre0x01

    fun msDescifrado(): Long = nanosDescifrado / NANOS_POR_MS

    fun totalDescifrado(): Int = operacionesDescifrado

    private companion object {
        const val NANOS_POR_MS = 1_000_000L
    }
}
