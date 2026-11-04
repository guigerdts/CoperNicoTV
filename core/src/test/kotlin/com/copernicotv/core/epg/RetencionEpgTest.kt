package com.copernicotv.core.epg

import java.time.Instant
import java.time.temporal.ChronoUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class RetencionEpgTest {
    private data class Programa(
        val canal: String,
        val titulo: String,
        val inicio: Instant,
        val fin: Instant,
        val descripcion: String? = null,
    )

    private val ahora = Instant.parse("2026-01-10T12:00:00Z")

    private fun aplicar(
        programas: List<Programa>,
        limites: LimitesRetencion,
    ): ResultadoRetencion<Programa> =
        RetencionEpg.aplicar(
            programas = programas,
            ahora = ahora,
            limites = limites,
            canal = { it.canal },
            inicio = { it.inicio },
            fin = { it.fin },
            tamano = { RetencionEpg.tamanoLogico(it.canal, it.titulo, it.descripcion) },
        )

    private fun programa(
        canal: String,
        horasDesdeAhora: Long,
        duracionHoras: Long = 1,
    ): Programa =
        Programa(
            canal = canal,
            titulo = "$canal-$horasDesdeAhora",
            inicio = ahora.plus(horasDesdeAhora, ChronoUnit.HOURS),
            fin = ahora.plus(horasDesdeAhora + duracionHoras, ChronoUnit.HOURS),
        )

    @Test
    fun `conserva el programa que solapa el borde de la ventana`() {
        val enEmision =
            Programa(
                canal = "a",
                titulo = "en emision",
                inicio = ahora.minus(2, ChronoUnit.HOURS),
                fin = ahora.minus(30, ChronoUnit.MINUTES),
            )
        val fuera = programa("a", -30)
        val resultado = aplicar(listOf(enEmision, fuera), LimitesRetencion())
        assertEquals(listOf("en emision"), resultado.conservados.map { it.titulo })
        assertEquals(1, resultado.fueraDeVentana)
    }

    @Test
    fun `descarta programas fuera de la ventana por delante y por detras`() {
        val programas =
            listOf(
                programa("a", -25),
                programa("a", -23),
                programa("a", 0),
                programa("a", 24 * 6),
                programa("a", 24 * 8),
            )
        val resultado = aplicar(programas, LimitesRetencion())
        assertEquals(listOf("a--23", "a-0", "a-144"), resultado.conservados.map { it.titulo })
        assertEquals(2, resultado.fueraDeVentana)
    }

    @Test
    fun `maximo por canal evicta primero el mas antiguo`() {
        val programas =
            (0 until 5).map { programa("a", it.toLong()) } +
                (0 until 3).map { programa("b", it.toLong()) }
        val resultado =
            aplicar(
                programas,
                LimitesRetencion(maxProgramasPorCanal = 3, presupuestoBytes = Long.MAX_VALUE),
            )
        assertEquals(3, resultado.conservados.count { it.canal == "a" })
        assertEquals(3, resultado.conservados.count { it.canal == "b" })
        assertEquals(2, resultado.evictosPorCanal)
        assertEquals(setOf("a-2", "a-3", "a-4"), resultado.conservados.filter { it.canal == "a" }.map { it.titulo }.toSet())
    }

    @Test
    fun `presupuesto global evicta primero la programacion mas antigua`() {
        val programas =
            listOf(
                programa("a", 0),
                programa("a", 1),
                programa("b", 2),
                programa("b", 3),
            )
        val tamano = RetencionEpg.tamanoLogico("a", "a-0", null)
        val resultado =
            aplicar(
                programas,
                LimitesRetencion(
                    maxProgramasPorCanal = 100,
                    presupuestoBytes = tamano * 2,
                ),
            )
        assertEquals(2, resultado.conservados.size)
        assertEquals(setOf("b-2", "b-3"), resultado.conservados.map { it.titulo }.toSet())
        assertEquals(2, resultado.evictosPorPresupuesto)
        assertEquals(tamano * 2, resultado.bytesConservados)
    }

    @Test
    fun `presupuesto menor que un programa evicta todo`() {
        val resultado =
            aplicar(
                listOf(programa("a", 0)),
                LimitesRetencion(maxProgramasPorCanal = 100, presupuestoBytes = 1),
            )
        assertEquals(0, resultado.conservados.size)
        assertEquals(1, resultado.evictosPorPresupuesto)
        assertEquals(0, resultado.bytesConservados)
    }

    @Test
    fun `resolucion global y por fuente`() {
        val global = LimitesRetencion()
        assertEquals(global, RetencionEpg.resolver(global, RetencionFuente(usarGlobal = true)))
        assertEquals(
            LimitesRetencion(ventanaPasadoDias = 2, ventanaFuturoDias = 3, maxProgramasPorCanal = 10, presupuestoBytes = 1024),
            RetencionEpg.resolver(
                global,
                RetencionFuente(
                    usarGlobal = false,
                    ventanaPasadoDias = 2,
                    ventanaFuturoDias = 3,
                    maxProgramasPorCanal = 10,
                    presupuestoBytes = 1024,
                ),
            ),
        )
        assertEquals(
            global,
            RetencionEpg.resolver(global, RetencionFuente(usarGlobal = false)),
        )
    }

    @Test
    fun `valores invalidos se rechazan`() {
        assertFailsWith<IllegalArgumentException> { LimitesRetencion(ventanaPasadoDias = -1) }
        assertFailsWith<IllegalArgumentException> { LimitesRetencion(maxProgramasPorCanal = 0) }
        assertFailsWith<IllegalArgumentException> { LimitesRetencion(presupuestoBytes = 0) }
    }

    @Test
    fun `tamano logico suma longitudes utf8 y tiempos`() {
        assertEquals(4 + 1 + 4 + 2 + 4 + 0 + 16, RetencionEpg.tamanoLogico("a", "bb", null))
        assertEquals(4 + 1 + 4 + 2 + 4 + 2 + 16, RetencionEpg.tamanoLogico("a", "bb", "cd"))
    }
}
