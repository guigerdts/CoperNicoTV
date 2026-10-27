package com.copernicotv.core.identity

import java.io.File
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotEquals
import kotlin.test.assertTrue

/**
 * Conformidad del case folding completo contra los datos oficiales 15.1.0.
 *
 * La prueba no replica la tabla: lee `tools/unicode/CaseFolding.txt`, verifica
 * su SHA-256 y comprueba **cada** mapeo C y F contra la implementación. Así una
 * tabla editada a mano, truncada o generada desde otra versión falla.
 */
class CaseFolderCompletoTest {
    @Test
    fun `declara la version normativa y el tamano de la tabla`() {
        assertEquals(VERSION_UNICODE_NORMATIVA, CaseFolderCompleto.VERSION_UNICODE)
        assertEquals("15.1.0", CaseFolderCompleto.VERSION_UNICODE)
        assertEquals(1530, CaseFolderCompleto.ENTRADAS)
    }

    @Test
    fun `la fuente oficial coincide con el sha registrado`() {
        val archivo = fuenteOficial()
        val huella = MessageDigest.getInstance("SHA-256").digest(archivo.readBytes())
        val hexadecimal = huella.joinToString("") { "%02x".format(it.toInt() and 0xFF) }
        assertEquals(CaseFolderCompleto.SHA256_FUENTE, hexadecimal)
        assertTrue(
            archivo.readText(Charsets.UTF_8).contains("CaseFolding-15.1.0.txt"),
            "la fuente no declara la versión 15.1.0",
        )
    }

    @Test
    fun `aplica exactamente las 1530 entradas C y F`() {
        val aplicadas = entradas().filter { it.estado == "C" || it.estado == "F" }
        assertEquals(CaseFolderCompleto.ENTRADAS, aplicadas.size)
        for (entrada in aplicadas) {
            assertEquals(
                entrada.mapeo,
                CaseFolderCompleto.fold(desdePunto(entrada.punto)),
                "U+%04X".format(entrada.punto),
            )
        }
    }

    @Test
    fun `no aplica los mapeos simples ni los turcos`() {
        val aplicadas = entradas().filter { it.estado == "C" || it.estado == "F" }.associateBy { it.punto }
        for (entrada in entradas().filter { it.estado == "S" }) {
            if (aplicadas.containsKey(entrada.punto)) {
                assertNotEquals(
                    entrada.mapeo,
                    CaseFolderCompleto.fold(desdePunto(entrada.punto)),
                    "S no debe ganarle a F en U+%04X".format(entrada.punto),
                )
            }
        }
        for (entrada in entradas().filter { it.estado == "T" }) {
            assertNotEquals(
                entrada.mapeo,
                CaseFolderCompleto.fold(desdePunto(entrada.punto)),
                "T es dependiente de locale y no debe aplicarse en U+%04X".format(entrada.punto),
            )
        }
    }

    @Test
    fun `casos limite documentados`() {
        val casos =
            mapOf(
                "Straße" to "strasse",
                "ẞ" to "ss",
                "İ" to "i\u0307",
                "I" to "i",
                "ı" to "ı",
                "Σ" to "σ",
                "ς" to "σ",
                "ΣΣ" to "σσ",
                "ﬁ" to "fi",
                "ﬅ" to "st",
                "ŉ" to "ʼn",
                "ǅ" to "ǆ",
                "ᾼ" to "αι",
                "\u0390" to "\u03B9\u0308\u0301",
                "\uD801\uDC00" to "\uD801\uDC28",
                "\uAB70" to "\u13A0",
                "\uD83A\uDD00" to "\uD83A\uDD22",
                "canario-1 123" to "canario-1 123",
                "\uD83D\uDCFA" to "\uD83D\uDCFA",
            )
        for ((entrada, esperado) in casos) {
            assertEquals(esperado, CaseFolderCompleto.fold(entrada), "fold(%s)".format(entrada))
        }
    }

    @Test
    fun `es idempotente sobre todas las claves`() {
        for (entrada in entradas().filter { it.estado == "C" || it.estado == "F" }) {
            val unaVez = CaseFolderCompleto.fold(desdePunto(entrada.punto))
            assertEquals(unaVez, CaseFolderCompleto.fold(unaVez), "U+%04X".format(entrada.punto))
        }
    }

    @Test
    fun `colapsa la diferencia entre ss y eszett al normalizar un perfil`() {
        val aleman = PerfilNormalizacion.normalizar("  Straße  ", CaseFolderCompleto)
        val suizo = PerfilNormalizacion.normalizar("  STRASSE  ", CaseFolderCompleto)
        assertEquals("strasse", aleman)
        assertEquals(aleman, suizo)
    }

    @Test
    fun `normaliza el nombre con la tabla conforme`() {
        assertEquals(
            "i\u0307stanbul",
            PerfilNormalizacion.normalizar("  İSTANBUL  ", CaseFolderCompleto),
        )
        assertEquals(
            "trabajo",
            PerfilNormalizacion.normalizar("  Trabajo  ", CaseFolderCompleto),
        )
    }

    private fun desdePunto(punto: Int): String = String(Character.toChars(punto))

    private fun fuenteOficial(): File {
        val raiz =
            System.getProperty("repoRoot")
                ?: error("falta la propiedad de sistema repoRoot; la tarea test debe definirla")
        val archivo = File(raiz, "tools/unicode/CaseFolding.txt")
        check(archivo.isFile) { "no existe la fuente oficial: ${archivo.absolutePath}" }
        return archivo
    }

    private fun entradas(): List<Entrada> =
        fuenteOficial()
            .readLines(Charsets.UTF_8)
            .map { it.substringBefore('#').trim() }
            .filter { it.isNotEmpty() }
            .map { linea ->
                val campos = linea.split(';').map { it.trim() }
                Entrada(
                    punto = campos[0].toInt(16),
                    estado = campos[1],
                    mapeo = campos[2].split(' ').filter { it.isNotEmpty() }.joinToString("") { desdePunto(it.toInt(16)) },
                )
            }

    private data class Entrada(
        val punto: Int,
        val estado: String,
        val mapeo: String,
    )
}
