package com.copernicotv.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_RESULTADOS
import com.copernicotv.app.ui.TAG_TEMA
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Canario de tema (S43, E7-T4): el toggle alterna claro/oscuro y el estado no
 * se pierde — los tres canales del fixture siguen visibles tras alternar y
 * tras recrear la Activity (la preferencia vive en DataStore).
 *
 * La importación pasa por el camino real (repositorio → Keystore → Room) y la
 * etiqueta del toggle muestra el tema actual.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class TemaUiTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var repositorio: CatalogoRepositorio

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @Test
    fun elTemaClaroSeMantieneTrasRecrearSinPerderEstado() {
        val resumen = runBlocking { repositorio.importarFixture() }
        assertNull("La importación reportó el código ${resumen.error}", resumen.error)
        assertEquals("Canales importados", 3, resumen.canales)

        esperarCanales()
        // Tema por defecto: oscuro, el comportamiento previo a E7-T4.
        composeRule.waitUntil(TIEMPO_ESPERA) {
            composeRule.onAllNodesWithText("Tema: oscuro").fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(TAG_TEMA).performScrollTo().performClick()

        composeRule.waitUntil(TIEMPO_ESPERA) {
            composeRule.onAllNodesWithText("Tema: claro").fetchSemanticsNodes().isNotEmpty()
        }
        // El estado no se pierde al alternar: los tres canales siguen.
        esperarCanales()

        // La preferencia vive en DataStore: sobrevive a la recreación.
        composeRule.activityRule.scenario.recreate()
        composeRule.waitUntil(TIEMPO_ESPERA) {
            composeRule.onAllNodesWithText("Tema: claro").fetchSemanticsNodes().isNotEmpty()
        }
        esperarCanales()
    }

    /**
     * Espera a que el catálogo tenga tres canales; si el tiempo se agota, el
     * fallo incluye el árbol de semántica completo.
     */
    private fun esperarCanales() {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    runCatching {
                        composeRule.onNodeWithTag(TAG_RESULTADOS).performScrollToIndex(2)
                        composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 2).fetchSemanticsNodes().isNotEmpty()
                    }.isSuccess
                }
            }.exceptionOrNull()
        if (fallo != null) {
            val arbol =
                runCatching { composeRule.onRoot().printToString() }
                    .getOrElse { "no disponible: ${it::class.simpleName}" }
            throw AssertionError(
                "No aparecieron los tres canales del fixture.\nÁrbol de semántica:\n$arbol",
                fallo,
            )
        }
    }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
    }
}
