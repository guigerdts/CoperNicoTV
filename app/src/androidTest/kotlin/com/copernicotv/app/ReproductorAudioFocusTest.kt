package com.copernicotv.app

import android.content.Context
import android.media.AudioFocusRequest
import android.media.AudioManager
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.media3.common.Player
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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
 * la misma sesión y con la posición conservada. Una pérdida permanente y una
 * pausa voluntaria del usuario no producen reanudación automática.
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
        // El fixture dura ~20 s: lo repite para que el test de foco nunca
        // quede sin reproducción activa.
        composeRule.activityRule.scenario.onActivity {
            composeRule.viewModelReproductor().motor?.repeatMode = Player.REPEAT_MODE_ONE
        }

        val audioManager = contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager
        val atributos =
            android.media.AudioAttributes.Builder()
                .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
                .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
                .build()

        // Duck: otra app pide foco transitorio con permiso de atenuación. El
        // motor atiende el duck sin pausar ni suprimir la reproducción (el
        // multiplicador de volumen del duck es interno del sink de Media3 y
        // no es observable por la API pública del Player).
        val pedirAtenuar =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT_MAY_DUCK)
                .setAudioAttributes(atributos)
                .build()
        assertEquals(
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            audioManager.requestAudioFocus(pedirAtenuar),
        )
        composeRule.esperarFase("Reproduciendo")
        var sigueReproduciendo = false
        var sinSupresion = false
        composeRule.activityRule.scenario.onActivity {
            val motor = composeRule.viewModelReproductor().motor
            sigueReproduciendo = motor?.isPlaying == true
            sinSupresion =
                motor?.playbackSuppressionReason == Player.PLAYBACK_SUPPRESSION_REASON_NONE
        }
        assertTrue("El duck pausó o suprimió la reproducción", sigueReproduciendo && sinSupresion)
        audioManager.abandonAudioFocusRequest(pedirAtenuar)

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

    @Test
    fun laPerdidaPermanentePausaYNoSeReanudaSola() {
        val audioManager = iniciarReproduccionEnBucle()
        val atributos = atributosDeMedia()

        // Otra app toma el foco en modo permanente (AUDIOFOCUS_GAIN). Media3
        // pausa con razón AUDIO_FOCUS_LOSS y libera su propio pedido, así que
        // deja de recibir avisos de foco.
        val pedirFoco =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN)
                .setAudioAttributes(atributos)
                .build()
        assertEquals(
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            audioManager.requestAudioFocus(pedirFoco),
        )
        composeRule.esperarFase("Pausado")
        var razonPausa: Int? = null
        composeRule.activityRule.scenario.onActivity {
            razonPausa = composeRule.viewModelReproductor().motor?.playWhenReadyChangeReason
        }
        assertEquals(Player.PLAY_WHEN_READY_CHANGE_REASON_AUDIO_FOCUS_LOSS, razonPausa)

        // Aunque la otra app libere el foco, una pérdida permanente no se
        // reanuda sola: el motor no vuelve a pedir el foco sin acción del
        // usuario (S42a).
        audioManager.abandonAudioFocusRequest(pedirFoco)
        val reanudo =
            runCatching {
                composeRule.waitUntil(2_000L) { composeRule.faseActual() == "Reproduciendo" }
            }.isSuccess
        assertFalse("El motor se reanudó solo tras una pérdida permanente", reanudo)
    }

    @Test
    fun laPausaVoluntariaNoSeReanudaSola() {
        val audioManager = iniciarReproduccionEnBucle()
        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ALTERNAR).performClick()
        composeRule.esperarFase("Pausado")
        var razonPausa: Int? = null
        composeRule.activityRule.scenario.onActivity {
            razonPausa = composeRule.viewModelReproductor().motor?.playWhenReadyChangeReason
        }
        assertEquals(Player.PLAY_WHEN_READY_CHANGE_REASON_USER_REQUEST, razonPausa)

        // Con la pausa voluntaria Media3 liberó el foco: otra app puede
        // tomarlo y soltarlo sin que el motor se reanude solo (S42a).
        val pedirFoco =
            AudioFocusRequest
                .Builder(AudioManager.AUDIOFOCUS_GAIN_TRANSIENT)
                .setAudioAttributes(atributosDeMedia())
                .build()
        assertEquals(
            AudioManager.AUDIOFOCUS_REQUEST_GRANTED,
            audioManager.requestAudioFocus(pedirFoco),
        )
        audioManager.abandonAudioFocusRequest(pedirFoco)
        val reanudo =
            runCatching {
                composeRule.waitUntil(2_000L) { composeRule.faseActual() == "Reproduciendo" }
            }.isSuccess
        assertFalse("La pausa voluntaria se convirtió en reanudación automática", reanudo)
    }

    private fun iniciarReproduccionEnBucle(): AudioManager {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        composeRule.esperarFase("Reproduciendo")
        // El fixture dura ~20 s: lo repite para que el test de foco nunca
        // quede sin reproducción activa.
        composeRule.activityRule.scenario.onActivity {
            composeRule.viewModelReproductor().motor?.repeatMode = Player.REPEAT_MODE_ONE
        }
        return contexto.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    private fun atributosDeMedia(): android.media.AudioAttributes =
        android.media.AudioAttributes
            .Builder()
            .setUsage(android.media.AudioAttributes.USAGE_MEDIA)
            .setContentType(android.media.AudioAttributes.CONTENT_TYPE_MOVIE)
            .build()
}
