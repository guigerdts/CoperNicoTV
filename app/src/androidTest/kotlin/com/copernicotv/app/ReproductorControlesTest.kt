package com.copernicotv.app

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_REPRODUCTOR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ASPECTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AVANZAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_CONTROLES
import com.copernicotv.app.ui.TAG_REPRODUCTOR_RETROCEDER
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLVER
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T2 en un emulador móvil: controles propios sobre la sesión Media3 real.
 *
 * Se interactúa como una persona (toque y rotación) y se lee el estado de la
 * sesión viva a través del ViewModel, sin simular el motor. El fixture dura
 * veinte segundos para que el salto de diez segundos sea observable.
 */
@HiltAndroidTest
class ReproductorControlesTest {
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
    fun playPausaDetieneYReanudaLaPosicion() {
        abrirCanalDePrueba()
        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ALTERNAR).performClick()
        composeRule.esperarFase("Pausado")

        val enPausa = composeRule.posicionMs()
        Thread.sleep(1_500L)
        val trasEspera = composeRule.posicionMs()
        assertTrue(
            "La posición avanzó con el reproductor en pausa: $enPausa -> $trasEspera",
            trasEspera <= enPausa + 250L,
        )

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ALTERNAR).performClick()
        composeRule.esperarFase("Reproduciendo")
        composeRule.esperarHasta { composeRule.posicionMs() > trasEspera + 300L }
    }

    @Test
    fun avanzarYRetrocederDesplazanLaPosicion() {
        abrirCanalDePrueba()
        composeRule.mostrarControlesConToque()
        val antes = composeRule.posicionMs()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_AVANZAR).performClick()
        composeRule.esperarHasta { composeRule.posicionMs() >= antes + 8_000L }

        composeRule.mostrarControlesConToque()
        val trasAvance = composeRule.posicionMs()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_RETROCEDER).performClick()
        composeRule.esperarHasta { composeRule.posicionMs() <= trasAvance - 8_000L }
    }

    @Test
    fun elCambioDeAspectoConservaLaSesionYLaPosicion() {
        abrirCanalDePrueba()
        val viewModel = composeRule.viewModelReproductor()
        val sesion = viewModel.sesionActual() ?: error("Sin sesión activa")
        composeRule.mostrarControlesConToque()
        assertEquals("Ajustar", composeRule.textoDeTag(TAG_REPRODUCTOR_ASPECTO))
        val antes = composeRule.posicionMs()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ASPECTO).performClick()
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Rellenar")
        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ASPECTO).performClick()
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Zoom")

        composeRule.esperarHasta { composeRule.posicionMs() > antes }
        assertSame("El cambio de aspecto recreó la sesión Media3", sesion, viewModel.sesionActual())
        assertFalse("El cambio de aspecto liberó la sesión Media3", sesion.estaLiberada())
    }

    @Test
    fun losControlesSeOcultanSolosYReaparecenConToque() {
        abrirCanalDePrueba()
        composeRule.mostrarControlesConToque()
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_CONTROLES)

        composeRule.onNodeWithTag(TAG_REPRODUCTOR).performTouchInput { click() }
        composeRule.esperarTag(TAG_REPRODUCTOR_CONTROLES)
    }

    @Test
    fun rotarConservaLaSesionYLaPosicion() {
        abrirCanalDePrueba()
        val viewModel = composeRule.viewModelReproductor()
        val sesion = viewModel.sesionActual() ?: error("Sin sesión activa")
        val antes = composeRule.posicionMs()

        val orientacionActual = composeRule.activity.resources.configuration.orientation
        val destino =
            if (orientacionActual == Configuration.ORIENTATION_LANDSCAPE) {
                Configuration.ORIENTATION_PORTRAIT
            } else {
                Configuration.ORIENTATION_LANDSCAPE
            }
        val solicitada =
            if (destino == Configuration.ORIENTATION_LANDSCAPE) {
                ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
            } else {
                ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
            }
        composeRule.runOnUiThread { composeRule.activity.requestedOrientation = solicitada }
        composeRule.esperarHasta {
            composeRule.activity.resources.configuration.orientation == destino
        }

        composeRule.esperarHasta { composeRule.posicionMs() > antes }
        assertSame("La rotación recreó la sesión Media3", sesion, viewModel.sesionActual())
        assertFalse("La rotación liberó la sesión Media3", sesion.estaLiberada())
    }

    @Test
    fun volverLiberaLaSesionMedia3() {
        abrirCanalDePrueba()
        val viewModel = composeRule.viewModelReproductor()
        val sesion = viewModel.sesionActual() ?: error("Sin sesión activa")
        composeRule.mostrarControlesConToque()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLVER).performClick()

        composeRule.esperarTag(PREFIJO_TAG_CANAL + 0)
        composeRule.esperarHasta { sesion.estaLiberada() }
        assertNull("La pantalla conservó la sesión tras volver", viewModel.sesionActual())
    }

    private fun abrirCanalDePrueba() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
    }
}
