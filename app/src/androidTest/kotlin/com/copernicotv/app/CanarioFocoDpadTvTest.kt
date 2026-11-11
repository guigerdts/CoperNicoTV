package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ABRIR_DOCUMENTO
import com.copernicotv.app.ui.TAG_IMPORTAR
import com.copernicotv.app.ui.TAG_IMPORTAR_URL
import com.copernicotv.app.ui.TipografiaTv
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Canarios de foco y D-pad de E7-T2 sobre el catálogo TV.
 *
 * Recorrido puro de D-pad: importar el fixture con Centrar, caminar con
 * Abajo hasta el primer Canal sin pedir foco programáticamente, recorrer la
 * lista completa (Abajo hasta el último y Arriba de regreso) verificando un
 * solo foco visible en cada paso y los límites de foco de DC-11 (área ≥
 * 56 × 56 dp y separación ≥ 8 dp entre objetivos), con capturas
 * reproducibles para la revisión visual humana a 10 pies, que sigue
 * pendiente y no se declara cerrada por CI.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class CanarioFocoDpadTvTest {
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
    fun importaYRecorreElCatalogoSoloConDpadCumpliendoLimitesDeFoco() {
        // Importación del fixture con el recorrido real del espectador: el
        // foco inicial cae en el primer control y se baja con D-pad hasta el
        // disparador de importación.
        composeRule.onNodeWithTag(TAG_ABRIR_DOCUMENTO).assertIsFocused()
        composeRule.focoUnico()
        composeRule.onNodeWithTag(TAG_ABRIR_DOCUMENTO)
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(TAG_IMPORTAR_URL).assertIsFocused()
        composeRule.onNodeWithTag(TAG_IMPORTAR_URL)
            .performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.onNodeWithTag(TAG_IMPORTAR).assertIsFocused()
        composeRule.onNodeWithTag(TAG_IMPORTAR)
            .performKeyInput { pressKey(Key.DirectionCenter) }
        composeRule.esperarHasta {
            composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + 2)
                .fetchSemanticsNodes()
                .isNotEmpty()
        }
        composeRule.focoUnico()

        // Camino puro de D-pad hasta el primer Canal: Abajo hasta que el
        // foco caiga en canal-0, sin requestFocus en el trayecto.
        var presiones = 0
        while (!canalEnfocado(0) && presiones < MAXIMO_PRESIONES) {
            presionarAbajo()
            composeRule.focoUnico()
            presiones++
        }
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()
        val densidad = densidadDelDispositivo()
        val limitesCanal0 = limitesCanal(0)
        assertTrue(
            "Área de foco de canal-0 bajo el mínimo de DC-11 (56 dp): " +
                "${limitesCanal0.height / densidad} dp de alto, " +
                "${limitesCanal0.width / densidad} dp de ancho",
            limitesCanal0.height / densidad >= 56f && limitesCanal0.width / densidad >= 56f,
        )
        composeRule.capturarP2("e7t2-catalogo-canal0")

        // Recorrido completo de la lista: Abajo hasta el último canal y
        // Arriba de regreso, un solo foco en cada paso y separación ≥ 8 dp.
        presionarAbajo()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertIsFocused()
        composeRule.focoUnico()
        val limitesCanal1 = limitesCanal(1)
        val separacion = (limitesCanal1.top - limitesCanal0.bottom) / densidad
        assertTrue(
            "Separación entre canal-0 y canal-1 bajo el mínimo de DC-11 " +
                "(8 dp): $separacion dp",
            separacion >= 8f,
        )
        presionarAbajo()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 2).assertIsFocused()
        composeRule.focoUnico()
        presionarArriba()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 1).assertIsFocused()
        composeRule.focoUnico()
        presionarArriba()
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).assertIsFocused()
        composeRule.focoUnico()
        composeRule.capturarP2("e7t2-catalogo-regreso")
    }

    @Test
    fun laTipografiaTvCumpleLosUmbralesDeTextoDeDc11() {
        // Los roles tipográficos usados por las pantallas compartidas se
        // corresponden con los mínimos de DC-11 en TV: encabezado de sección
        // ≥ 32 sp, título de elemento ≥ 24 sp, cuerpo y texto crítico ≥ 18 sp
        // y metadatos ≥ 16 sp.
        assertTrue(
            "headlineMedium (encabezado de sección) bajo el mínimo de 32 sp: " +
                "${TipografiaTv.headlineMedium.fontSize.value} sp",
            TipografiaTv.headlineMedium.fontSize.value >= 32f,
        )
        assertTrue(
            "titleLarge (título de elemento) bajo el mínimo de 24 sp: " +
                "${TipografiaTv.titleLarge.fontSize.value} sp",
            TipografiaTv.titleLarge.fontSize.value >= 24f,
        )
        assertTrue(
            "titleMedium (título de elemento) bajo el mínimo de 24 sp: " +
                "${TipografiaTv.titleMedium.fontSize.value} sp",
            TipografiaTv.titleMedium.fontSize.value >= 24f,
        )
        assertTrue(
            "titleSmall (texto crítico) bajo el mínimo de 18 sp: " +
                "${TipografiaTv.titleSmall.fontSize.value} sp",
            TipografiaTv.titleSmall.fontSize.value >= 18f,
        )
        assertTrue(
            "bodyLarge (cuerpo) bajo el mínimo de 18 sp: " +
                "${TipografiaTv.bodyLarge.fontSize.value} sp",
            TipografiaTv.bodyLarge.fontSize.value >= 18f,
        )
        assertTrue(
            "bodyMedium (cuerpo) bajo el mínimo de 18 sp: " +
                "${TipografiaTv.bodyMedium.fontSize.value} sp",
            TipografiaTv.bodyMedium.fontSize.value >= 18f,
        )
        assertTrue(
            "bodySmall (metadatos) bajo el mínimo de 16 sp: " +
                "${TipografiaTv.bodySmall.fontSize.value} sp",
            TipografiaTv.bodySmall.fontSize.value >= 16f,
        )
        assertTrue(
            "labelSmall (metadatos) bajo el mínimo de 16 sp: " +
                "${TipografiaTv.labelSmall.fontSize.value} sp",
            TipografiaTv.labelSmall.fontSize.value >= 16f,
        )
        assertTrue(
            "labelMedium (metadatos) bajo el mínimo de 16 sp: " +
                "${TipografiaTv.labelMedium.fontSize.value} sp",
            TipografiaTv.labelMedium.fontSize.value >= 16f,
        )
    }

    /** Baja el foco con la tecla D-pad Abajo y espera la recomposición. */
    private fun presionarAbajo() {
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionDown) }
        composeRule.waitForIdle()
    }

    /** Sube el foco con la tecla D-pad Arriba y espera la recomposición. */
    private fun presionarArriba() {
        composeRule.onRoot().performKeyInput { pressKey(Key.DirectionUp) }
        composeRule.waitForIdle()
    }

    /** Indica si el canal de la posición dada tiene el foco activo. */
    private fun canalEnfocado(posicion: Int): Boolean =
        runCatching {
            composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + posicion).assertIsFocused()
        }.isSuccess

    /** Límites del nodo del canal de la posición dada, en píxeles. */
    private fun limitesCanal(posicion: Int) =
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + posicion)
            .fetchSemanticsNode()
            .boundsInRoot

    /** Densidad de la pantalla del dispositivo de prueba. */
    private fun densidadDelDispositivo(): Float =
        InstrumentationRegistry.getInstrumentation().targetContext
            .resources.displayMetrics.density

    private companion object {
        const val MAXIMO_PRESIONES = 12
    }
}
