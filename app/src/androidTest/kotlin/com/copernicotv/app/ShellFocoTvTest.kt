package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import androidx.test.espresso.Espresso.pressBack
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ABRIR_DOCUMENTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Foco del shell de TV de E7-T1.
 *
 * Recorrido solo con D-pad (Centrar y Atrás): en cada paso hay exactamente un
 * foco visible y al regresar del reproductor el catálogo restaura el foco en
 * su primer control. Además produce capturas reproducibles para la revisión
 * visual P2 de DC-11, que CI recoge con `adb pull`.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ShellFocoTvTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
        sembrarCanal(catalogo, "https://ejemplo.invalid/stream.ts")
    }

    @Test
    fun elRecorridoDpadMantieneUnSoloFocoYLoRestauraAlRegresar() {
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 0).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.focoUnico()

        // Entrada al canal con D-pad: el foco se pide programáticamente (el
        // canario E1-C fija el patrón) y Centrar abre el reproductor.
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).requestFocus()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0)
            .performKeyInput { pressKey(Key.DirectionCenter) }

        // Reproductor abierto: la shell restituye un foco único, sin dejar la
        // pantalla sin foco al cambiar de pantalla.
        composeRule.esperarHasta { composeRule.contarConFoco() == 1 }
        composeRule.onNodeWithTag(TAG_REPRODUCTOR).assertIsDisplayed()
        composeRule.focoUnico()

        // Regreso: el catálogo se recompone y restaura el foco al primer
        // control (TAG_ABRIR_DOCUMENTO recibe el foco inicial del shell).
        pressBack()
        composeRule.esperarHasta { composeRule.contarConFoco() == 1 }
        composeRule.onNodeWithTag(TAG_ABRIR_DOCUMENTO).assertIsFocused()
        composeRule.focoUnico()
    }

    @Test
    fun lasCapturasReproduciblesCubrenCatalogoYReproductorConFoco() {
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 0).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).requestFocus()
        composeRule.focoUnico()
        val capturaCatalogo = composeRule.capturarP2("e7t1-catalogo-canal0")

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.esperarHasta { composeRule.contarConFoco() == 1 }
        val capturaReproductor = composeRule.capturarP2("e7t1-reproductor-arranque")

        if (!capturaCatalogo.exists() || capturaCatalogo.length() <= 0L) {
            throw AssertionError("La captura del catálogo no quedó en $capturaCatalogo")
        }
        if (!capturaReproductor.exists() || capturaReproductor.length() <= 0L) {
            throw AssertionError("La captura del reproductor no quedó en $capturaReproductor")
        }
    }
}
