package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_REPRODUCTOR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_CONTROLES
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T2 en el emulador de Android TV: la pantalla se maneja solo con D-pad.
 *
 * Se abre el Canal desde el catálogo y se comprueba que el foco aparece en el
 * control play/pausa, que el botón central alterna la reproducción y que las
 * flechas buscan cuando los controles están ocultos.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ReproductorTvTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private lateinit var servidor: ServidorMedios

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
    fun elDpadAlternaPlayPausaConElFocoEnElControl() {
        abrirCanalDePrueba()
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFocoEnAlternar()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ALTERNAR).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.esperarFase("Pausado")

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ALTERNAR).performKeyInput {
            pressKey(Key.DirectionCenter)
        }
        composeRule.esperarFase("Reproduciendo")
    }

    @Test
    fun elDpadBuscaYRevelaLosControlesOcultos() {
        abrirCanalDePrueba()
        composeRule.mostrarControlesConDpad()
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_CONTROLES)

        val antes = composeRule.posicionMs()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR).performKeyInput {
            pressKey(Key.DirectionRight)
        }

        composeRule.esperarTag(TAG_REPRODUCTOR_CONTROLES)
        // El salto se limita a la duración conocida: si la posición ya estaba
        // cerca del final, el resultado válido es terminar el contenido.
        composeRule.esperarHasta {
            val posicion = composeRule.posicionMs()
            posicion >= antes + 8_000L || posicion >= 19_000L
        }
        composeRule.esperarFocoEnAlternar()
    }

    private fun abrirCanalDePrueba() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFase("Reproduciendo")
    }
}
