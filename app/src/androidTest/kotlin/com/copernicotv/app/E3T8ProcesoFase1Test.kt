package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.PreferenciasOrden
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ACCION_BAJAR
import com.copernicotv.core.orden.ModoOrden
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E3-T8, fase 1: reordena L1 desde la UI y deja la evidencia que leerá la
 * fase 2 después de que el proceso haya sido terminado y relanzado.
 *
 * Esta prueba no usa `ActivityScenario.recreate()` como evidencia de reinicio:
 * la recreación de proceso ocurre entre dos invocaciones de instrumentación
 * separadas por `am force-stop` (ver `.github/scripts/e3-process-restart.sh`).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class E3T8ProcesoFase1Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var preferencias: PreferenciasOrden

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var semilla: SemillaE3T8

    @Before
    fun preparar() {
        hiltRule.inject()
        borrarMarca(contexto)
        runBlocking {
            db.clearAllTables()
            preferencias.barrerHuerfanas(emptySet())
            semilla = sembrarE3T8(db, preferencias)
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.esperarListaE3T8(NOMBRE_LISTA_L1)
        composeRule.esperarTextoE3T8(0, "C1")
    }

    @Test
    fun reordenaL1YdejaEvidenciaParaElNuevoProceso() {
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        composeRule.esperarTagE3T8(TAG_ACCION_BAJAR)
        composeRule.onNodeWithTag(TAG_ACCION_BAJAR).performClick()

        composeRule.esperarTextoE3T8(0, "C2")
        composeRule.esperarTextoE3T8(1, "C1")
        composeRule.esperarTextoE3T8(2, "C3")
        composeRule.esperarModoSeleccionadoE3T8(ModoOrden.MANUAL)

        val estado = runBlocking { leerEstadoE3T8(db, preferencias, semilla) }
        afirmarEstadoE3T8(estado, semilla)

        escribirMarca(contexto, semilla)
        val marca = leerMarca(contexto)
        assertEquals(identidadProceso(), marca.identidad)
        assertEquals(semilla.lista1Id, marca.lista1Id)
        assertEquals(semilla.lista2Id, marca.lista2Id)
        assertEquals(semilla.canalIds, marca.canalIds)
        assertEquals(semilla.coleccionId, marca.coleccionId)
    }
}
