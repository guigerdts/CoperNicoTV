package com.copernicotv.app

import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.pressKey
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ASPECTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AUDIO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AVANZAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_RETROCEDER
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SECUNDARIOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLVER
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E7-T5 en un emulador de Android TV real: la preferencia global de S17b se
 * opera con el mando (foco navegado + OK) y al volver a mostrar los controles
 * el foco va a play/pausa, como decidió el propietario. La reapertura del
 * Canal restaura el modo de aspecto de S44b y lo aplica a la `PlayerView`.
 *
 * La supervivencia al reinicio real del proceso la demuestra el arnés
 * `E7T5Proceso*` del job móvil; aquí se valida la navegación con D-pad y la
 * aplicación efectiva sobre la sesión Media3 viva.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ReproductorPreferenciasTvTest {
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
    fun elDpadAlternaLosSecundariosYelFocoVuelveAPlayPausa() {
        abrirCanalDePrueba()
        composeRule.esperarFocoEnAlternar()
        // Pausa el contenido: con PAUSADO el auto-ocultado no actúa y el
        // recorrido con el mando no compite contra el temporizador de 3 s.
        pulsar(TAG_REPRODUCTOR_ALTERNAR, Key.DirectionCenter)
        composeRule.esperarFase("Pausado")

        // La fila es play/pausa, −10 s, +10 s, aspecto y el control de la
        // preferencia (S17b); el foco recorre los cinco en orden.
        pulsar(TAG_REPRODUCTOR_ALTERNAR, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_RETROCEDER)
        pulsar(TAG_REPRODUCTOR_RETROCEDER, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_AVANZAR)
        pulsar(TAG_REPRODUCTOR_AVANZAR, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_ASPECTO)
        pulsar(TAG_REPRODUCTOR_ASPECTO, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_SECUNDARIOS)

        pulsar(TAG_REPRODUCTOR_SECUNDARIOS, Key.DirectionCenter)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_RETROCEDER)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AVANZAR)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_ASPECTO)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AUDIO)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_SUBTITULOS)
        composeRule.esperarTag(TAG_REPRODUCTOR_ALTERNAR)
        composeRule.esperarTag(TAG_REPRODUCTOR_SECUNDARIOS)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_ALTERNAR)

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SECUNDARIOS).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_RETROCEDER)
        composeRule.esperarTag(TAG_REPRODUCTOR_AVANZAR)
        composeRule.esperarTag(TAG_REPRODUCTOR_ASPECTO)
        composeRule.esperarTag(TAG_REPRODUCTOR_AUDIO)
        composeRule.esperarTag(TAG_REPRODUCTOR_SUBTITULOS)
        // El propietario definió esto para S17b: al volver a mostrar, el foco
        // va a play/pausa.
        composeRule.esperarFocoEnAlternar()
    }

    @Test
    fun elModoRestauradoAlReabrirSeAplicaALaSurface() {
        abrirCanalDePrueba()
        pulsar(TAG_REPRODUCTOR_ALTERNAR, Key.DirectionCenter)
        composeRule.esperarFase("Pausado")
        pulsar(TAG_REPRODUCTOR_ALTERNAR, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_RETROCEDER)
        pulsar(TAG_REPRODUCTOR_RETROCEDER, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_AVANZAR)
        pulsar(TAG_REPRODUCTOR_AVANZAR, Key.DirectionRight)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_ASPECTO)
        pulsar(TAG_REPRODUCTOR_ASPECTO, Key.DirectionCenter)
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Rellenar")

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLVER).performClick()
        composeRule.esperarTag(PREFIJO_TAG_CANAL + 0)

        composeRule.abrirReproductor()
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFase("Reproduciendo")
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Rellenar")
        composeRule.esperarHasta { resizeModeActual() == AspectRatioFrameLayout.RESIZE_MODE_FILL }
    }

    /** Pulsa una tecla del mando sobre el nodo con [tag]. */
    private fun pulsar(
        tag: String,
        tecla: Key,
    ) {
        composeRule.onNodeWithTag(tag).performKeyInput { pressKey(tecla) }
    }

    private fun abrirCanalDePrueba() {
        sembrarCanal(catalogo, FixturesF19.url(servidor, FixturesF19.PISTAS))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFase("Reproduciendo")
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
