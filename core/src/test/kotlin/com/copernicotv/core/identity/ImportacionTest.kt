package com.copernicotv.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImportacionTest {
    private fun declaracion(
        nombre: String? = "Canal",
        url: String = "http://example.com/canal",
        tvgId: String? = null,
        grupo: String? = null,
        tvgName: String? = null,
        logo: String? = null,
    ) = DeclaracionCanal(nombre, url, tvgId, grupo, tvgName, logo)

    @Test
    fun `el orden de la lista no cambia las claves`() {
        val declaraciones =
            listOf(
                declaracion(nombre = "Canal A", url = "http://example.com/a"),
                declaracion(nombre = "Canal B", url = "http://example.com/b"),
                declaracion(nombre = "Canal C", url = "http://example.com/c"),
            )
        val directo = AgrupadorImportacion.agrupar(declaraciones)
        val inverso = AgrupadorImportacion.agrupar(declaraciones.reversed())
        assertEquals(
            directo.canales.map { it.claveUnicidad }.toSet(),
            inverso.canales.map { it.claveUnicidad }.toSet(),
        )
        assertEquals(
            directo.canales.flatMap { canal -> canal.apariencias.map { it.clavePresentacion } }.toSet(),
            inverso.canales.flatMap { canal -> canal.apariencias.map { it.clavePresentacion } }.toSet(),
        )
    }

    @Test
    fun `declaraciones identicas se agrupan una vez y la repeticion es informativa`() {
        val resultado =
            AgrupadorImportacion.agrupar(
                listOf(
                    declaracion(nombre = "Canal", url = "HTTP://Example.com/canal"),
                    declaracion(nombre = " Canal ", url = "http://example.com/canal"),
                ),
            )
        assertEquals(1, resultado.canales.size)
        assertEquals(1, resultado.repeticionesInformativas)
        assertEquals(2, resultado.canales.single().declaraciones)
        assertEquals(0, resultado.rechazadas.size)
    }

    @Test
    fun `misma identidad con presentacion distinta produce dos apariencias`() {
        val resultado =
            AgrupadorImportacion.agrupar(
                listOf(
                    declaracion(nombre = "Canal", grupo = "Deportes", tvgName = "Canal"),
                    declaracion(nombre = "Canal", grupo = "Noticias", tvgName = "Canal"),
                ),
            )
        val canal = resultado.canales.single()
        assertEquals(2, canal.apariencias.size)
        assertTrue(canal.claveUnicidad?.startsWith("canon:v2:") == true)
        assertEquals(
            setOf("Deportes", "Noticias"),
            canal.apariencias.mapNotNull { it.groupTitle }.toSet(),
        )
        assertEquals(2, canal.declaraciones)
    }

    @Test
    fun `tvg id duplicado no se usa como clave y no fusiona canales distintos`() {
        val resultado =
            AgrupadorImportacion.agrupar(
                listOf(
                    declaracion(nombre = "Canal A", url = "http://example.com/a", tvgId = "duplicado"),
                    declaracion(nombre = "Canal B", url = "http://example.com/b", tvgId = "duplicado"),
                ),
            )
        assertEquals(2, resultado.canales.size)
        assertTrue(resultado.canales.all { it.tvgId == "duplicado" })
        assertTrue(resultado.canales.all { it.claveUnicidad?.startsWith("canon:v2:") == true })
        assertTrue(resultado.colisiones.isEmpty())
    }

    @Test
    fun `colision de clave canonica no se fusiona ni se resuelve por posicion`() {
        val resultado =
            AgrupadorImportacion.agrupar(
                listOf(
                    declaracion(nombre = "Canal", url = "http://example.com/canal", tvgId = "canal.es"),
                    declaracion(nombre = "Canal", url = "http://example.com/canal"),
                ),
            )
        assertEquals(2, resultado.canales.size)
        assertEquals(1, resultado.colisiones.size)
        assertEquals(2, resultado.colisiones.single().canalesDistintos)
        val claves = resultado.canales.mapNotNull { it.claveUnicidad }.toSet()
        assertEquals(2, claves.size)
    }

    @Test
    fun `declaracion sin nombre ni tvg no es derivable`() {
        val resultado = AgrupadorImportacion.agrupar(listOf(declaracion(nombre = null)))
        val canal = resultado.canales.single()
        assertNull(canal.claveCanonica)
        assertNull(canal.claveUnicidad)
        assertFalse(canal.claveDerivable)
    }

    @Test
    fun `url invalida se rechaza con ordinal y sin datos sensibles`() {
        val resultado =
            AgrupadorImportacion.agrupar(
                listOf(
                    declaracion(nombre = "Uno", url = "http://example.com/uno"),
                    declaracion(nombre = "Dos", url = "http://usuario:secreto@example.com/dos con espacio"),
                ),
            )
        assertEquals(1, resultado.canales.size)
        assertEquals(1, resultado.rechazadas.size)
        val rechazo = resultado.rechazadas.single()
        assertEquals(1, rechazo.ordinal)
        assertFalse(rechazo.error.toString().contains("secreto"))
        assertFalse(rechazo.error.toString().contains("usuario"))
    }

    @Test
    fun `diferencia de logo no crea apariencia nueva`() {
        val resultado =
            AgrupadorImportacion.agrupar(
                listOf(
                    declaracion(nombre = "Canal", logo = "http://example.com/logo1.png"),
                    declaracion(nombre = "Canal", logo = "http://example.com/logo2.png"),
                ),
            )
        val canal = resultado.canales.single()
        assertEquals(1, canal.apariencias.size)
        assertNotNull(canal.logoCanonica)
        assertEquals(2, canal.declaraciones)
        assertEquals(0, resultado.repeticionesInformativas)
    }
}
