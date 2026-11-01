package com.copernicotv.app.ui

import com.copernicotv.app.data.DisponibilidadFuente
import com.copernicotv.app.data.IndicadoresFuente
import com.copernicotv.app.data.IndicadoresFuenteRepositorio
import com.copernicotv.app.data.actualizacion.EstadoSolicitud
import com.copernicotv.app.data.actualizacion.ProgramadorActualizaciones
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * E4-T8/S7a: `cargando` pasa de `true` a `false` al terminar la Actualización,
 * tanto en éxito como en fallo, sin reutilizar la disponibilidad.
 *
 * Desde E4-T7 el indicador sale del estado del trabajo de WorkManager, así que
 * la prueba mueve ese estado en lugar de esperar una corrutina: es lo mismo que
 * observa la pantalla cuando el trabajo pasa de `RUNNING` a `SUCCEEDED` o
 * `FAILED`.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FuentesViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val fuente =
        IndicadoresFuente(
            fuenteId = 7L,
            nombre = "Fuente",
            disponibilidad = DisponibilidadFuente.DISPONIBLE,
        )
    private val estados = MutableStateFlow<Map<Long, EstadoSolicitud>>(emptyMap())

    @Before
    fun preparar() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun limpiar() {
        Dispatchers.resetMain()
    }

    @Test
    fun `S7a cargando vuelve a false cuando el trabajo termina bien`() =
        runTest(dispatcher) {
            val viewModel = crearViewModel()
            runCurrent()
            assertFalse("La pantalla ya cargó", viewModel.estado.value.cargando)
            assertFalse("Sin trabajo no está cargando", viewModel.estado.value.fuentes.single().cargando)

            estados.value = mapOf(fuente.fuenteId to EstadoSolicitud(enCurso = true))
            runCurrent()
            assertTrue("Carga mientras el trabajo está abierto", viewModel.estado.value.fuentes.single().cargando)

            estados.value = mapOf(fuente.fuenteId to EstadoSolicitud(enCurso = false))
            runCurrent()

            assertFalse("Termina en false con éxito", viewModel.estado.value.fuentes.single().cargando)
            assertNull("Un éxito no deja error", viewModel.estado.value.error)
            assertEquals(
                "La disponibilidad no se reutiliza como estado de actualización",
                DisponibilidadFuente.DISPONIBLE,
                viewModel.estado.value.fuentes.single().disponibilidad,
            )
        }

    @Test
    fun `S7a cargando vuelve a false cuando el trabajo termina en fallo`() =
        runTest(dispatcher) {
            val viewModel = crearViewModel()
            runCurrent()

            estados.value = mapOf(fuente.fuenteId to EstadoSolicitud(enCurso = true))
            runCurrent()
            assertTrue("Sigue cargando mientras el trabajo está abierto", viewModel.estado.value.fuentes.single().cargando)

            estados.value = mapOf(fuente.fuenteId to EstadoSolicitud(enCurso = false))
            runCurrent()

            assertFalse("Termina en false con fallo", viewModel.estado.value.fuentes.single().cargando)
            assertEquals(
                "El fallo no cambia la disponibilidad",
                DisponibilidadFuente.DISPONIBLE,
                viewModel.estado.value.fuentes.single().disponibilidad,
            )
        }

    @Test
    fun `una solicitud que no se puede encolar deja el codigo de error`() =
        runTest(dispatcher) {
            val viewModel =
                FuentesViewModel(
                    repositorio = RepositorioFalso(listOf(fuente)),
                    programador = ProgramadorFalso(estados) { error("sin_conexion") },
                )
            runCurrent()

            viewModel.actualizar(fuente.fuenteId)
            advanceUntilIdle()

            assertEquals("fuentes_actualizacion_fallida", viewModel.estado.value.error)
            assertFalse("No queda cargando", viewModel.estado.value.fuentes.single().cargando)
        }

    private fun crearViewModel(): FuentesViewModel =
        FuentesViewModel(
            repositorio = RepositorioFalso(listOf(fuente)),
            programador = ProgramadorFalso(estados),
        )
}

private class RepositorioFalso(
    fuentes: List<IndicadoresFuente>,
) : IndicadoresFuenteRepositorio {
    private val flujo = MutableStateFlow(fuentes)

    override fun observar(): Flow<List<IndicadoresFuente>> = flujo
}

private class ProgramadorFalso(
    private val estados: MutableStateFlow<Map<Long, EstadoSolicitud>>,
    private val alSolicitar: suspend (Long) -> Unit = {},
) : ProgramadorActualizaciones {
    override fun observar(): Flow<Map<Long, EstadoSolicitud>> = estados

    override fun observarLista(listaId: Long): Flow<EstadoSolicitud> =
        estados.map { it[listaId] ?: EstadoSolicitud() }

    override suspend fun solicitarLista(listaId: Long) = alSolicitar(listaId)

    override suspend fun solicitarFuente(fuenteId: Long) = alSolicitar(fuenteId)

    override suspend fun reconciliar() = Unit
}
