package com.copernicotv.app.player

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Reglas puras del zapeo y del volumen de E6-T6, sin Android ni Media3.
 *
 * El vecino de un Canal en el universo ordenado del zapeo y el acotado del
 * volumen a la escala 0..1 de Media3: el ViewModel y la interfaz solo aplican
 * estas reglas.
 */
class ZapeoReproductorTest {
    private val canales = listOf(101L, 102L, 103L)

    @Test
    fun elSiguienteDelMedioEsElCanalVecinoEnOrden() {
        assertEquals(102L, canalVecino(canales, 101L, DireccionZapeo.SIGUIENTE))
        assertEquals(103L, canalVecino(canales, 102L, DireccionZapeo.SIGUIENTE))
    }

    @Test
    fun elAnteriorDelMedioEsElCanalVecinoEnOrden() {
        assertEquals(101L, canalVecino(canales, 102L, DireccionZapeo.ANTERIOR))
        assertEquals(102L, canalVecino(canales, 103L, DireccionZapeo.ANTERIOR))
    }

    @Test
    fun elUltimoCanalNoTieneSiguiente() {
        assertNull(canalVecino(canales, 103L, DireccionZapeo.SIGUIENTE))
    }

    @Test
    fun elPrimerCanalNoTieneAnterior() {
        assertNull(canalVecino(canales, 101L, DireccionZapeo.ANTERIOR))
    }

    @Test
    fun unCatalogoVacioNoOfreceVecinos() {
        assertNull(canalVecino(emptyList(), 101L, DireccionZapeo.SIGUIENTE))
        assertNull(canalVecino(emptyList(), 101L, DireccionZapeo.ANTERIOR))
    }

    @Test
    fun unCanalAusenteDelUniversoNoOfreceVecinos() {
        assertNull(canalVecino(canales, 999L, DireccionZapeo.SIGUIENTE))
        assertNull(canalVecino(canales, 999L, DireccionZapeo.ANTERIOR))
    }

    @Test
    fun elVolumenSeRedondeaAUnDecimalSinArrastreDeFlotantes() {
        assertEquals(0.3f, limitarVolumen(0.1f + 0.1f + 0.1f))
        assertEquals(0.7f, limitarVolumen(0.7f))
    }

    @Test
    fun elVolumenSeAcotaAlRangoValidoDeMedia3() {
        assertEquals(1.0f, limitarVolumen(1.5f))
        assertEquals(1.0f, limitarVolumen(Float.POSITIVE_INFINITY))
        assertEquals(0.0f, limitarVolumen(-0.2f))
        assertEquals(0.0f, limitarVolumen(Float.NEGATIVE_INFINITY))
    }

    @Test
    fun unVolumenInvalidoNoInventaValor() {
        assertEquals(0.0f, limitarVolumen(Float.NaN))
    }

    @Test
    fun elPasoDeVolumenEsUnDecimo() {
        assertEquals(0.1f, PASO_VOLUMEN)
    }
}
