package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.db.AppDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * S45a/S45b, fase 2: se ejecuta después de `am force-stop` y de una nueva
 * invocación de instrumentación. Comprueba que la secuencia visible de la
 * Colección conserva el orden elegido y coincide exactamente con
 * `ColeccionCanalDao.porColeccion`.
 *
 * No hay limpieza de Room en `@Before`: los datos son los que quedaron en
 * disco al terminar el proceso anterior.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class S45ProcesoFase2Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var marca: MarcaS45

    @Before
    fun preparar() {
        hiltRule.inject()
        marca = leerMarcaS45(contexto)
        assertNotEquals(
            "La fase 2 se ejecutó en el mismo proceso que la fase 1",
            marca.identidad,
            identidadProceso(),
        )
        composeRule.esperarListaE3T8(NOMBRE_LISTA_L1)
        composeRule.abrirColeccionS45(marca.coleccionId)
        composeRule.esperarFilasColeccionS45(marca.filas.size)
        composeRule.esperarOrdenColeccionS45(marca.filas)
    }

    @Test
    fun trasNuevoProcesoLaUiYLaBaseConservanElOrdenDeLaColeccion() {
        val filasDb = runBlocking { db.coleccionCanalDao().porColeccion(marca.coleccionId) }
        val nombresDb =
            runBlocking {
                filasDb.mapNotNull { fila -> db.canalDao().porId(fila.canalId)?.nombre }
            }

        assertEquals(marca.filas, filasDb.map { it.id })
        assertEquals(marca.nombres, nombresDb)
        assertEquals(listOf("C1", "C2", "C3"), nombresDb)
        assertEquals(
            "S45b: la UI no muestra el mismo orden que Room",
            filasDb.map { it.id },
            composeRule.filasVisiblesColeccionS45(),
        )
        assertEquals(marca.nombres, composeRule.nombresVisiblesColeccionS45())
    }
}
