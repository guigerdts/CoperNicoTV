package com.copernicotv.app.ui

import com.copernicotv.app.data.DisponibilidadFuente
import com.copernicotv.app.data.EjecutorActualizacionFuente
import com.copernicotv.app.data.IndicadoresFuente
import com.copernicotv.app.data.IndicadoresFuenteRepositorio
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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

    @Before
    fun preparar() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun limpiar() {
        Dispatchers.resetMain()
    }

    @Test
    fun `S7a cargando vuelve a false cuando la actualizacion termina bien`() =
        runTest(dispatcher) {
            val puerta = CompletableDeferred<Unit>()
            val viewModel =
                FuentesViewModel(
                    repositorio = RepositorioFalso(listOf(fuente)),
                    ejecutor = EjecutorActualizacionFuente { puerta.await() },
                )
            runCurrent()
            assertFalse("La pantalla ya cargó", viewModel.estado.value.cargando)
            assertFalse("Sin Actualización no está cargando", viewModel.estado.value.fuentes.single().cargando)

            viewModel.actualizar(fuente.fuenteId)
            assertTrue("Empieza a cargar al lanzar la Actualización", viewModel.estado.value.fuentes.single().cargando)
            runCurrent()
            assertTrue("Sigue cargando mientras no termina", viewModel.estado.value.fuentes.single().cargando)

            puerta.complete(Unit)
            advanceUntilIdle()

            assertFalse("Termina en false con éxito", viewModel.estado.value.fuentes.single().cargando)
            assertNull("Un éxito no deja error", viewModel.estado.value.error)
            assertEquals(
                "La disponibilidad no se reutiliza como estado de actualización",
                DisponibilidadFuente.DISPONIBLE,
                viewModel.estado.value.fuentes.single().disponibilidad,
            )
        }

    @Test
    fun `S7a cargando vuelve a false cuando la actualizacion falla`() =
        runTest(dispatcher) {
            val puerta = CompletableDeferred<Unit>()
            val viewModel =
                FuentesViewModel(
                    repositorio = RepositorioFalso(listOf(fuente)),
                    ejecutor =
                        EjecutorActualizacionFuente {
                            puerta.await()
                            error("sin_conexion")
                        },
                )
            runCurrent()

            viewModel.actualizar(fuente.fuenteId)
            runCurrent()
            assertTrue("Sigue cargando mientras no termina", viewModel.estado.value.fuentes.single().cargando)

            puerta.complete(Unit)
            advanceUntilIdle()

            assertFalse("Termina en false con fallo", viewModel.estado.value.fuentes.single().cargando)
            assertEquals("fuentes_actualizacion_fallida", viewModel.estado.value.error)
            assertEquals(
                "El fallo no cambia la disponibilidad",
                DisponibilidadFuente.DISPONIBLE,
                viewModel.estado.value.fuentes.single().disponibilidad,
            )
        }
}

private class RepositorioFalso(
    fuentes: List<IndicadoresFuente>,
) : IndicadoresFuenteRepositorio {
    private val flujo = MutableStateFlow(fuentes)

    override fun observar(): Flow<List<IndicadoresFuente>> = flujo
}
