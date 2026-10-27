package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.compose.ui.test.requestFocus
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_IMPORTAR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Canario móvil de E1-C: importar un fixture M3U, abrir el catálogo y navegar
 * con D-pad.
 *
 * La importación pasa por el camino real (parser canario → identidad → HMAC →
 * cifrado → Room) y el catálogo se lee del `Flow` de la base, no de memoria.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class CanarioMovilTest {
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

    /**
     * Aísla el camino de persistencia del camino de UI.
     *
     * Si la importación falla aquí, el fallo está en el repositorio o en el
     * Keystore real; si pasa, el problema está en la observación del catálogo
     * o en la composición. El canario de UI no distingue ambos casos.
     */
    @Test
    fun elRepositorioImportaElFixtureConKeystoreReal() {
        val resumen = runBlocking { repositorio.importarFixture() }
        assertNull("La importación reportó el código ${resumen.error}", resumen.error)
        assertNotNull("La importación no devolvió lista", resumen.listaId)
        assertEquals("Canales importados", 3, resumen.canales)
    }

    @Test
    fun importaYRecorreElCatalogoConDpad() {
        composeRule.onNodeWithTag(TAG_IMPORTAR).requestFocus()
        composeRule.onNodeWithTag(TAG_IMPORTAR).performClick()

        esperarCanales()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertTextEquals("Canario Uno")
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertTextEquals("Canario Dos")
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 2).assertTextEquals("Canario Tres")

        composeRule.onNodeWithTag(TAG_IMPORTAR).requestFocus()
        composeRule.onNodeWithTag(TAG_IMPORTAR).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertIsFocused()
    }

    /**
     * Espera a que el catálogo tenga tres canales.
     *
     * Si el tiempo se agota, el fallo incluye el árbol de semántica completo:
     * así se distingue "no se importó" (mensaje de error visible) de "se
     * importó pero la UI no lo observa" (canales ausentes sin mensaje).
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
