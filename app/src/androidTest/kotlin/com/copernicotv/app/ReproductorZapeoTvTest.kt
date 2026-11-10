package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.ReproductorUiState
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SIGUIENTE
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLUMEN_BAJAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLUMEN_SUBIR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLUMEN_VALOR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T6 en el emulador de Android TV: el zapeo y el volumen se manejan con el
 * D-pad. El foco recorre los botones nuevos de la fila primaria y de la fila
 * de volumen, y el botón central activa el zapeo sin volver al Catálogo. El
 * comportamiento de revelado con Up/Down de E6-T2 no cambia.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ReproductorZapeoTvTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private lateinit var servidor: ServidorMedios

    private lateinit var ids: Map<String, Long>

    @Before
    fun preparar() {
        hiltRule.inject()
        runBlocking { db.clearAllTables() }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        FixturesF15.verificar(assets)
        servidor = ServidorMedios(assets).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun elDpadZapeaAlCanalSiguienteConElFocoEnLosControles() {
        sembrarDosCanales()
        composeRule.abrirReproductor()
        composeRule.esperarFase("Reproduciendo")
        esperarCanal(ids.getValue("Canal A"))
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFocoEnAlternar()

        // De play/pausa a la derecha está el zapeo siguiente; el centro activa.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ALTERNAR).performKeyInput {
            pressKey(Key.DirectionRight)
        }
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SIGUIENTE)
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performKeyInput {
            pressKey(Key.DirectionCenter)
        }

        esperarCanal(ids.getValue("Canal B"))
        composeRule.esperarFase("Reproduciendo")
    }

    @Test
    fun elDpadAjustaElVolumenDesdeElFocoDeLosControles() {
        sembrarDosCanales()
        composeRule.abrirReproductor()
        composeRule.esperarFase("Reproduciendo")
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFocoEnAlternar()
        val viewModel = composeRule.viewModelReproductor()
        assertEquals(1.0f, volumenMotorEn(viewModel))

        // Subir a la fila de volumen: vive encima de la primaria para que
        // bajar desde play conserve el recorrido a las pistas validado en
        // E6-T3 (L72).
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ALTERNAR).performKeyInput {
            pressKey(Key.DirectionUp)
        }
        composeRule.esperarHasta {
            enfocadoEn(TAG_REPRODUCTOR_VOLUMEN_BAJAR) || enfocadoEn(TAG_REPRODUCTOR_VOLUMEN_SUBIR)
        }
        if (enfocadoEn(TAG_REPRODUCTOR_VOLUMEN_SUBIR)) {
            composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLUMEN_SUBIR).performKeyInput {
                pressKey(Key.DirectionLeft)
            }
            composeRule.esperarFocoEn(TAG_REPRODUCTOR_VOLUMEN_BAJAR)
        }

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLUMEN_BAJAR).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.esperarHasta { volumenMotorEn(viewModel) == 0.9f }
        composeRule.esperarTexto(TAG_REPRODUCTOR_VOLUMEN_VALOR, "Volumen: 90%")
    }

    private fun enfocadoEn(tag: String): Boolean = runCatching { composeRule.onNodeWithTag(tag).assertIsFocused() }.isSuccess

    private fun esperarCanal(canalId: Long) {
        composeRule.esperarHasta {
            (composeRule.viewModelReproductor().estado.value as? ReproductorUiState.Listo)?.canalId == canalId
        }
    }

    private fun sembrarDosCanales() {
        ids =
            runBlocking {
                val texto =
                    "#EXTM3U\n" +
                        "#EXTINF:-1 tvg-id=\"e6zapeotv0\",Canal A\n${FixturesF15.url(servidor, FixturesF15.MP4)}\n" +
                        "#EXTINF:-1 tvg-id=\"e6zapeotv1\",Canal B\n${FixturesF15.url(servidor, FixturesF15.MP4)}\n"
                catalogo.importar(texto, "Fuente Zapeo TV", "http://example.invalid/zapeo-tv.m3u")
                db.canalDao().todos().associate { it.nombre.orEmpty() to it.id }
            }
    }
}
