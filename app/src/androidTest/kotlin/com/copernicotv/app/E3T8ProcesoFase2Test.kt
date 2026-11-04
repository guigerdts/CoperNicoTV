package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.PreferenciasOrden
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.orden.ModoOrden
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E3-T8, fase 2: se ejecuta después de `am force-stop` y de una nueva
 * invocación de instrumentación. Lee la marca de la fase 1, comprueba que la
 * identidad del proceso cambió y verifica el orden visible y persistido.
 *
 * No hay limpieza de Room ni de DataStore en `@Before`: los datos deben ser los
 * que quedaron en disco al terminar el proceso anterior.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class E3T8ProcesoFase2Test {
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

    private lateinit var marca: MarcaProceso

    @Before
    fun preparar() {
        hiltRule.inject()
        marca = leerMarca(contexto)
        assertNotEquals(
            "La fase 2 se ejecutó en el mismo proceso que la fase 1",
            marca.identidad,
            identidadProceso(),
        )
    }

    @Test
    fun trasNuevoProcesoLaUiYLaBaseConservanElOrdenYLasPreferencias() {
        val semilla = marca.semilla()
        composeRule.esperarListaE3T8(NOMBRE_LISTA_L1)
        composeRule.esperarTextoE3T8(0, "C2")
        composeRule.esperarTextoE3T8(1, "C1")
        composeRule.esperarTextoE3T8(2, "C3")
        composeRule.esperarModoSeleccionadoE3T8(ModoOrden.MANUAL)

        val estado = runBlocking { leerEstadoE3T8(db, preferencias, semilla) }
        afirmarEstadoE3T8(estado, semilla)

        composeRule.seleccionarListaE3T8(semilla.lista2Id, NOMBRE_LISTA_L2)
        composeRule.esperarModoSeleccionadoE3T8(ModoOrden.NOMBRE)
        composeRule.esperarTextoE3T8(0, "C3")
        composeRule.esperarTextoE3T8(1, "C2")
        composeRule.esperarTextoE3T8(2, "C1")
    }
}
