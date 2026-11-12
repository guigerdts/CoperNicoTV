package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_ACCESO_RAPIDO
import com.copernicotv.app.ui.TAG_BORRAR_FAVORITOS
import com.copernicotv.app.ui.TAG_CONFIRMAR_BORRAR
import com.copernicotv.app.ui.TAG_PRIVACIDAD
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
 * E7-T6 (S47b/S47c), fase 2: se ejecuta después de `am force-stop` y de una
 * nueva invocación de instrumentación. Comprueba que el borrado del Historial
 * persistió tras el proceso nuevo, que el Favorito se conservó, y después
 * borra los Favoritos desde la UI; el Historial permanece vacío.
 *
 * No hay limpieza de Room en `@Before`: los datos son los que quedaron en
 * disco al terminar el proceso anterior. La fase no abre servidor de medios:
 * el borrado es una operación local y no debe depender de la red (S47d).
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class S47ProcesoFase2Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var marca: MarcaS47

    @Before
    fun preparar() {
        hiltRule.inject()
        marca = leerMarcaS47(contexto)
        assertNotEquals(
            "La fase 2 se ejecutó en el mismo proceso que la fase 1",
            marca.identidad,
            identidadProceso(),
        )
        composeRule.esperarTag(TAG_PRIVACIDAD)
    }

    @Test
    fun elBorradoPersisteYElFavoritoSeConservaTrasNuevoProceso() {
        assertEquals(1, marca.favoritos)
        assertEquals(0, marca.historial)

        // S47c: el borrado del Historial sobrevivió al reinicio real.
        runBlocking {
            assertEquals(0, db.historialDao().contar())
            assertEquals(1, db.favoritoDao().contar())
        }
        // El acceso rápido no existe: su única fila de Historial fue borrada.
        composeRule.esperarTagAusente(TAG_ACCESO_RAPIDO)

        // S47b desde la UI en el proceso nuevo: los Favoritos también se
        // borran de forma independiente.
        composeRule.onNodeWithTag(TAG_PRIVACIDAD).performClick()
        composeRule.esperarTag(TAG_BORRAR_FAVORITOS)
        composeRule.onNodeWithTag(TAG_BORRAR_FAVORITOS).performClick()
        composeRule.esperarTag(TAG_CONFIRMAR_BORRAR)
        composeRule.onNodeWithTag(TAG_CONFIRMAR_BORRAR).performClick()

        composeRule.esperarHasta {
            runBlocking { db.favoritoDao().contar() == 0 }
        }
        runBlocking {
            assertEquals(0, db.favoritoDao().contar())
            assertEquals(0, db.historialDao().contar())
        }
    }
}
