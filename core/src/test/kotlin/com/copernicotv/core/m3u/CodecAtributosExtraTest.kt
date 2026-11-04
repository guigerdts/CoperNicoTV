package com.copernicotv.core.m3u

import com.copernicotv.core.identity.AtributoExtra
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class CodecAtributosExtraTest {
    @Test
    fun `la codificacion es determinista e independiente del orden`() {
        val uno = listOf(AtributoExtra("b", "2"), AtributoExtra("a", "1"))
        val otro = listOf(AtributoExtra("a", "1"), AtributoExtra("b", "2"))
        assertEquals("a=1\nb=2", CodecAtributosExtra.codificar(uno))
        assertEquals(CodecAtributosExtra.codificar(uno), CodecAtributosExtra.codificar(otro))
    }

    @Test
    fun `sobrevive a iguales saltos de linea y barras invertidas`() {
        val atributos =
            listOf(
                AtributoExtra("clave=rara", "valor\\con\nsalto"),
                AtributoExtra("otra", "a=b"),
            )
        val texto = CodecAtributosExtra.codificar(atributos)
        assertEquals(atributos, CodecAtributosExtra.decodificar(texto))
    }

    @Test
    fun `una lista vacia se codifica como null`() {
        assertNull(CodecAtributosExtra.codificar(emptyList()))
    }

    @Test
    fun `un texto nulo o vacio no produce atributos`() {
        assertEquals(emptyList(), CodecAtributosExtra.decodificar(null))
        assertEquals(emptyList(), CodecAtributosExtra.decodificar(""))
    }

    @Test
    fun `las lineas sin separador se descartan`() {
        val texto = "a=1\nsin-separador\nb=2"
        assertEquals(
            listOf(AtributoExtra("a", "1"), AtributoExtra("b", "2")),
            CodecAtributosExtra.decodificar(texto),
        )
    }

    @Test
    fun `conserva claves repetidas`() {
        val atributos = listOf(AtributoExtra("k", "1"), AtributoExtra("k", "2"))
        assertEquals(atributos, CodecAtributosExtra.decodificar(CodecAtributosExtra.codificar(atributos)))
    }
}
