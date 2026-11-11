package com.copernicotv.app

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollToIndex
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_BUSCAR
import com.copernicotv.app.ui.TAG_FILTRO_TODOS
import com.copernicotv.app.ui.TAG_NUEVO_CANAL
import com.copernicotv.app.ui.TAG_RESULTADOS
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * Medición P2 de DC-11 táctil en móvil (E7-T3): el área táctil de los
 * controles clave del catálogo mide al menos 48 × 48 dp y la separación
 * entre filas contiguas es de al menos 8 dp.
 *
 * La medición usa `TouchBoundsInRoot` (píxeles, coordenadas de la raíz) y
 * compara contra los mínimos convertidos con la densidad real del emulador;
 * cada valor queda en el log con prefijo `DC11_TOUCH` / `DC11_SEPARACION`
 * para registrarlo en la checklist P2 de E7-T7. La revisión visual humana
 * de las capturas sigue pendiente y no se declara cerrada.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class MedicionTouchMovilTest {
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
    fun elAreaTactilDelCatalogoMovilCumpleDc11() {
        val resumen = runBlocking { repositorio.importarFixture() }
        assertNull("La importación reportó el código ${resumen.error}", resumen.error)
        assertEquals("Canales importados", CANALES_ESPERADOS, resumen.canales)
        esperarCanales()
        composeRule.onNodeWithTag(TAG_RESULTADOS).performScrollToIndex(0)

        val densidad =
            InstrumentationRegistry.getInstrumentation().targetContext
                .resources.displayMetrics.density

        val limitesCanal0 = limitesTactiles(PREFIJO_TAG_CANAL + 0, densidad)
        val limitesCanal1 = limitesTactiles(PREFIJO_TAG_CANAL + 1, densidad)
        limitesTactiles(TAG_BUSCAR, densidad)
        limitesTactiles(TAG_FILTRO_TODOS, densidad)
        limitesTactiles(TAG_NUEVO_CANAL, densidad)

        // La lista del catálogo apila las filas hacia abajo: la separación
        // táctil entre canal-0 y canal-1 es el hueco entre sus límites en la
        // misma captura de árbol.
        val separacion = limitesCanal1.top - limitesCanal0.bottom
        println("DC11_SEPARACION: canal-0/canal-1 = ${separacion / densidad} dp")
        assertTrue(
            "Separación táctil canal-0↔canal-1 = ${separacion / densidad} dp (< 8 dp)",
            separacion >= MINIMA_SEPARACION_DP * densidad - TOLERANCIA_PX,
        )

        composeRule.capturarP2("e7t3-catalogo-tactil-movil")
    }

    /**
     * Devuelve los límites táctiles del nodo con [tag] en coordenadas de la
     * raíz, registra la medición en el log y verifica el mínimo de DC-11.
     */
    private fun limitesTactiles(
        tag: String,
        densidad: Float,
    ): Rect {
        val nodos = composeRule.onNodeWithTag(tag).fetchSemanticsNodes()
        val nodo =
            nodos.firstOrNull()
                ?: throw AssertionError("Sin nodo con tag $tag para medir DC-11\nÁrbol:\n${arbol()}")
        val limites =
            nodo.config.getOrNull(SemanticsProperties.TouchBoundsInRoot)
                ?: throw AssertionError("El nodo $tag no reporta TouchBoundsInRoot\nÁrbol:\n${arbol()}")
        println(
            "DC11_TOUCH: $tag = ${limites.width}x${limites.height}px " +
                "(${limites.width / densidad} x ${limites.height / densidad} dp)",
        )
        val minimo = MINIMO_TACTIL_DP * densidad
        assertTrue(
            "Área táctil de $tag = ${limites.width / densidad} × ${limites.height / densidad} dp (< 48 × 48 dp)",
            limites.width >= minimo && limites.height >= minimo,
        )
        return limites
    }

    /**
     * Espera a que el catálogo tenga tres canales; si el tiempo se agota el
     * fallo incluye el árbol de semántica completo.
     */
    private fun esperarCanales() {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    runCatching {
                        composeRule.onNodeWithTag(TAG_RESULTADOS).performScrollToIndex(CANALES_ESPERADOS - 1)
                        composeRule.onAllNodesWithTag(PREFIJO_TAG_CANAL + (CANALES_ESPERADOS - 1))
                            .fetchSemanticsNodes()
                            .isNotEmpty()
                    }.isSuccess
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError(
                "No aparecieron los tres canales tras importar.\nÁrbol de semántica:\n${arbol()}",
                fallo,
            )
        }
    }

    private fun arbol(): String =
        runCatching { composeRule.onRoot().printToString() }
            .getOrElse { "no disponible: ${it::class.simpleName}" }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
        const val CANALES_ESPERADOS = 3
        const val MINIMO_TACTIL_DP = 48f
        const val MINIMA_SEPARACION_DP = 8f
        const val TOLERANCIA_PX = 0.01f
    }
}
