package com.copernicotv.core.identity

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs

class TvgIdTest {
    @Test
    fun `valor valido se persiste recortado`() {
        val resultado = ValidadorTvgId.validar("  canal.es  ")
        assertIs<ResultadoTvgId.Valido>(resultado)
        assertEquals("canal.es", resultado.valor)
    }

    @Test
    fun `ausente y vacio se rechazan con motivo tipado`() {
        assertEquals(MotivoTvgId.AUSENTE, (ValidadorTvgId.validar(null) as ResultadoTvgId.Invalido).motivo)
        assertEquals(MotivoTvgId.VACIO_TRAS_RECORTE, (ValidadorTvgId.validar("   ") as ResultadoTvgId.Invalido).motivo)
        assertEquals(MotivoTvgId.VACIO_TRAS_RECORTE, (ValidadorTvgId.validar("") as ResultadoTvgId.Invalido).motivo)
    }

    @Test
    fun `caracteres Cc y Cf se rechazan`() {
        assertEquals(
            MotivoTvgId.CONTIENE_CONTROL,
            (ValidadorTvgId.validar("canal\u0007") as ResultadoTvgId.Invalido).motivo,
        )
        assertEquals(
            MotivoTvgId.CONTIENE_CONTROL,
            (ValidadorTvgId.validar("canal\u200E") as ResultadoTvgId.Invalido).motivo,
        )
    }

    @Test
    fun `limite de 512 code points`() {
        assertIs<ResultadoTvgId.Valido>(ValidadorTvgId.validar("a".repeat(512)))
        assertEquals(
            MotivoTvgId.EXCEDE_LONGITUD,
            (ValidadorTvgId.validar("a".repeat(513)) as ResultadoTvgId.Invalido).motivo,
        )
    }

    @Test
    fun `code points fuera del BMP cuentan como uno`() {
        assertIs<ResultadoTvgId.Valido>(ValidadorTvgId.validar("\uD83D\uDCFA".repeat(512)))
        assertEquals(
            MotivoTvgId.EXCEDE_LONGITUD,
            (ValidadorTvgId.validar("\uD83D\uDCFA".repeat(513)) as ResultadoTvgId.Invalido).motivo,
        )
    }

    @Test
    fun `surrogate no emparejado se rechaza`() {
        assertEquals(
            MotivoTvgId.SURROGATE_INVALIDO,
            (ValidadorTvgId.validar("canal\uD800") as ResultadoTvgId.Invalido).motivo,
        )
    }

    @Test
    fun `no se impone alfabeto adicional`() {
        assertIs<ResultadoTvgId.Valido>(ValidadorTvgId.validar("canal.es_1-ñ:con espacios"))
    }
}
