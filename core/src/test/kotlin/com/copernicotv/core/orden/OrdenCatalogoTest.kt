package com.copernicotv.core.orden

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * S14e–S14i: ordenación A/B/C con collation española, dirección, `Sin grupo`
 * al final, nunca reproducidos al final y PK ascendente como último desempate.
 */
class OrdenCatalogoTest {
    @Test
    fun `modo nombre usa collation espanola y deja los sin nombre al final`() {
        val filas =
            listOf(
                fila("Álvaro", 1),
                fila("alfa", 2),
                fila("Beta", 3),
                fila("Nube", 4),
                fila("Ñandú", 5),
                fila("zeta", 6),
                fila(null, 7),
            )

        assertEquals(
            listOf("alfa", "Álvaro", "Beta", "Nube", "Ñandú", "zeta", null),
            OrdenCatalogo.ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.ASCENDENTE).map { it.nombre },
        )
        assertEquals(
            listOf("zeta", "Ñandú", "Nube", "Beta", "Álvaro", "alfa", null),
            OrdenCatalogo.ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE).map { it.nombre },
        )
    }

    @Test
    fun `los acentos desempatan despues de la clave primaria`() {
        val filas = listOf(fila("Álvaro", 2), fila("Alvaro", 1))

        assertEquals(
            listOf("Alvaro", "Álvaro"),
            OrdenCatalogo.ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.ASCENDENTE).map { it.nombre },
        )
        assertEquals(
            listOf("Álvaro", "Alvaro"),
            OrdenCatalogo.ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE).map { it.nombre },
        )
    }

    @Test
    fun `modo grupo ordena por grupo y deja sin grupo al final`() {
        val filas =
            listOf(
                fila("C1", 1, grupo = "Grupo Alfa"),
                fila("C2", 2, grupo = "Grupo Bravo"),
                fila("C3", 3, grupo = null),
                fila("C4", 4, grupo = "Grupo Alfa"),
                fila(null, 5, grupo = "Grupo Alfa"),
            )

        assertEquals(
            listOf("C1", "C4", null, "C2", "C3"),
            OrdenCatalogo.ordenar(filas, ModoOrden.GRUPO, DireccionOrden.ASCENDENTE).map { it.nombre },
        )
        assertEquals(
            listOf("C2", "C4", "C1", null, "C3"),
            OrdenCatalogo.ordenar(filas, ModoOrden.GRUPO, DireccionOrden.DESCENDENTE).map { it.nombre },
        )
    }

    @Test
    fun `modo recientes ordena por ultima reproduccion y deja nunca reproducidos al final`() {
        val filas =
            listOf(
                fila("C1", 1, fecha = 4L),
                fila("C2", 2, fecha = 2L),
                fila("C3", 3, fecha = 3L),
                fila("C4", 4, fecha = null),
            )

        assertEquals(
            listOf("C2", "C3", "C1", "C4"),
            OrdenCatalogo.ordenar(filas, ModoOrden.RECIENTES, DireccionOrden.ASCENDENTE).map { it.nombre },
        )
        assertEquals(
            listOf("C1", "C3", "C2", "C4"),
            OrdenCatalogo.ordenar(filas, ModoOrden.RECIENTES, DireccionOrden.DESCENDENTE).map { it.nombre },
        )
    }

    @Test
    fun `modo recientes desempata por nombre con la collation y la direccion`() {
        val filas =
            listOf(
                fila("Beta", 1, fecha = 5L),
                fila("alfa", 2, fecha = 5L),
                fila("z", 3, fecha = 5L),
                fila("ø", 4, fecha = 5L),
                fila(null, 5, fecha = 5L),
            )

        assertEquals(
            listOf("alfa", "Beta", "ø", "z", null),
            OrdenCatalogo.ordenar(filas, ModoOrden.RECIENTES, DireccionOrden.ASCENDENTE).map { it.nombre },
        )
        assertEquals(
            listOf("z", "ø", "Beta", "alfa", null),
            OrdenCatalogo.ordenar(filas, ModoOrden.RECIENTES, DireccionOrden.DESCENDENTE).map { it.nombre },
        )
    }

    @Test
    fun `los empates usan la PK ascendente en ambos sentidos`() {
        val filas = listOf(fila("Igual", 20), fila("Igual", 10))

        assertEquals(
            listOf(10L, 20L),
            OrdenCatalogo
                .ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.ASCENDENTE)
                .map { it.aparienciaId },
        )
        assertEquals(
            listOf(10L, 20L),
            OrdenCatalogo
                .ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE)
                .map { it.aparienciaId },
        )
    }

    @Test
    fun `modo manual conserva el orden y la PK desempata`() {
        val filas =
            listOf(
                fila("C2", 5, orden = 2),
                fila("C1", 7, orden = 1),
                fila("C1b", 6, orden = 1),
            )

        assertEquals(
            listOf(6L, 7L, 5L),
            OrdenCatalogo
                .ordenar(filas, ModoOrden.MANUAL, DireccionOrden.ASCENDENTE)
                .map { it.aparienciaId },
        )
        assertEquals(
            listOf(5L, 6L, 7L),
            OrdenCatalogo
                .ordenar(filas, ModoOrden.MANUAL, DireccionOrden.DESCENDENTE)
                .map { it.aparienciaId },
        )
    }

    @Test
    fun `los caracteres no alfabeticos van despues de las letras por code point`() {
        val filas = listOf(fila("A-", 1), fila("AZ", 2), fila("A2", 3), fila("A_1", 4))

        assertEquals(
            listOf("AZ", "A-", "A2", "A_1"),
            OrdenCatalogo.ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.ASCENDENTE).map { it.nombre },
        )
    }

    @Test
    fun `las letras no descomponibles usan su letra base`() {
        val filas = listOf(fila("ø", 1), fila("o", 2), fila("z", 3))

        assertEquals(
            listOf("o", "ø", "z"),
            OrdenCatalogo.ordenar(filas, ModoOrden.NOMBRE, DireccionOrden.ASCENDENTE).map { it.nombre },
        )
    }

    private fun fila(
        nombre: String?,
        id: Long,
        grupo: String? = null,
        fecha: Long? = null,
        orden: Int = 0,
    ): Fila = Fila(nombre, grupo, fecha, id, orden)

    private data class Fila(
        override val nombre: String?,
        override val grupoNombre: String?,
        override val ultimaReproduccionEpochMs: Long?,
        override val aparienciaId: Long,
        override val ordenManual: Int,
    ) : CanalOrdenable
}
