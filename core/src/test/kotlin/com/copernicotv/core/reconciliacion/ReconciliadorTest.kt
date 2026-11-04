package com.copernicotv.core.reconciliacion

import com.copernicotv.core.identity.EstadoCanal
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ReconciliadorTest {
    private fun identidad(
        nombre: String? = "Canal",
        tvgId: String? = null,
        canonica: String? = null,
        unicidad: String? = null,
        derivable: Boolean = true,
        hmac: String = "hmac:v1:cv1:x",
    ) = IdentidadReconciliacion(
        nombre = nombre,
        tvgId = tvgId,
        claveCanonica = canonica,
        claveUnicidad = unicidad,
        claveDerivable = derivable,
        urlStreamHmac = hmac,
    )

    private fun fila(
        id: Long,
        identidad: IdentidadReconciliacion,
        estado: EstadoCanal = EstadoCanal.A,
    ) = FilaHistorica(id = id, identidad = identidad, estado = estado)

    @Test
    fun `coincidencia exacta conserva la pk y no marca ausencia`() {
        val resultado =
            Reconciliador.reconciliar(
                historicas = listOf(fila(1, identidad(canonica = "canon:v2:a", unicidad = "tvg:v1:uno"))),
                declaraciones = listOf(DeclaracionNueva(0, identidad(canonica = "canon:v2:a", unicidad = "tvg:v1:uno"))),
            )

        assertEquals(EstadoCanal.A, resultado.filas.single().estado)
        assertEquals(1L, resultado.filas.single().historicoId)
        assertEquals(0, resultado.filas.single().declaracionIndice)
        assertTrue(resultado.ausencias.isEmpty())
        assertTrue(resultado.colisiones.isEmpty())
    }

    @Test
    fun `a-b con dos filas y una clave comun no fusiona`() {
        val canon = "canon:v2:comun"
        val resultado =
            Reconciliador.reconciliar(
                historicas =
                    listOf(
                        fila(1, identidad(tvgId = "uno", canonica = canon, unicidad = "tvg:v1:uno")),
                        fila(2, identidad(tvgId = "dos", canonica = canon, unicidad = "tvg:v1:dos")),
                    ),
                declaraciones = listOf(DeclaracionNueva(0, identidad(tvgId = null, canonica = canon, unicidad = canon))),
            )

        assertEquals(listOf(EstadoCanal.B, EstadoCanal.B), resultado.filas.map { it.estado })
        assertTrue(resultado.filas.all { it.identidad.claveUnicidad == null })
        assertTrue(resultado.filas.all { it.identidad.claveCanonica == canon })
        assertEquals(DestinoDeclaracion.OBSERVACION, resultado.declaraciones.single().destino)
        assertEquals(listOf(1L, 2L), resultado.colisiones.single().filas)
        assertTrue(resultado.ausencias.isEmpty())
    }

    @Test
    fun `c-a cierra con correspondencia unica y claves distintas`() {
        val canon = "canon:v2:comun"
        val resultado =
            Reconciliador.reconciliar(
                historicas =
                    listOf(
                        fila(1, identidad(nombre = "Uno", canonica = canon, hmac = "hmac:uno"), EstadoCanal.C),
                        fila(2, identidad(nombre = "Dos", canonica = canon, hmac = "hmac:dos"), EstadoCanal.C),
                    ),
                declaraciones =
                    listOf(
                        DeclaracionNueva(
                            0,
                            identidad(
                                nombre = "Uno",
                                canonica = "canon:v2:uno",
                                unicidad = "canon:v2:uno",
                                hmac = "hmac:uno",
                            ),
                        ),
                        DeclaracionNueva(
                            1,
                            identidad(
                                nombre = "Dos",
                                canonica = "canon:v2:dos",
                                unicidad = "canon:v2:dos",
                                hmac = "hmac:dos",
                            ),
                        ),
                    ),
            )

        assertEquals(listOf(EstadoCanal.A, EstadoCanal.A), resultado.filas.map { it.estado })
        assertEquals(listOf("canon:v2:uno", "canon:v2:dos"), resultado.filas.map { it.identidad.claveUnicidad })
        assertEquals(setOf(1L, 2L), resultado.cierresC.toSet())
        assertTrue(resultado.filas.all { it.decisionSuperada })
    }

    @Test
    fun `c-b vuelve a ambiguedad si la colision persiste`() {
        val canon = "canon:v2:comun"
        val resultado =
            Reconciliador.reconciliar(
                historicas =
                    listOf(
                        fila(1, identidad(canonica = canon), EstadoCanal.C),
                        fila(2, identidad(canonica = canon), EstadoCanal.C),
                    ),
                declaraciones = listOf(DeclaracionNueva(0, identidad(canonica = canon, unicidad = canon))),
            )

        assertEquals(listOf(EstadoCanal.B, EstadoCanal.B), resultado.filas.map { it.estado })
        assertTrue(resultado.filas.all { it.identidad.claveUnicidad == null })
        assertTrue(resultado.filas.all { it.decisionSuperada })
        assertEquals(DestinoDeclaracion.OBSERVACION, resultado.declaraciones.single().destino)
    }

    @Test
    fun `c no se cierra si la correspondencia es ambigua`() {
        val canon = "canon:v2:comun"
        val resultado =
            Reconciliador.reconciliar(
                historicas =
                    listOf(
                        fila(1, identidad(nombre = "Canal", canonica = canon), EstadoCanal.C),
                        fila(2, identidad(nombre = "Canal", canonica = canon), EstadoCanal.C),
                    ),
                declaraciones =
                    listOf(
                        DeclaracionNueva(0, identidad(nombre = "Canal", canonica = null, unicidad = "canon:v2:a")),
                        DeclaracionNueva(1, identidad(nombre = "Canal", canonica = null, unicidad = "canon:v2:b")),
                    ),
            )

        assertEquals(listOf(EstadoCanal.C, EstadoCanal.C), resultado.filas.map { it.estado })
        assertTrue(resultado.cierresC.isEmpty())
        assertTrue(resultado.filas.none { it.decisionSuperada })
    }

    @Test
    fun `la ausencia conserva la fila y no borra`() {
        val resultado =
            Reconciliador.reconciliar(
                historicas = listOf(fila(7, identidad(canonica = "canon:v2:a", unicidad = "tvg:v1:a"))),
                declaraciones = emptyList(),
            )

        assertEquals(EstadoCanal.A, resultado.filas.single().estado)
        assertEquals(listOf(7L), resultado.ausencias)
    }

    @Test
    fun `una fila b permanece b y la declaracion colisionante es observacion`() {
        val canon = "canon:v2:comun"
        val resultado =
            Reconciliador.reconciliar(
                historicas = listOf(fila(1, identidad(canonica = canon), EstadoCanal.B)),
                declaraciones = listOf(DeclaracionNueva(0, identidad(canonica = canon, unicidad = canon))),
            )

        assertEquals(EstadoCanal.B, resultado.filas.single().estado)
        assertNull(resultado.filas.single().identidad.claveUnicidad)
        assertEquals(DestinoDeclaracion.OBSERVACION, resultado.declaraciones.single().destino)
    }

    @Test
    fun `una declaracion sin colision crea un canal nuevo`() {
        val resultado =
            Reconciliador.reconciliar(
                historicas = emptyList(),
                declaraciones = listOf(DeclaracionNueva(0, identidad(canonica = "canon:v2:nueva", unicidad = "canon:v2:nueva"))),
            )

        assertEquals(DestinoDeclaracion.CANAL_NUEVO, resultado.declaraciones.single().destino)
    }

    @Test
    fun `una declaracion con evidencia compartida sin correspondencia no crea canal`() {
        val resultado =
            Reconciliador.reconciliar(
                historicas = listOf(fila(1, identidad(nombre = "Canal", canonica = "canon:v2:a", unicidad = "tvg:v1:a"))),
                declaraciones = listOf(DeclaracionNueva(0, identidad(nombre = "Canal", canonica = "canon:v2:b", unicidad = "canon:v2:b"))),
            )

        assertEquals(EstadoCanal.A, resultado.filas.single().estado)
        assertEquals(DestinoDeclaracion.OBSERVACION, resultado.declaraciones.single().destino)
        assertTrue(resultado.ausencias.isEmpty())
    }

    @Test
    fun `el orden de las declaraciones no decide la asignacion`() {
        val resultado =
            Reconciliador.reconciliar(
                historicas =
                    listOf(
                        fila(1, identidad(canonica = "canon:v2:a", unicidad = "tvg:v1:a")),
                        fila(2, identidad(canonica = "canon:v2:b", unicidad = "tvg:v1:b")),
                    ),
                declaraciones =
                    listOf(
                        DeclaracionNueva(0, identidad(canonica = "canon:v2:b", unicidad = "tvg:v1:b")),
                        DeclaracionNueva(1, identidad(canonica = "canon:v2:a", unicidad = "tvg:v1:a")),
                    ),
            )

        assertEquals(1, resultado.filas.first { it.historicoId == 1L }.declaracionIndice)
        assertEquals(0, resultado.filas.first { it.historicoId == 2L }.declaracionIndice)
    }
}
