package com.copernicotv.core.epg

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class PropuestaAsociacionTest {
    private fun canal(
        nombre: String? = "ESPN HD",
        tvgId: String? = null,
    ) = CanalAsociableEntrada(
        canalId = 1,
        nombre = nombre,
        tvgId = tvgId,
    )

    private fun guia(vararg pares: Pair<String, List<String>>): List<CanalGuiaEntrada> = pares.map { CanalGuiaEntrada(it.first, it.second) }

    @Test
    fun `un tvg-id identico propone automaticamente`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(tvgId = "ESPN.mx"),
                guia("espn.mx" to listOf("ESPN"), "otro" to listOf("Otro")),
            )
        assertEquals(DecisionAsociacion.AUTOMATICA, resultado.decision)
        assertEquals("id_exacto", resultado.motivo)
        assertEquals("espn.mx", resultado.propuestas.single().channelIdTexto)
        assertEquals(HeuristicaPropuestasEpg.PUNTAJE_ID_EXACTO, resultado.propuestas.single().puntaje)
    }

    @Test
    fun `un nombre identico unico propone automaticamente`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(nombre = "Canal   Central"),
                guia("c1" to listOf("canal central"), "c2" to listOf("Canal Central Dos")),
            )
        assertEquals(DecisionAsociacion.AUTOMATICA, resultado.decision)
        assertEquals("nombre_exacto_unico", resultado.motivo)
        assertEquals("c1", resultado.propuestas.first().channelIdTexto)
    }

    @Test
    fun `dos nombres identicos empatan y exigen confirmacion manual`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(nombre = "Canal Central"),
                guia("c1" to listOf("Canal Central"), "c2" to listOf("Canal Central")),
            )
        assertEquals(DecisionAsociacion.PENDIENTE, resultado.decision)
        assertEquals("empate", resultado.motivo)
        assertEquals(2, resultado.propuestas.size)
    }

    @Test
    fun `un nombre contenido nunca asocia automaticamente`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(nombre = "ESPN HD México"),
                guia("c1" to listOf("ESPN HD")),
            )
        assertEquals(DecisionAsociacion.PENDIENTE, resultado.decision)
        assertEquals("semejanza", resultado.motivo)
        assertEquals(HeuristicaPropuestasEpg.PUNTAJE_CONTENIDO, resultado.propuestas.single().puntaje)
    }

    @Test
    fun `el solapamiento de palabras propone sin asociar`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(nombre = "Discovery Channel Latin America"),
                guia("c1" to listOf("Discovery Channel")),
            )
        assertEquals(DecisionAsociacion.PENDIENTE, resultado.decision)
        assertTrue(resultado.propuestas.single().puntaje in 60..75)
    }

    @Test
    fun `una palabra suelta en comun no propone nada`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(nombre = "Cine Acción"),
                guia("c1" to listOf("Cine Romántico")),
            )
        assertEquals(DecisionAsociacion.SIN_CANDIDATO, resultado.decision)
        assertTrue(resultado.propuestas.isEmpty())
    }

    @Test
    fun `sin nombre ni tvg-id no hay candidato`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(nombre = null, tvgId = null),
                guia("c1" to listOf("Cualquiera")),
            )
        assertEquals(DecisionAsociacion.SIN_CANDIDATO, resultado.decision)
    }

    @Test
    fun `las propuestas se ordenan por puntaje y se acotan`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                canal(nombre = "Canal Uno"),
                guia(
                    "z" to listOf("Canal Uno"),
                    "a" to listOf("Canal Uno Plus"),
                    "b" to listOf("Canal"),
                    "c" to listOf("Otro"),
                ),
                maximo = 2,
            )
        assertEquals(2, resultado.propuestas.size)
        assertEquals("z", resultado.propuestas.first().channelIdTexto)
        assertTrue(resultado.propuestas.first().puntaje > resultado.propuestas.last().puntaje)
    }

    @Test
    fun `un nombre vacio en la guia se ignora sin lanzar`() {
        val resultado =
            HeuristicaPropuestasEpg.proponer(
                CanalAsociableEntrada(canalId = 1, nombre = "Canal", tvgId = null),
                listOf(CanalGuiaEntrada("vacio", listOf("  "))),
            )
        assertEquals(DecisionAsociacion.SIN_CANDIDATO, resultado.decision)
    }
}
