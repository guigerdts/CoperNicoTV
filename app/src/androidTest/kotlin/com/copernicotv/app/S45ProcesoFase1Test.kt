package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
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
 * S45a/S45b, fase 1: reordena una Colección desde la UI y deja la marca que
 * leerá la fase 2 después de `am force-stop` y una nueva invocación de
 * instrumentación.
 *
 * La siembra es la de E3-T8 (`sembrarE3T8`): C2, C1, C3 en la Colección. El
 * test mueve la primera fila una posición hacia abajo y exige C1, C2, C3 tanto
 * en la UI como en Room (S45b local).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class S45ProcesoFase1Test {
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

    private var coleccionId: Long = 0L
    private var filasIniciales: List<Long> = emptyList()

    @Before
    fun preparar() {
        hiltRule.inject()
        borrarMarcaS45(contexto)
        val semilla =
            runBlocking {
                db.clearAllTables()
                preferencias.barrerHuerfanas(emptySet())
                sembrarE3T8(db, preferencias)
            }
        coleccionId = semilla.coleccionId
        filasIniciales = runBlocking { db.coleccionCanalDao().porColeccion(coleccionId).map { it.id } }

        composeRule.activityRule.scenario.recreate()
        composeRule.waitForIdle()
        composeRule.esperarListaE3T8(NOMBRE_LISTA_L1)
        composeRule.abrirColeccionS45(coleccionId)
        composeRule.esperarFilasColeccionS45(filasIniciales.size)
        composeRule.esperarOrdenColeccionS45(filasIniciales)
    }

    @Test
    fun reordenaLaColeccionDesdeLaUiYdejaEvidenciaParaElNuevoProceso() {
        assertEquals(3, filasIniciales.size)
        composeRule.bajarFilaColeccionS45(filasIniciales.first())

        val esperadas = listOf(filasIniciales[1], filasIniciales[0], filasIniciales[2])
        composeRule.esperarOrdenColeccionS45(esperadas)

        val filasDb = runBlocking { db.coleccionCanalDao().porColeccion(coleccionId) }
        val nombresDb =
            runBlocking {
                filasDb.mapNotNull { fila -> db.canalDao().porId(fila.canalId)?.nombre }
            }
        assertEquals(esperadas, filasDb.map { it.id })
        assertEquals(listOf("C1", "C2", "C3"), nombresDb)
        assertEquals(
            "S45b: la UI no muestra el mismo orden que Room",
            filasDb.map { it.id },
            composeRule.filasVisiblesColeccionS45(),
        )
        assertEquals(nombresDb, composeRule.nombresVisiblesColeccionS45())

        escribirMarcaS45(
            contexto,
            MarcaS45(
                identidad = identidadProceso(),
                coleccionId = coleccionId,
                filas = filasDb.map { it.id },
                nombres = nombresDb,
            ),
        )
        assertEquals(identidadProceso(), leerMarcaS45(contexto).identidad)
    }
}
