package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.requestFocus
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_IMPORTAR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
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

    @Before
    fun preparar() {
        hiltRule.inject()
        db.clearAllTables()
    }

    @Test
    fun importaYRecorreElCatalogoConDpad() {
        composeRule.onNodeWithTag(TAG_IMPORTAR).requestFocus()
        composeRule.onNodeWithTag(TAG_IMPORTAR).performClick()

        composeRule.waitUntil(TIEMPO_ESPERA) {
            composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 2).fetchSemanticsNodes().isNotEmpty()
        }

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertTextEquals("Canario Uno")
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertTextEquals("Canario Dos")
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 2).assertTextEquals("Canario Tres")

        composeRule.onNodeWithTag(TAG_IMPORTAR).requestFocus()
        composeRule.onNodeWithTag(TAG_IMPORTAR).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()

        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertIsFocused()
    }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
    }
}
