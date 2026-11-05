package com.copernicotv.app

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.HistorialRepositorio
import com.copernicotv.app.data.PreferenciasOrden
import com.copernicotv.app.data.db.AppDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * S11d, fase 1: registra reproducciones con fechas conocidas, comprueba el
 * acceso rápido y deja una marca con la identidad del proceso.
 *
 * La recreación de proceso no ocurre aquí: la fase 2 se ejecuta en otra
 * invocación de instrumentación después de `am force-stop`
 * (`.github/scripts/e3-process-restart.sh`). No se usa
 * `ActivityScenario.recreate()` como evidencia de reinicio.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class S11dProcesoFase1Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var historial: HistorialRepositorio

    @Inject
    lateinit var preferencias: PreferenciasOrden

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var semilla: SemillaS11d

    @Before
    fun preparar() {
        hiltRule.inject()
        borrarMarcaS11d(contexto)
        runBlocking {
            db.clearAllTables()
            preferencias.barrerHuerfanas(emptySet())
            semilla = sembrarHistorialS11d(db, historial)
        }
        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
    }

    @Test
    fun registraHistorialYDejaEvidenciaParaElNuevoProceso() {
        composeRule.esperarAccesoS11d(semilla)

        val estado = runBlocking { leerEstadoS11d(db, historial, semilla) }
        afirmarEstadoS11d(estado, semilla)
        composeRule.abrirSegundoS11d(semilla)

        escribirMarcaS11d(contexto, semilla)
        val marca = leerMarcaS11d(contexto)
        assertEquals(identidadProcesoS11d(), marca.identidad)
        assertEquals(semilla.listaId, marca.listaId)
        assertEquals(semilla.canalIds, marca.canalIds)
        assertEquals(semilla.nombres, marca.nombres)

        Log.i(
            "S11dProceso",
            "fase=1 identidad=${identidadProcesoS11d()} lista=${semilla.listaId} " +
                "canales=${semilla.ordenIds.joinToString(",")} orden=${semilla.ordenNombres.joinToString("|")}",
        )
    }
}
