package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ABRIR_DOCUMENTO
import com.copernicotv.app.ui.TAG_ACCIONES_CANAL
import com.copernicotv.app.ui.TAG_ACCION_REPRODUCIR
import com.copernicotv.app.ui.TAG_REPRODUCTOR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLVER
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Foco del shell de TV de E7-T1.
 *
 * Recorrido real del catálogo solo con D-pad: Centrar sobre un canal abre el
 * diálogo de acciones, cuya primera acción (Reproducir) toma el foco inicial
 * y abre el reproductor; cada superficie muestra exactamente un foco visible
 * y al regresar del reproductor el catálogo restaura el foco en su primer
 * control. Además produce capturas reproducibles para la revisión visual P2
 * de DC-11, que CI recoge con `adb pull`.
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

        // Entrada al canal solo con D-pad: el foco se pide programáticamente
        // (el canario E1-C fija el patrón) y Centrar abre el diálogo de
        // acciones del canal, superficie propia del catálogo.
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).requestFocus()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0)
            .performKeyInput { pressKey(Key.DirectionCenter) }

        // Diálogo de acciones abierto: su primera acción (Reproducir) toma
        // el foco al componerse (comportamiento E7-T1 que esta prueba fija).
        // Detrás queda el foco del catálogo en su propio dueño de foco, por
        // eso la superficie modal no se verifica con la cuenta global, que
        // solo aplica a superficies de ventana completa (catálogo, TV root).
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(TAG_ACCIONES_CANAL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.esperarHasta {
            runCatching {
                composeRule.onNodeWithTag(TAG_ACCION_REPRODUCIR).assertIsFocused()
            }.isSuccess
        }
        // Reproducir abre el reproductor: la shell restituye un foco único
        // y no deja la pantalla sin foco al cambiar de pantalla.
        composeRule.onNodeWithTag(TAG_ACCION_REPRODUCIR)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(TAG_REPRODUCTOR).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.esperarHasta { composeRule.contarConFoco() == 1 }
        composeRule.onNodeWithTag(TAG_REPRODUCTOR).assertIsDisplayed()
        composeRule.focoUnico()

        // Regreso: el catálogo se recompone y restaura el foco a su primer
        // control (TAG_ABRIR_DOCUMENTO recibe el foco inicial del shell). La
        // salida es por el control visible del reproductor, el patrón de las
        // pruebas E6: en el emulador TV el back del sistema no llega de forma
        // confiable a la composición. Se espera acoplada al estado real del
        // catálogo: un conteo global puede satisfacerse antes de la
        // recomposición con el foco que aún conserva la pantalla anterior.
        composeRule.esperarTag(TAG_REPRODUCTOR_VOLVER)
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLVER).performClick()
        composeRule.esperarHasta {
            runCatching {
                composeRule.onNodeWithTag(TAG_ABRIR_DOCUMENTO).assertExists()
                composeRule.onNodeWithTag(TAG_ABRIR_DOCUMENTO).assertIsFocused()
            }.isSuccess
        }
        composeRule.focoUnico()
    }

    @Test
    fun lasCapturasReproduciblesCubrenCatalogoYSuRecorridoConFoco() {
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 0).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).requestFocus()
        composeRule.focoUnico()
        val capturaCatalogo = composeRule.capturarP2("e7t1-catalogo-canal0")

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(TAG_ACCIONES_CANAL).fetchSemanticsNodes().isNotEmpty()
        }
        composeRule.onNodeWithTag(TAG_ACCION_REPRODUCIR).requestFocus()
        composeRule.onNodeWithTag(TAG_ACCION_REPRODUCIR)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(TAG_REPRODUCTOR).fetchSemanticsNodes().isNotEmpty()
        }
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
