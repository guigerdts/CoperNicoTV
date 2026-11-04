package com.copernicotv.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Casos dirigidos de [Nfc151]: Hangul, exclusiones, marcas combinatorias,
 * ordenamiento canónico, bloqueo, plano suplementario, ausencia de NFKC y la
 * regresión de identidad H-02.
 *
 * La conformidad completa la cubre [Nfc151ConformidadTest] con las 19074 filas
 * de `NormalizationTest.txt`; estos casos documentan comportamientos concretos
 * que una tabla truncada o un algoritmo incompleto romperían.
 */
class Nfc151DirigidosTest {
    @Test
    fun `regresion H-02 sobre la marca arabica U+10EFD`() {
        // NFC de 0061 0301 10EFD: el orden canónico deja 10EFD (ccc 220) antes
        // de 0301 (ccc 230) y la composición a+0301 no queda bloqueada, así que
        // el resultado es 00E1 10EFD. NFD sí conserva el orden canónico.
        assertEquals("\u00E1\uD803\uDEFD", nfc("\u0061\u0301\uD803\uDEFD"))
        assertEquals("\u0061\uD803\uDEFD\u0301", Nfc151.descomponer("\u0061\u0301\uD803\uDEFD"))
        assertEquals(
            "\u00E1\uD803\uDEFD",
            PerfilNormalizacion.normalizar("\u0061\u0301\uD803\uDEFD", CaseFolderCompleto),
        )
    }

    @Test
    fun `la marca nueva altera el orden y la composicion respecto de Unicode 13_0`() {
        // En Unicode 13.0 U+10EFD no está asignado (ccc 0) y actúa como starter:
        // 0061 10EFD 0301 queda igual. En 15.1 no bloquea la composición de 0301.
        assertEquals("\u00E1\uD803\uDEFD", nfc("\u0061\uD803\uDEFD\u0301"))
        assertEquals("\u0061\uD803\uDEFD\u0301", Nfc151.descomponer("\u0061\uD803\uDEFD\u0301"))
    }

    @Test
    fun `compone y descompone Hangul de forma algoritmica`() {
        assertEquals("\uAC00", nfc("\u1100\u1161"))
        assertEquals("\uAC01", nfc("\uAC00\u11A8"))
        assertEquals("\uAC01", nfc("\u1100\u1161\u11A8"))
        assertEquals("\uAC01", nfc("\uAC01"))
        assertEquals("\uD7A3", nfc("\uD7A3"))
        assertEquals("\u1100\u1161\u11A8", Nfc151.descomponer("\uAC01"))
        assertEquals("\u1112\u1175\u11C2", Nfc151.descomponer("\uD7A3"))
        assertEquals("\uAC00\u0301", nfc("\uAC00\u0301"))
    }

    @Test
    fun `respeta las exclusiones de composicion`() {
        // 0958 tiene descomposición canónica pero está excluida de componer.
        assertEquals("\u0915\u093C", nfc("\u0958"))
        assertEquals("\u0915\u093C", nfc("\u0915\u093C"))
        // Descomposiciones no-starter: nunca recomponen.
        assertEquals("\u0308\u0301", nfc("\u0344"))
        assertEquals("\u0F71\u0F72", nfc("\u0F73"))
        assertEquals("\u0F71\u0F74", nfc("\u0F75"))
        assertEquals("\u0F71\u0F80", nfc("\u0F81"))
        // Símbolos musicales excluidos: se descomponen y no recomponen.
        assertEquals("\uD834\uDD57\uD834\uDD65", nfc("\uD834\uDD5E"))
        assertEquals("\uD834\uDD57\uD834\uDD65", nfc("\uD834\uDD57\uD834\uDD65"))
    }

    @Test
    fun `aplica descomposiciones singleton`() {
        assertEquals("\u00C5", nfc("\u212B"))
        assertEquals("\u03A9", nfc("\u2126"))
        assertEquals("\u0041\u030A", Nfc151.descomponer("\u212B"))
    }

    @Test
    fun `ordena marcas por CCC de forma estable`() {
        // 0315 (ccc 232) retrocede detrás de 0300 (ccc 230).
        assertEquals("\u0061\u0300\u0315", Nfc151.descomponer("\u0061\u0315\u0300"))
        // CCC iguales conservan el orden de entrada.
        assertEquals("\u0061\u0300\u0301", Nfc151.descomponer("\u0061\u0300\u0301"))
        assertEquals("\u0061\u0301\u0300", Nfc151.descomponer("\u0061\u0301\u0300"))
    }

    @Test
    fun `bloquea la composicion cuando una marca igual o mayor interviene`() {
        assertEquals("\u00E4\u0301", nfc("\u0061\u0308\u0301"))
    }

    @Test
    fun `compone en el plano suplementario y con segundos de CCC cero`() {
        assertEquals("\uD804\uDC9A", nfc("\uD804\uDC99\uD804\uDCBA"))
        assertEquals("\uD804\uDC99\uD804\uDCBA", Nfc151.descomponer("\uD804\uDC9A"))
        assertEquals("\u09CB", nfc("\u09C7\u09BE"))
        assertEquals("\u09C7\u09BE", Nfc151.descomponer("\u09CB"))
    }

    @Test
    fun `no aplica descomposiciones de compatibilidad`() {
        assertEquals("\uFB01", nfc("\uFB01"))
        assertEquals("\u00BD", nfc("\u00BD"))
        assertEquals("\u2460", nfc("\u2460"))
    }

    @Test
    fun `es idempotente sobre los casos dirigidos`() {
        val casos =
            listOf(
                "\u0061\u0301\uD803\uDEFD",
                "\uAC01",
                "\uD7A3",
                "\u0958",
                "\u0344",
                "\uD834\uDD5E",
                "\uD804\uDC9A",
                "\uFB01",
            )
        for (caso in casos) {
            val unaVez = nfc(caso)
            assertEquals(unaVez, nfc(unaVez), "NFC idempotente en $caso")
            val descompuesta = Nfc151.descomponer(caso)
            assertEquals(descompuesta, Nfc151.descomponer(descompuesta), "NFD idempotente en $caso")
        }
    }

    @Test
    fun `deja intactas las cadenas vacias y ASCII`() {
        assertEquals("", nfc(""))
        assertEquals("canario-1 123", nfc("canario-1 123"))
    }
}
