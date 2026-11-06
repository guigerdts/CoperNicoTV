package com.copernicotv.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ASPECTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AUDIO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AVANZAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_FASE
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PANEL_PISTAS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PROGRESO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_RETROCEDER
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SECUNDARIOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLVER
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
 * E7-T5 en un emulador móvil real: la preferencia global de S17b oculta y
 * muestra exactamente los controles secundarios definidos por el propietario
 * (−10 s, +10 s, aspecto, audio y subtítulos) y conserva visibles Volver,
 * play/pausa, la barra de progreso y el indicador de fase; el modo de aspecto
 * de S44b se aplica a la `PlayerView` y sobrevive al cierre y reabertura.
 *
 * La persistencia tras reinicio real del proceso la demuestra el arnés de dos
 * fases `E7T5Proceso*` dentro de `e3-process-restart.sh`; aquí se valida el
 * comportamiento interactivo sobre la sesión Media3 viva.
 */
@HiltAndroidTest
class ReproductorPreferenciasTest {
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
        FixturesF19.verificar(assets)
        servidor = ServidorMedios(assets).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun ocultaYMuestraLosSecundariosDefinidos() {
        abrirCanalDePrueba()
        secundariosVisibles()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SECUNDARIOS).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_ALTERNAR)
        composeRule.esperarTag(TAG_REPRODUCTOR_VOLVER)
        composeRule.esperarTag(TAG_REPRODUCTOR_PROGRESO)
        composeRule.esperarTag(TAG_REPRODUCTOR_FASE)
        composeRule.esperarTag(TAG_REPRODUCTOR_SECUNDARIOS)
        siempreVisiblesPresentes()
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_RETROCEDER)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AVANZAR)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_ASPECTO)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AUDIO)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_SUBTITULOS)

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SECUNDARIOS).performClick()
        secundariosVisibles()
    }

    @Test
    fun ocultarLosSecundariosCierraElPanelAbierto() {
        abrirCanalDePrueba()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_AUDIO).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_PISTAS)

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SECUNDARIOS).performClick()

        composeRule.esperarTagAusente(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AUDIO)
    }

    @Test
    fun elAspectoSeAplicaALaSurfaceYSobreviveALaReapertura() {
        abrirCanalDePrueba()
        assertEquals(
            AspectRatioFrameLayout.RESIZE_MODE_FIT,
            resizeModeActual(),
        )

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ASPECTO).performClick()
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Rellenar")
        composeRule.esperarHasta { resizeModeActual() == AspectRatioFrameLayout.RESIZE_MODE_FILL }

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLVER).performClick()
        composeRule.esperarTag(PREFIJO_TAG_CANAL + 0)

        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Rellenar")
        composeRule.esperarHasta { resizeModeActual() == AspectRatioFrameLayout.RESIZE_MODE_FILL }
    }

    private fun abrirCanalDePrueba() {
        sembrarCanal(catalogo, FixturesF19.url(servidor, FixturesF19.PISTAS))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
    }

    private fun secundariosVisibles() {
        composeRule.esperarTag(TAG_REPRODUCTOR_RETROCEDER)
        composeRule.esperarTag(TAG_REPRODUCTOR_AVANZAR)
        composeRule.esperarTag(TAG_REPRODUCTOR_ASPECTO)
        composeRule.esperarTag(TAG_REPRODUCTOR_AUDIO)
        composeRule.esperarTag(TAG_REPRODUCTOR_SUBTITULOS)
    }

    private fun siempreVisiblesPresentes() {
        composeRule.esperarTag(TAG_REPRODUCTOR_VOLVER)
        composeRule.esperarTag(TAG_REPRODUCTOR_ALTERNAR)
        composeRule.esperarTag(TAG_REPRODUCTOR_PROGRESO)
        composeRule.esperarTag(TAG_REPRODUCTOR_FASE)
    }

    /** El `resizeMode` vigente de la única `PlayerView` de la jerarquía (S44b). */
    private fun resizeModeActual(): Int {
        var leido = -1
        composeRule.runOnUiThread {
            leido = buscarPlayerView(composeRule.activity.window.decorView)?.resizeMode ?: -1
        }
        return leido
    }

    private fun buscarPlayerView(raiz: View): PlayerView? {
        if (raiz is PlayerView) return raiz
        if (raiz is ViewGroup) {
            for (indice in 0 until raiz.childCount) {
                buscarPlayerView(raiz.getChildAt(indice))?.let { return it }
            }
        }
        return null
    }
}
