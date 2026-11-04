package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ABRIR_DOCUMENTO
import com.copernicotv.app.ui.TAG_IMPORTAR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Canario de Android TV de E1-C.
 *
 * No usa toque: el foco inicial cae en el primer control, se baja con
 * DPAD_DOWN hasta el disparador de importación, se activa con DPAD_CENTER y el
 * catálogo se recorre con DPAD_DOWN. Si el foco no existe o no se mueve, la
 * prueba falla.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class CanarioTvTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @Test
    fun importaYRecorreElCatalogoSoloConDpad() {
        // La pantalla tiene dos controles: el foco inicial cae en el primero y
        // se baja con D-pad hasta el disparador de importación. Así el canario
        // recorre la UI real y no una versión con un solo botón.
        composeRule.onNodeWithTag(TAG_ABRIR_DOCUMENTO).assertIsFocused()
        composeRule.onNodeWithTag(TAG_ABRIR_DOCUMENTO).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(TAG_IMPORTAR).assertIsFocused()
        composeRule.onNodeWithTag(TAG_IMPORTAR).performKeyInput { pressKey(Key.DirectionCenter) }

        esperarCanales()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertTextEquals("Canario Uno")
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertTextEquals("Canario Dos")
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 2).assertTextEquals("Canario Tres")

        // Tras importar, el botón deja de estar enfocado: se recupera el foco
        // para recorrer el catálogo solo con D-pad, sin toque.
        composeRule.onNodeWithTag(TAG_IMPORTAR).requestFocus()
        composeRule.onNodeWithTag(TAG_IMPORTAR).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertIsFocused()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 2).assertIsFocused()
    }

    /**
     * Espera a que el catálogo tenga tres canales.
     *
     * Si el tiempo se agota, el fallo incluye el árbol de semántica completo
     * para distinguir un fallo de importación de un fallo de observación.
     */
    private fun esperarCanales() {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 2).fetchSemanticsNodes().isNotEmpty()
                }
            }.exceptionOrNull()
        if (fallo != null) {
            val arbol =
                runCatching { composeRule.onRoot().printToString() }
                    .getOrElse { "no disponible: ${it::class.simpleName}" }
            throw AssertionError(
                "No aparecieron los tres canales tras importar.\nÁrbol de semántica:\n$arbol",
                fallo,
            )
        }
    }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
    }
}
