package com.copernicotv.app

import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.printToString
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_FUENTES
import com.copernicotv.app.ui.TAG_FUENTE_ACTUALIZACION
import com.copernicotv.app.ui.TAG_FUENTE_ACTUALIZAR
import com.copernicotv.app.ui.TAG_FUENTE_CARGA
import com.copernicotv.app.ui.TAG_FUENTE_DISPONIBILIDAD
import com.copernicotv.app.ui.tagFuente
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNull
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E4-T8/S7d: la pantalla de Fuentes muestra los tres indicadores sin
 * reutilizar uno por otro, y una Actualización correcta no cambia la
 * disponibilidad.
 */
@HiltAndroidTest
class FuentesUiTest {
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
    fun laPantallaDeFuentesMuestraLosTresIndicadoresYActualizaSinCambiarLaDisponibilidad() {
        val fuenteId =
            runBlocking {
                val resumen = repositorio.importarFixture()
                assertNull("La importación previa falló con ${resumen.error}", resumen.error)
                db.fuenteDao().todas().single().id
            }

        esperarTag(TAG_FUENTES)
        composeRule.onNodeWithTag(TAG_FUENTES).performScrollTo().performClick()

        val tagCarga = tagFuente(TAG_FUENTE_CARGA, fuenteId)
        val tagActualizacion = tagFuente(TAG_FUENTE_ACTUALIZACION, fuenteId)
        val tagDisponibilidad = tagFuente(TAG_FUENTE_DISPONIBILIDAD, fuenteId)
        val tagActualizar = tagFuente(TAG_FUENTE_ACTUALIZAR, fuenteId)

        esperarTag(tagDisponibilidad)
        composeRule.onNodeWithTag(tagCarga).assertTextEquals("Carga: en reposo")
        composeRule.onNodeWithTag(tagActualizacion).assertTextEquals("Actualización: sin intentos")
        composeRule.onNodeWithTag(tagDisponibilidad).assertTextEquals("Disponibilidad: disponible")

        composeRule.onNodeWithTag(tagActualizar).performScrollTo().performClick()

        esperarTexto(tagActualizacion, "Actualización: última correcta")
        composeRule.onNodeWithTag(tagCarga).assertTextEquals("Carga: en reposo")
        composeRule.onNodeWithTag(tagDisponibilidad).assertTextEquals("Disponibilidad: disponible")
    }

    private fun esperarTag(tag: String) {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    composeRule.onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty()
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError(
                "No apareció el nodo '$tag'.\nÁrbol de semántica:\n${arbol()}",
                fallo,
            )
        }
    }

    private fun esperarTexto(
        tag: String,
        texto: String,
    ) {
        val fallo =
            runCatching {
                composeRule.waitUntil(TIEMPO_ESPERA) {
                    runCatching {
                        composeRule.onNodeWithTag(tag).assertTextEquals(texto)
                    }.isSuccess
                }
            }.exceptionOrNull()
        if (fallo != null) {
            throw AssertionError(
                "El nodo '$tag' no mostró '$texto'.\nÁrbol de semántica:\n${arbol()}",
                fallo,
            )
        }
    }

    private fun arbol(): String =
        runCatching { composeRule.onRoot().printToString() }
            .getOrElse { "no disponible: ${it::class.simpleName}" }

    private companion object {
        const val TIEMPO_ESPERA = 15_000L
    }
}
