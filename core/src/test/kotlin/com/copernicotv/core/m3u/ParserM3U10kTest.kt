package com.copernicotv.core.m3u

import java.io.ByteArrayInputStream
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Prueba funcional de E2-T5 sobre el fixture determinista de DC-10.
 *
 * No mide tiempos: la medición vive en `MedicionP1Test` y en el job
 * `performance`. Aquí se demuestra que el parser y el importador procesan las
 * 10 000 entradas sin pérdidas, con el hash del fixture como ancla de
 * reproducibilidad.
 */
class ParserM3U10kTest {
    private val clave = SecretKeySpec(ByteArray(32) { (it * 7).toByte() }, "HmacSHA256")

    @Test
    fun `el fixture conserva su hash registrado`() {
        assertEquals(FixtureP1.SHA256, FixtureP1.sha256(FixtureP1.bytes()))
    }

    @Test
    fun `el parser procesa las 10 000 entradas sin diagnosticos de error`() {
        val resultado = parsear(FixtureP1.bytes())

        assertEquals(FixtureP1.ENTRADAS, resultado.entradas.size)
        assertTrue(
            resultado.diagnosticos.none { it.severidad == SeveridadM3U.ERROR },
            "diagnósticos de error inesperados: ${resultado.diagnosticos.map { it.codigo.codigo }}",
        )
        assertEquals("p1-00001", resultado.entradas.first().tvgId)
        assertEquals("p1-10000", resultado.entradas.last().tvgId)
        assertEquals("Grupo 100", resultado.entradas.last().groupTitle)
        assertEquals(
            FixtureP1.ENTRADAS,
            resultado.entradas.map { it.tvgId }.distinct().size,
        )
    }

    @Test
    fun `el importador agrupa las 10 000 entradas sin rechazos ni colisiones`() {
        val importado = ByteArrayInputStream(FixtureP1.bytes()).use { ImportadorM3U.importar(it, clave) }

        assertEquals(FixtureP1.ENTRADAS, importado.canales.size)
        assertTrue(importado.rechazadas.isEmpty(), "rechazos inesperados: ${importado.rechazadas.size}")
        assertTrue(importado.colisiones.isEmpty(), "colisiones inesperadas: ${importado.colisiones.size}")
        assertTrue(importado.errores.isEmpty(), "errores inesperados: ${importado.errores.map { it.codigo.codigo }}")
        assertTrue(importado.canales.all { it.urlStreamHmac.isNotEmpty() })
    }

    private fun parsear(bytes: ByteArray): ResultadoParserM3U = ByteArrayInputStream(bytes).use { ParserM3U.parsear(it) }
}
