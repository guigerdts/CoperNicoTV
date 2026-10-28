package com.copernicotv.app.diagnostico

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * El detalle técnico debe bastar para diagnosticar sin filtrar datos.
 *
 * Los mensajes de las excepciones quedan fuera a propósito: pueden contener la
 * URL de la Lista o credenciales (S57a).
 */
class DetalleTecnicoTest {
    @Test
    fun `incluye el tipo del fallo y el de su causa`() {
        val fallo = IllegalStateException("envoltorio", IllegalArgumentException("causa"))

        val detalle = detalleTecnico(fallo)

        assertTrue(detalle, detalle.contains(IllegalStateException::class.java.name))
        assertTrue(detalle, detalle.contains(IllegalArgumentException::class.java.name))
    }

    @Test
    fun `incluye fotogramas de la pila`() {
        val detalle = detalleTecnico(falloConPila())

        assertTrue(detalle, detalle.contains("at com.copernicotv.app.diagnostico.DetalleTecnicoTest"))
    }

    @Test
    fun `no incluye los mensajes de las excepciones`() {
        val secreto = "https://usuario:clave@example.invalid/lista.m3u"
        val fallo = IllegalStateException(secreto, IllegalArgumentException(secreto))

        val detalle = detalleTecnico(fallo)

        assertFalse(detalle, detalle.contains("usuario"))
        assertFalse(detalle, detalle.contains("example.invalid"))
    }

    @Test
    fun `respeta el maximo de fotogramas`() {
        val detalle = detalleTecnico(falloConPila(), maximoFotogramas = 1)

        assertTrue(detalle, detalle.lines().count { it.trimStart().startsWith("at ") } == 1)
    }

    @Test
    fun `no se cuelga con una causa circular`() {
        val primero = IllegalStateException("primero")
        val segundo = IllegalArgumentException("segundo")
        primero.initCause(segundo)
        segundo.initCause(primero)

        val detalle = detalleTecnico(primero)

        assertTrue(detalle, detalle.contains(IllegalStateException::class.java.name))
        assertTrue(detalle, detalle.contains(IllegalArgumentException::class.java.name))
    }

    private fun falloConPila(): Throwable = IllegalStateException("con pila")
}
