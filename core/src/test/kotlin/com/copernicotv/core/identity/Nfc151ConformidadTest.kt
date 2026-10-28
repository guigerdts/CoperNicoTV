package com.copernicotv.core.identity

import com.copernicotv.core.identity.nfc.TablaNfc
import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Conformidad de [Nfc151] contra la suite oficial de Unicode 15.1.0.
 *
 * Lee `tools/unicode/NormalizationTest.txt` vendorizado, verifica su SHA-256 y
 * comprueba las invariantes normativas de UAX #15 sobre **cada** fila de la
 * suite. No replica la tabla ni se apoya en `java.text.Normalizer`.
 *
 * Invariantes comprobadas (las que corresponden a las formas canónicas):
 *
 * ```
 * c2 == NFC(c1) == NFC(c2) == NFC(c3)
 * c4 == NFC(c4) == NFC(c5)
 * c3 == NFD(c1) == NFD(c2) == NFD(c3)
 * c5 == NFD(c4) == NFD(c5)
 * ```
 *
 * NFKC y NFKD no se implementan a propósito: el contrato de identidad de U0
 * prohíbe las descomposiciones de compatibilidad.
 */
class Nfc151ConformidadTest {
    @Test
    fun `declara la version normativa y las metricas de las tablas`() {
        assertEquals(VERSION_UNICODE_NORMATIVA, Nfc151.VERSION_UNICODE)
        assertEquals("15.1.0", Nfc151.VERSION_UNICODE)
        assertEquals(922, TablaNfc.ENTRADAS_CCC)
        assertEquals(2061, TablaNfc.ENTRADAS_DESCOMPOSICION)
        assertEquals(941, TablaNfc.ENTRADAS_COMPOSICION)
        assertEquals(1120, TablaNfc.EXCLUSIONES_COMPOSICION)
    }

    @Test
    fun `la fuente oficial coincide con el sha registrado`() {
        val archivo = fuenteOficial()
        assertEquals(SHA256_NORMALIZATION_TEST, huella(archivo))
        assertTrue(
            archivo.readText(Charsets.UTF_8).contains("NormalizationTest-15.1.0.txt"),
            "la fuente no declara la versión 15.1.0",
        )
    }

    @Test
    fun `cumple las invariantes NFC de la suite completa`() {
        var comprobadas = 0
        for (fila in filas()) {
            val contexto = "línea ${fila.linea}"
            assertEquals(fila.c2, Nfc151.normalizar(fila.c1), "NFC(c1) $contexto")
            assertEquals(fila.c2, Nfc151.normalizar(fila.c2), "NFC(c2) $contexto")
            assertEquals(fila.c2, Nfc151.normalizar(fila.c3), "NFC(c3) $contexto")
            assertEquals(fila.c4, Nfc151.normalizar(fila.c4), "NFC(c4) $contexto")
            assertEquals(fila.c4, Nfc151.normalizar(fila.c5), "NFC(c5) $contexto")
            comprobadas++
        }
        assertEquals(FILAS_ESPERADAS, comprobadas)
    }

    @Test
    fun `cumple las invariantes NFD de la suite completa`() {
        var comprobadas = 0
        for (fila in filas()) {
            val contexto = "línea ${fila.linea}"
            assertEquals(fila.c3, Nfc151.descomponer(fila.c1), "NFD(c1) $contexto")
            assertEquals(fila.c3, Nfc151.descomponer(fila.c2), "NFD(c2) $contexto")
            assertEquals(fila.c3, Nfc151.descomponer(fila.c3), "NFD(c3) $contexto")
            assertEquals(fila.c5, Nfc151.descomponer(fila.c4), "NFD(c4) $contexto")
            assertEquals(fila.c5, Nfc151.descomponer(fila.c5), "NFD(c5) $contexto")
            comprobadas++
        }
        assertEquals(FILAS_ESPERADAS, comprobadas)
    }

    @Test
    fun `es idempotente sobre todas las columnas de la suite`() {
        for (fila in filas()) {
            for ((columna, valor) in fila.columnas()) {
                val unaVez = Nfc151.normalizar(valor)
                assertEquals(unaVez, Nfc151.normalizar(unaVez), "NFC idempotente $columna línea ${fila.linea}")
                val descompuesta = Nfc151.descomponer(valor)
                assertEquals(
                    descompuesta,
                    Nfc151.descomponer(descompuesta),
                    "NFD idempotente $columna línea ${fila.linea}",
                )
            }
        }
    }

    @Test
    fun `nfc publico delega en la implementacion conforme`() {
        for (fila in filas()) {
            assertEquals(Nfc151.normalizar(fila.c1), nfc(fila.c1), "nfc(c1) línea ${fila.linea}")
        }
    }

    private fun filas(): List<Fila> =
        fuenteOficial()
            .readLines(Charsets.UTF_8)
            .mapIndexedNotNull { indice, linea ->
                val contenido = linea.substringBefore('#').trim()
                if (contenido.isEmpty() || contenido.startsWith('@')) {
                    null
                } else {
                    val campos = contenido.split(';')
                    check(campos.size >= 5) { "NormalizationTest.txt:${indice + 1}: columnas insuficientes" }
                    Fila(
                        linea = indice + 1,
                        c1 = decodificar(campos[0]),
                        c2 = decodificar(campos[1]),
                        c3 = decodificar(campos[2]),
                        c4 = decodificar(campos[3]),
                        c5 = decodificar(campos[4]),
                    )
                }
            }

    private fun decodificar(campo: String): String {
        val salida = StringBuilder()
        for (valor in campo.trim().split(' ')) {
            if (valor.isNotEmpty()) salida.appendCodePoint(valor.toInt(16))
        }
        return salida.toString()
    }

    private fun fuenteOficial(): File {
        val raiz =
            System.getProperty("repoRoot")
                ?: error("falta la propiedad de sistema repoRoot; la tarea test debe definirla")
        val archivo = File(raiz, "tools/unicode/NormalizationTest.txt")
        check(archivo.isFile) { "no existe la fuente oficial: ${archivo.absolutePath}" }
        return archivo
    }

    private fun huella(archivo: File): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(archivo.readBytes())
        return digest.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
    }

    private data class Fila(
        val linea: Int,
        val c1: String,
        val c2: String,
        val c3: String,
        val c4: String,
        val c5: String,
    ) {
        fun columnas(): List<Pair<String, String>> = listOf("c1" to c1, "c2" to c2, "c3" to c3, "c4" to c4, "c5" to c5)
    }

    private companion object {
        const val FILAS_ESPERADAS = 19074
        const val SHA256_NORMALIZATION_TEST = "871238e37e3be0696ec2bd0891119a041b052da1a84485eda05a5438724b223e"
    }
}
