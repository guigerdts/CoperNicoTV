package com.copernicotv.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.HistorialRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ACCESO_RAPIDO
import com.copernicotv.app.ui.TAG_ACCION_FAVORITO
import com.copernicotv.app.ui.TAG_BORRAR_FAVORITOS
import com.copernicotv.app.ui.TAG_BORRAR_HISTORIAL
import com.copernicotv.app.ui.TAG_CONFIRMAR_BORRAR
import com.copernicotv.app.ui.TAG_PRIVACIDAD
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E7-T6 (S47a/S47b) en un emulador: los dos borrados del diálogo de
 * Privacidad desde la UI real con conservación cruzada. Borrar el Historial
 * deja el Favorito intacto y borrar los Favoritos deja el Historial intacto;
 * la persistencia del borrado tras un proceso nuevo la fija el arnés
 * `S47ProcesoFase1/Fase2`.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class S47PrivacidadTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    @Inject
    lateinit var historial: HistorialRepositorio

    @Before
    fun preparar() {
        hiltRule.inject()
        limpiarBaseS47(db)
    }

    @Test
    fun losDosBorradosSonIndependientesYSeConservanCruzados() {
        sembrarCanalS47(catalogo)
        composeRule.esperarTag(PREFIJO_TAG_CANAL + 0)
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        composeRule.esperarTag(TAG_ACCION_FAVORITO)
        composeRule.onNodeWithTag(TAG_ACCION_FAVORITO).performClick()
        composeRule.esperarHasta {
            runBlocking { db.favoritoDao().contar() == 1 }
        }
        runBlocking {
            val canal = db.canalDao().todos().single()
            historial.registrar(canal.id)
        }
        composeRule.esperarHasta {
            runBlocking { db.historialDao().contar() == 1 }
        }

        // S47a: borrar el Historial conserva el Favorito.
        composeRule.onNodeWithTag(TAG_PRIVACIDAD).performScrollTo().performClick()
        composeRule.esperarTag(TAG_BORRAR_HISTORIAL)
        composeRule.onNodeWithTag(TAG_BORRAR_HISTORIAL).performClick()
        composeRule.esperarTag(TAG_CONFIRMAR_BORRAR)
        composeRule.onNodeWithTag(TAG_CONFIRMAR_BORRAR).performClick()
        composeRule.esperarHasta {
            runBlocking { db.historialDao().contar() == 0 }
        }
        runBlocking {
            assertEquals(0, db.historialDao().contar())
            assertEquals(1, db.favoritoDao().contar())
        }
        composeRule.esperarTagAusente(TAG_ACCESO_RAPIDO)

        // S47b: borrar los Favoritos conserva el Historial; el Historial se
        // vuelve a registrar para que la conservación sea observable.
        runBlocking {
            val canal = db.canalDao().todos().single()
            historial.registrar(canal.id)
        }
        composeRule.esperarHasta {
            runBlocking { db.historialDao().contar() == 1 }
        }
        composeRule.onNodeWithTag(TAG_PRIVACIDAD).performScrollTo().performClick()
        composeRule.esperarTag(TAG_BORRAR_FAVORITOS)
        composeRule.onNodeWithTag(TAG_BORRAR_FAVORITOS).performClick()
        composeRule.esperarTag(TAG_CONFIRMAR_BORRAR)
        composeRule.onNodeWithTag(TAG_CONFIRMAR_BORRAR).performClick()
        composeRule.esperarHasta {
            runBlocking { db.favoritoDao().contar() == 0 }
        }
        runBlocking {
            assertEquals(0, db.favoritoDao().contar())
            assertEquals(1, db.historialDao().contar())
        }
    }
}
