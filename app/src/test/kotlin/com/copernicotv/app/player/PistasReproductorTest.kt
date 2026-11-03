package com.copernicotv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * E6-T3: reglas puras de detección, coincidencia y aviso de pistas (D3, S44).
 */
class PistasReproductorTest {
    @Test
    fun `busca la coincidencia exacta antes que el idioma`() {
        val espanol = pista(indice = 0, idioma = "spa", etiqueta = "Español")
        val ingles = pista(indice = 1, idioma = "eng", etiqueta = "Inglés")

        val elegida = buscarPista(listOf(espanol, ingles), PreferenciaPista.Pista("eng", "Inglés"))

        assertEquals(ingles, elegida)
    }

    @Test
    fun `cae al idioma cuando la etiqueta del contenido cambia`() {
        val espanol = pista(indice = 0, idioma = "spa", etiqueta = "Castellano")
        val ingles = pista(indice = 1, idioma = "eng", etiqueta = "English")

        val elegida = buscarPista(listOf(espanol, ingles), PreferenciaPista.Pista("eng", "Inglés"))

        assertEquals(ingles, elegida)
    }

    @Test
    fun `no inventa una pista cuando el idioma no existe`() {
        val espanol = pista(indice = 0, idioma = "spa", etiqueta = "Español")

        val elegida = buscarPista(listOf(espanol), PreferenciaPista.Pista("eng", "Inglés"))

        assertNull(elegida)
        assertTrue(preferenciaNoDisponible(listOf(espanol), PreferenciaPista.Pista("eng", "Inglés")))
    }

    @Test
    fun `una pista no soportada no satisface la preferencia`() {
        val rota = pista(indice = 0, idioma = "eng", etiqueta = "Inglés", soportada = false)

        assertNull(buscarPista(listOf(rota), PreferenciaPista.Pista("eng", "Inglés")))
    }

    @Test
    fun `automatico y desactivada nunca generan aviso`() {
        val espanol = pista(indice = 0, idioma = "spa", etiqueta = "Español")

        assertFalse(preferenciaNoDisponible(listOf(espanol), PreferenciaPista.Automatica))
        assertFalse(preferenciaNoDisponible(listOf(espanol), PreferenciaPista.Desactivada))
        assertFalse(preferenciaNoDisponible(emptyList(), PreferenciaPista.Automatica))
    }

    @Test
    fun `etiqueta prefiere el titulo y cae al nombre del idioma`() {
        assertEquals("Español", etiquetaPista(pista(etiqueta = "Español", idioma = "spa")))
        assertEquals("Inglés", etiquetaPista(pista(etiqueta = null, idioma = "eng")))
        assertEquals("EUS", etiquetaPista(pista(etiqueta = null, idioma = "eus")))
        assertNull(etiquetaPista(pista(etiqueta = null, idioma = null)))
        assertNull(etiquetaPista(pista(etiqueta = " ", idioma = "und")))
    }

    @Test
    fun `serializa y reconstruye preferencias sin perder etiquetas`() {
        val preferencias =
            listOf(
                PreferenciaPista.Automatica,
                PreferenciaPista.Desactivada,
                PreferenciaPista.Pista("spa", "Español"),
                PreferenciaPista.Pista("eng", "Inglés (latino)"),
                PreferenciaPista.Pista("und", null),
            )

        preferencias.forEach { preferencia ->
            assertEquals(preferencia, decodificarPreferencia(codificarPreferencia(preferencia)))
        }
        assertEquals(
            PreferenciaPista.Automatica,
            decodificarPreferencia(codificarPreferencia(PreferenciaPista.Pista(null, null))),
        )
    }

    @Test
    fun `un valor invalido cae en automatico`() {
        assertEquals(PreferenciaPista.Automatica, decodificarPreferencia(null))
        assertEquals(PreferenciaPista.Automatica, decodificarPreferencia(""))
        assertEquals(PreferenciaPista.Automatica, decodificarPreferencia("desconocido"))
        assertEquals(PreferenciaPista.Automatica, decodificarPreferencia("pista:solo-idioma"))
        assertEquals(PreferenciaPista.Automatica, decodificarPreferencia("pista:%:%"))
        assertEquals(PreferenciaPista.Automatica, decodificarPreferencia("pista::"))
    }

    @Test
    fun `el estado expone la pista activa y el aviso agregado`() {
        val activa = pista(indice = 0, idioma = "spa", etiqueta = "Español", activa = true)
        val estado =
            EstadoPistas(
                audio = listOf(activa),
                preferenciaSubtitulos = PreferenciaPista.Pista("eng", "Inglés"),
                avisoSubtitulos = true,
                subtitulosDesactivados = true,
            )

        assertEquals(activa, estado.audioActivo)
        assertNull(estado.subtitulosActivos)
        assertTrue(estado.hayAviso)
    }

    private fun pista(
        indice: Int,
        idioma: String? = "spa",
        etiqueta: String? = null,
        activa: Boolean = false,
        soportada: Boolean = true,
    ) = PistaDisponible(
        grupo = 1,
        indice = indice,
        tipo = TipoPista.AUDIO,
        idioma = idioma,
        etiqueta = etiqueta,
        canales = 2,
        forzada = false,
        activa = activa,
        soportada = soportada,
    )
}
