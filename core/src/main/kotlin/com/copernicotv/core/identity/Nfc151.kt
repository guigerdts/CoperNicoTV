package com.copernicotv.core.identity

import com.copernicotv.core.identity.nfc.BusquedaTablaNfc
import com.copernicotv.core.identity.nfc.TablaComposicion
import com.copernicotv.core.identity.nfc.TablaDescomposicion
import com.copernicotv.core.identity.nfc.TablaNfc

/**
 * Normalización canónica conforme a Unicode 15.1.0 (UAX #15).
 *
 * La implementación no depende de la versión Unicode de la plataforma: usa
 * tablas generadas desde `UnicodeData.txt`, `DerivedNormalizationProps.txt` y
 * `CompositionExclusions.txt` 15.1.0, vendorizadas en `tools/unicode/`. Por eso
 * el resultado es idéntico en JDK 17 (Unicode 13.0) y en cualquier Android.
 *
 * Solo implementa las formas canónicas NFC y NFD. No implementa NFKC ni NFKD:
 * el contrato de identidad de U0 prohíbe las descomposiciones de compatibilidad.
 *
 * Algoritmo (UAX #15 §1.3 y §3.11):
 * 1. descomposición canónica recursiva, con Hangul algorítmico;
 * 2. ordenamiento canónico estable por CCC;
 * 3. composición canónica con bloqueo y exclusiones, con Hangul algorítmico.
 */
object Nfc151 {
    /** Versión Unicode de las tablas. */
    const val VERSION_UNICODE: String = TablaNfc.VERSION

    private const val S_BASE = 0xAC00
    private const val L_BASE = 0x1100
    private const val V_BASE = 0x1161
    private const val T_BASE = 0x11A7
    private const val L_COUNT = 19
    private const val V_COUNT = 21
    private const val T_COUNT = 28
    private const val N_COUNT = V_COUNT * T_COUNT
    private const val S_COUNT = L_COUNT * N_COUNT

    /** Clase de un no-starter inicial: mayor que cualquier CCC real (0..240). */
    private const val CLASE_INICIAL_NO_STARTER = 256

    /** NFC: descompone, ordena y vuelve a componer. */
    fun normalizar(valor: String): String {
        if (valor.isEmpty()) return valor
        val codigos = descomponerCodigos(valor)
        ordenarCanonicamente(codigos)
        componer(codigos)
        return construir(codigos)
    }

    /**
     * NFD: descompone y ordena sin recomponer.
     *
     * Se expone para verificar la mitad de descomposición contra la suite
     * oficial; el contrato de identidad solo persiste NFC.
     */
    fun descomponer(valor: String): String {
        if (valor.isEmpty()) return valor
        val codigos = descomponerCodigos(valor)
        ordenarCanonicamente(codigos)
        return construir(codigos)
    }

    private fun descomponerCodigos(valor: String): MutableList<Int> {
        val salida = ArrayList<Int>(valor.length)
        var indice = 0
        while (indice < valor.length) {
            val punto = valor.codePointAt(indice)
            descomponerPunto(punto, salida)
            indice += Character.charCount(punto)
        }
        return salida
    }

    private fun descomponerPunto(
        punto: Int,
        salida: MutableList<Int>,
    ) {
        if (punto in S_BASE until S_BASE + S_COUNT) {
            val indice = punto - S_BASE
            salida.add(L_BASE + indice / N_COUNT)
            salida.add(V_BASE + (indice % N_COUNT) / T_COUNT)
            val final = T_BASE + indice % T_COUNT
            if (final != T_BASE) salida.add(final)
            return
        }
        val posicion = BusquedaTablaNfc.indiceDeClave(TablaDescomposicion.entradas, punto, 3)
        if (posicion < 0) {
            salida.add(punto)
            return
        }
        val base = posicion * 3
        descomponerPunto(TablaDescomposicion.entradas[base + 1], salida)
        val segundo = TablaDescomposicion.entradas[base + 2]
        if (segundo != 0) descomponerPunto(segundo, salida)
    }

    /**
     * Ordenamiento canónico estable: cada no-starter retrocede mientras la CCC
     * anterior sea mayor y distinta de 0. Las CCC iguales conservan su orden.
     */
    private fun ordenarCanonicamente(codigos: MutableList<Int>) {
        var indice = 1
        while (indice < codigos.size) {
            val clase = BusquedaTablaNfc.ccc(codigos[indice])
            if (clase != 0) reubicarMarca(codigos, indice, clase)
            indice++
        }
    }

    /**
     * Retrocede una marca hasta su posición canónica. Solo intercambia con
     * marcas anteriores de CCC mayor; los starters y las CCC iguales la frenan,
     * así el orden relativo de marcas equivalentes se conserva.
     */
    private fun reubicarMarca(
        codigos: MutableList<Int>,
        indice: Int,
        clase: Int,
    ) {
        var posicion = indice
        while (posicion > 0) {
            val anterior = BusquedaTablaNfc.ccc(codigos[posicion - 1])
            if (anterior == 0 || anterior <= clase) break
            val temporal = codigos[posicion - 1]
            codigos[posicion - 1] = codigos[posicion]
            codigos[posicion] = temporal
            posicion--
        }
    }

    /**
     * Composición canónica con bloqueo (UAX #15, algoritmo canónico de
     * composición). [codigos] queda con la forma compuesta y sin la cola sobrante.
     */
    private fun componer(codigos: MutableList<Int>) {
        if (codigos.isEmpty()) return
        var posicionStarter = 0
        var starter = codigos[0]
        var posicionSalida = 1
        var ultimaClase = BusquedaTablaNfc.ccc(starter)
        if (ultimaClase != 0) ultimaClase = CLASE_INICIAL_NO_STARTER
        var indice = 1
        while (indice < codigos.size) {
            val actual = codigos[indice]
            val clase = BusquedaTablaNfc.ccc(actual)
            val compuesto = componerPunto(starter, actual)
            val puedeComponer = compuesto >= 0 && (ultimaClase < clase || ultimaClase == 0)
            if (puedeComponer) {
                codigos[posicionStarter] = compuesto
                starter = compuesto
            } else {
                if (clase == 0) {
                    posicionStarter = posicionSalida
                    starter = actual
                }
                ultimaClase = clase
                codigos[posicionSalida] = actual
                posicionSalida++
            }
            indice++
        }
        while (codigos.size > posicionSalida) codigos.removeAt(codigos.size - 1)
    }

    private fun componerPunto(
        primero: Int,
        segundo: Int,
    ): Int {
        if (primero in L_BASE until L_BASE + L_COUNT && segundo in V_BASE until V_BASE + V_COUNT) {
            return S_BASE + ((primero - L_BASE) * V_COUNT + (segundo - V_BASE)) * T_COUNT
        }
        val esLv = primero in S_BASE until S_BASE + S_COUNT && (primero - S_BASE) % T_COUNT == 0
        if (esLv && segundo in T_BASE + 1 until T_BASE + T_COUNT) {
            return primero + (segundo - T_BASE)
        }
        val posicion = BusquedaTablaNfc.indiceDeComposicion(TablaComposicion.entradas, primero, segundo)
        return if (posicion < 0) -1 else TablaComposicion.entradas[posicion * 3 + 2]
    }

    private fun construir(codigos: List<Int>): String {
        val salida = StringBuilder(codigos.size)
        for (punto in codigos) salida.appendCodePoint(punto)
        return salida.toString()
    }
}
