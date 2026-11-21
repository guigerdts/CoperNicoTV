package com.copernicotv.app.ota

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Aislamiento de la programación del OTA.
 *
 * Los dos sistemas comparten WorkManager: si el OTA reutilizara un nombre o un
 * tag de la actualización de listas, una cancelación o una observación de la UI
 * tocaría trabajo ajeno. Estas pruebas fijan el contrato sin depender de
 * memoria ni de revisión manual.
 */
class TrabajosOtaTest {
    @Test
    fun `los nombres del ota no se repiten entre si`() {
        val nombres = listOf(TrabajosOta.PERIODICO, TrabajosOta.MANUAL)

        assertEquals(nombres.size, nombres.toSet().size)
        assertTrue(nombres.all { it.startsWith("ota-") })
    }

    @Test
    fun `los tags del ota no colisionan con los de la actualizacion de listas`() {
        val tagsOta = setOf(TrabajosOta.ETIQUETA, TrabajosOta.ETIQUETA_PERIODICO, TrabajosOta.ETIQUETA_MANUAL)
        val tagsListas = setOf("actualizacion", "actualizacion-manual", "fuente:1")

        assertTrue(tagsOta.intersect(tagsListas).isEmpty())
    }

    @Test
    fun `cada trabajo lleva el tag comun y el suyo`() {
        assertTrue(TrabajosOta.ETIQUETA in TrabajosOta.ETIQUETAS_PERIODICO)
        assertTrue(TrabajosOta.ETIQUETA_PERIODICO in TrabajosOta.ETIQUETAS_PERIODICO)
        assertTrue(TrabajosOta.ETIQUETA in TrabajosOta.ETIQUETAS_MANUAL)
        assertTrue(TrabajosOta.ETIQUETA_MANUAL in TrabajosOta.ETIQUETAS_MANUAL)
        assertEquals(2, TrabajosOta.ETIQUETAS_PERIODICO.size)
        assertEquals(2, TrabajosOta.ETIQUETAS_MANUAL.size)
    }

    @Test
    fun `la comprobacion periodica es de doce horas`() {
        assertEquals(12L, TrabajosOta.HORAS_PERIODO)
    }
}
