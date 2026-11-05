package com.copernicotv.app

import android.content.Context
import android.util.Log
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.HistorialRepositorio
import com.copernicotv.app.data.db.AppDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * S11d, fase 2: corre en una invocación de instrumentación posterior a
 * `am force-stop`. Exige una identidad de proceso distinta y verifica que la UI
 * y Room conservan el Historial registrado por la fase 1.
 *
 * No hay limpieza de Room ni de DataStore en `@Before`: los datos deben ser los
 * que quedaron en disco al terminar el proceso anterior.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class S11dProcesoFase2Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var historial: HistorialRepositorio

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var marca: MarcaS11d

    @Before
    fun preparar() {
        hiltRule.inject()
        marca = leerMarcaS11d(contexto)
        assertNotEquals(
            "La fase 2 se ejecutó en el mismo proceso que la fase 1",
            marca.identidad,
            identidadProcesoS11d(),
        )
    }

    @Test
    fun trasNuevoProcesoLaUiYLaBaseConservanElHistorial() {
        val semilla = marca.semilla()
        esperarAccesoS11d(semilla)

        val estado = runBlocking { leerEstadoS11d(db, historial, semilla) }
        afirmarEstadoS11d(estado, semilla)
        abrirSegundoS11d(semilla)

        Log.i(
            "S11dProceso",
            "fase=2 identidad=${identidadProcesoS11d()} fase1=${marca.identidad} " +
                "conteo=${estado.conteo} canales=${estado.canales.joinToString(",")} " +
                "orden=${estado.nombres.joinToString("|")}",
        )
    }
}
