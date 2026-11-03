package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.PreferenciaPista
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AUDIO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AUDIO_AUTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PANEL_PISTAS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS_AUTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS
import com.copernicotv.app.ui.tagPistaAudio
import com.copernicotv.app.ui.tagPistaSubtitulos
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T3 en el emulador de Android TV: los selectores de pistas se manejan solo
 * con D-pad, el panel devuelve el foco a su disparador y la sesión vive.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ReproductorPistasTvTest {
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
    fun elDpadSeleccionaAudioYDevuelveElFocoAlDisparador() {
        abrir(FixturesF19.PISTAS)
        val viewModel = composeRule.viewModelReproductor()
        val sesion = viewModel.sesionActual() ?: error("Sin sesión activa")
        composeRule.esperarFocoEnAlternar()

        pulsar(TAG_REPRODUCTOR_ALTERNAR, Key.DirectionDown)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_AUDIO)
        pulsar(TAG_REPRODUCTOR_AUDIO, Key.DirectionCenter)
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_AUDIO_AUTO)

        pulsar(TAG_REPRODUCTOR_AUDIO_AUTO, Key.DirectionDown)
        composeRule.esperarFocoEn(tagPistaAudio(0))
        pulsar(tagPistaAudio(0), Key.DirectionDown)
        composeRule.esperarFocoEn(tagPistaAudio(1))
        pulsar(tagPistaAudio(1), Key.DirectionCenter)

        composeRule.esperarTexto(TAG_REPRODUCTOR_AUDIO, "Audio: Inglés")
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_AUDIO)
        assertSame("Cambiar de audio recreó la sesión Media3", sesion, viewModel.sesionActual())
        val audioGuardado = runBlocking { preferencias.leer().audio } as PreferenciaPista.Pista
        assertEquals("eng", audioGuardado.idioma)
    }

    @Test
    fun elDpadSeleccionaYDesactivaSubtitulosConRetornoDeFoco() {
        abrir(FixturesF19.PISTAS)
        composeRule.esperarFocoEnAlternar()

        pulsar(TAG_REPRODUCTOR_ALTERNAR, Key.DirectionDown)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_AUDIO)
        pulsar(TAG_REPRODUCTOR_AUDIO, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SUBTITULOS)

        pulsar(TAG_REPRODUCTOR_SUBTITULOS, Key.DirectionCenter)
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS)
        pulsar(TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS, Key.DirectionDown)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SUBTITULOS_AUTO)
        pulsar(TAG_REPRODUCTOR_SUBTITULOS_AUTO, Key.DirectionDown)
        composeRule.esperarFocoEn(tagPistaSubtitulos(0))
        pulsar(tagPistaSubtitulos(0), Key.DirectionCenter)

        composeRule.esperarTexto(TAG_REPRODUCTOR_SUBTITULOS, "Subtítulos: Español")
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SUBTITULOS)

        pulsar(TAG_REPRODUCTOR_SUBTITULOS, Key.DirectionCenter)
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_PISTAS)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS)
        pulsar(TAG_REPRODUCTOR_SUBTITULOS_DESACTIVADOS, Key.DirectionCenter)

        composeRule.esperarTexto(TAG_REPRODUCTOR_SUBTITULOS, "Subtítulos: Desactivados")
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SUBTITULOS)
        assertEquals(
            PreferenciaPista.Desactivada,
            runBlocking { preferencias.leer().subtitulos },
        )
    }

    private fun pulsar(
        tag: String,
        tecla: Key,
    ) {
        composeRule.onNodeWithTag(tag).performKeyInput { pressKey(tecla) }
    }

    private fun abrir(ruta: String) {
        sembrarCanal(catalogo, FixturesF19.url(servidor, ruta))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFase("Reproduciendo")
    }
}
