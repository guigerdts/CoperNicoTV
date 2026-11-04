package com.copernicotv.core.actualizacion

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/** S46b/DC-07 punto 2: validación del intervalo sin recorte silencioso. */
class IntervaloActualizacionTest {
    @Test
    fun `el valor por defecto es 720 minutos y se acepta`() {
        val resultado = IntervaloActualizacion.de(IntervaloActualizacion.POR_DEFECTO)

        assertEquals(720L, resultado.intervalo().minutos)
    }

    @Test
    fun `el minimo y el maximo son inclusivos`() {
        assertTrue(IntervaloActualizacion.de(15L) is ResultadoIntervalo.Aceptado)
        assertTrue(IntervaloActualizacion.de(10080L) is ResultadoIntervalo.Aceptado)
    }

    @Test
    fun `un valor debajo del minimo se rechaza y no se recorta`() {
        assertEquals(
            ResultadoIntervalo.Rechazado(MotivoRechazo.DEBAJO_DEL_MINIMO),
            IntervaloActualizacion.de(14L),
        )
        assertEquals(
            ResultadoIntervalo.Rechazado(MotivoRechazo.DEBAJO_DEL_MINIMO),
            IntervaloActualizacion.de(0L),
        )
    }

    @Test
    fun `un valor sobre el maximo se rechaza y no se recorta`() {
        assertEquals(
            ResultadoIntervalo.Rechazado(MotivoRechazo.SOBRE_EL_MAXIMO),
            IntervaloActualizacion.de(10081L),
        )
    }

    @Test
    fun `usar global ignora el override de la fuente`() {
        val resultado = intervaloEfectivo(globalMinutos = 720L, propioMinutos = 30L, usarGlobal = true)

        assertEquals(720L, (resultado as ResultadoIntervalo.Aceptado).intervalo.minutos)
    }

    @Test
    fun `sin usar global se aplica el override de la fuente`() {
        val resultado = intervaloEfectivo(globalMinutos = 720L, propioMinutos = 30L, usarGlobal = false)

        assertEquals(30L, (resultado as ResultadoIntervalo.Aceptado).intervalo.minutos)
    }

    @Test
    fun `sin override y sin usar global se aplica el global`() {
        val resultado = intervaloEfectivo(globalMinutos = 720L, propioMinutos = null, usarGlobal = false)

        assertEquals(720L, (resultado as ResultadoIntervalo.Aceptado).intervalo.minutos)
    }

    @Test
    fun `un override fuera de rango se rechaza aunque el global sea valido`() {
        assertEquals(
            ResultadoIntervalo.Rechazado(MotivoRechazo.DEBAJO_DEL_MINIMO),
            intervaloEfectivo(globalMinutos = 720L, propioMinutos = 5L, usarGlobal = false),
        )
    }

    private fun ResultadoIntervalo.intervalo(): IntervaloActualizacion = (this as ResultadoIntervalo.Aceptado).intervalo
}
