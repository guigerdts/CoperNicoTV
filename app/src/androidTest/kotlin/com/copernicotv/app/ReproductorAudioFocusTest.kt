package com.copernicotv.app

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E7-T6 (S42a/S42b) en un emulador: el motor Media3 gestiona el foco de
 * audio. Una petición transitoria con permiso de atenuar baja el volumen sin
 * pausar (duck) y al abandonarla el motor lo restaura; una petición
 * transitoria sin permiso pausa y, al recuperar el foco, el motor reanuda en
 * la misma sesión y con la posición conservada.
 *
 * El foco lo pide el propio proceso de prueba con `AudioFocusRequest`; la
 * pérdida llega al oyente del motor aunque ambos clientes vivan en el mismo
 * proceso, igual que en las pruebas de Media3.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ReproductorAudioFocusTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private lateinit var servidor: ServidorMedios

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

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
    fun elFocoAtenuaYPausaYAlRecuperarloElMotorRestaura() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")

        val audioManager = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val atributos =
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()
        val viewModel = composeRule.viewModelReproductor()
        val volumenBase =
            requireNotNull(composeRule.volumenMotorEn(viewModel)) {
                "El motor no reporta volumen antes del foco"
            }

        // Duck: otra app pide foco transitorio con permiso de atenuación; el
        // motor baja el volumen sin pausar la sesión.
        val pedirAtenuar =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(atributos)
                .build()
        assertEquals(
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            audioManager.requestAudioFocus(pedirAtenuar),
        )
        composeRule.esperarHasta {
            (composeRule.volumenMotorEn(viewModel) ?: volumenBase) < volumenBase
        }
        val volumenAtenuado = composeRule.volumenMotorEn(viewModel) ?: 0f
        assertTrue(
            "La atenuación silenció el motor por completo: $volumenAtenuado",
            volumenAtenuado > 0f,
        )
        composeRule.esperarFase("Reproduciendo")

        // El foco vuelve: el motor restaura el volumen íntegro.
        audioManager.abandonAudioFocusRequest(pedirAtenuar)
        composeRule.esperarHasta {
            (composeRule.volumenMotorEn(viewModel) ?: 0f) >= volumenBase
        }

        // Pérdida transitoria sin permiso de atenuar: el motor se pausa y al
        // recuperar el foco reanuda la misma sesión, con la posición que
        // siguió avanzando (S42b).
        val pedirPausa =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(atributos)
                .build()
        assertEquals(
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            audioManager.requestAudioFocus(pedirPausa),
        )
        composeRule.esperarFase("Pausado")
        val posicionEnPausa = composeRule.posicionMs()

        audioManager.abandonAudioFocusRequest(pedirPausa)
        composeRule.esperarFase("Reproduciendo")
        composeRule.esperarHasta { composeRule.posicionMs() > posicionEnPausa + 300L }
    }
}
