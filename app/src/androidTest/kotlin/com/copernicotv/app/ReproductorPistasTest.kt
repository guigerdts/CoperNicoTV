package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.PreferenciaPista
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.TAG_ACCION_REPRODUCIR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AUDIO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AVISO_PISTAS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PANEL_PISTAS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PREFERENCIA_GUARDADA
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLVER
import com.copernicotv.app.ui.tagPistaAudio
import com.copernicotv.app.ui.tagPistaSubtitulos
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T3 en un emulador móvil: selección de audio y subtítulos sobre la sesión
 * Media3 real, sin simular el motor y sin recrear la sesión.
 */
@HiltAndroidTest
class ReproductorPistasTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    @Inject
    lateinit var preferencias: RepositorioPreferenciasPista

    private lateinit var servidor: ServidorMedios

    @Before
    fun preparar() {
        hiltRule.inject()
        runBlocking {
            db.clearAllTables()
            preferencias.guardar(PreferenciasPista())
        }
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        FixturesF19.verificar(assets)
        servidor = ServidorMedios(assets).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun detectaAudioSeleccionaLaPistaYConservaLaSesion() {
        abrir(FixturesF19.PISTAS)
        val viewModel = composeRule.viewModelReproductor()
        val sesion = viewModel.sesionActual() ?: error("Sin sesión activa")
        composeRule.esperarTag(TAG_REPRODUCTOR_AUDIO)
        val antes = composeRule.posicionMs()

        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_AUDIO).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarTag(tagPistaAudio(0))
        composeRule.esperarTag(tagPistaAudio(1))
        val etiquetaIngles = composeRule.textoDeTag(tagPistaAudio(1)).removeSuffix(" · activa")
        assertTrue(
            "La opción de audio inglés no muestra información identificable: $etiquetaIngles",
            etiquetaIngles.isNotBlank(),
        )

        composeRule.onNodeWithTag(tagPistaAudio(1)).performClick()

        composeRule.esperarTexto(TAG_REPRODUCTOR_AUDIO, "Audio: $etiquetaIngles")
        assertSame("Cambiar de audio recreó la sesión Media3", sesion, viewModel.sesionActual())
        assertFalse("Cambiar de audio liberó la sesión Media3", sesion.estaLiberada())
        val audioGuardado = runBlocking { preferencias.leer().audio } as PreferenciaPista.Pista
        assertEquals("eng", audioGuardado.idioma)
        composeRule.esperarHasta { composeRule.posicionMs() > antes + 300L }
    }

    @Test
    fun seleccionaYDesactivaSubtitulosSinCortarLaReproduccion() {
        abrir(FixturesF19.PISTAS)
        val viewModel = composeRule.viewModelReproductor()
        val sesion = viewModel.sesionActual() ?: error("Sin sesión activa")
        composeRule.esperarTag(TAG_REPRODUCTOR_SUBTITULOS)
        val antes = composeRule.posicionMs()

        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SUBTITULOS).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarTag(tagPistaSubtitulos(0))
        val etiquetaEspanol = composeRule.textoDeTag(tagPistaSubtitulos(0)).removeSuffix(" · activa")
        assertTrue(
            "La opción de subtítulos español no muestra información identificable: $etiquetaEspanol",
            etiquetaEspanol.isNotBlank(),
        )
        composeRule.onNodeWithTag(tagPistaSubtitulos(0)).performClick()
        composeRule.esperarTexto(TAG_REPRODUCTOR_SUBTITULOS, "Subtítulos: $etiquetaEspanol")

        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SUBTITULOS).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS)
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS).performClick()

        composeRule.esperarTexto(TAG_REPRODUCTOR_SUBTITULOS, "Subtítulos: Desactivados")
        assertEquals(
            PreferenciaPista.Desactivada,
            runBlocking { preferencias.leer().subtitulos },
        )
        assertSame("Cambiar de subtítulos recreó la sesión Media3", sesion, viewModel.sesionActual())
        assertFalse("Cambiar de subtítulos liberó la sesión Media3", sesion.estaLiberada())
        composeRule.esperarHasta { composeRule.posicionMs() > antes + 300L }
    }

    @Test
    fun sinPistasNoInventaSelectoresNiRompeLaReproduccion() {
        abrir(FixturesF19.SIN_PISTAS)
        val antes = composeRule.posicionMs()

        composeRule.esperarHasta { composeRule.posicionMs() > antes + 300L }
        assertTrue(
            "Se ofreció audio en un contenido sin pistas",
            composeRule.onAllNodesWithTag(TAG_REPRODUCTOR_AUDIO).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Se ofrecieron subtítulos en un contenido sin pistas",
            composeRule.onAllNodesWithTag(TAG_REPRODUCTOR_SUBTITULOS).fetchSemanticsNodes().isEmpty(),
        )
        assertTrue(
            "Se mostró un aviso de pistas sin preferencia guardada",
            composeRule.onAllNodesWithTag(TAG_REPRODUCTOR_AVISO_PISTAS).fetchSemanticsNodes().isEmpty(),
        )
    }

    @Test
    fun preferenciaNoDisponibleUsaAutomaticoConservaLaPreferenciaYAvisa() {
        runBlocking {
            preferencias.guardar(
                PreferenciasPista(
                    audio = PreferenciaPista.Pista("eng", "Inglés"),
                    subtitulos = PreferenciaPista.Automatica,
                ),
            )
        }
        abrir(FixturesF19.SOLO_ESPANOL)

        composeRule.esperarTag(TAG_REPRODUCTOR_AVISO_PISTAS)
        assertTrue(
            "El aviso no nombra la preferencia ausente: ${composeRule.textoDeTag(TAG_REPRODUCTOR_AVISO_PISTAS)}",
            composeRule.textoDeTag(TAG_REPRODUCTOR_AVISO_PISTAS).contains("Inglés"),
        )
        composeRule.esperarTexto(TAG_REPRODUCTOR_AUDIO, "Audio: Español")
        assertTrue(
            "El disparador de audio no muestra ninguna pista activa",
            composeRule.textoDeTag(TAG_REPRODUCTOR_AUDIO).startsWith("Audio: "),
        )

        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_AUDIO).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PREFERENCIA_GUARDADA)
        assertTrue(
            "El panel no indica la preferencia guardada",
            composeRule.textoDeTag(TAG_REPRODUCTOR_PREFERENCIA_GUARDADA).contains("Inglés"),
        )
        assertTrue(
            "El panel inventó una pista inglesa inexistente",
            composeRule.onAllNodesWithTag(tagPistaAudio(1)).fetchSemanticsNodes().isEmpty(),
        )
        assertEquals(
            PreferenciaPista.Pista("eng", "Inglés"),
            runBlocking { preferencias.leer().audio },
        )
    }

    @Test
    fun laPreferenciaDeAudioPersisteAlVolverYReabrir() {
        abrir(FixturesF19.PISTAS)
        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_AUDIO).performClick()
        composeRule.esperarTag(tagPistaAudio(1))
        composeRule.onNodeWithTag(tagPistaAudio(1)).performClick()
        composeRule.esperarTexto(TAG_REPRODUCTOR_AUDIO, "Audio: Inglés")
        val textoEsperado = composeRule.textoDeTag(TAG_REPRODUCTOR_AUDIO)

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLVER).performClick()
        composeRule.esperarTag(PREFIJO_TAG_CANAL + 0)
        composeRule.onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
        composeRule.esperarTag(TAG_ACCION_REPRODUCIR)
        composeRule.onNodeWithTag(TAG_ACCION_REPRODUCIR).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_AUDIO)

        composeRule.mostrarControlesConToque()
        composeRule.esperarTexto(TAG_REPRODUCTOR_AUDIO, textoEsperado)
    }

    private fun abrir(ruta: String) {
        sembrarCanal(catalogo, FixturesF19.url(servidor, ruta))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
    }
}
