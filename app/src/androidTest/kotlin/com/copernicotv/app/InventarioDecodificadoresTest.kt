package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.InventarioDecodificadores
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.player.SesionMedia3
import com.copernicotv.core.player.ConfiguracionImagen
import com.copernicotv.core.player.DeclaracionFormatoVideo
import com.copernicotv.core.player.DecoderSoftwareVideo
import com.copernicotv.core.player.MotivoSoporteVideo
import com.copernicotv.core.player.ProtocoloStream
import com.copernicotv.core.player.TipoDecoder
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference

/**
 * Inventario y selección de decoders contra el dispositivo real (E7-T11, T5).
 *
 * Las pruebas JVM no pueden tocar `MediaCodecList`: aquí se valida la
 * conversión y el veredicto end-to-end sobre el emulador. Los decoders
 * disponibles dependen de cada imagen: las aserciones son de consistencia
 * entre inventario y veredicto, nunca de un dispositivo concreto.
 */
@HiltAndroidTest
class InventarioDecodificadoresTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    private lateinit var servidor: ServidorMedios

    private val sesionDirectaReferencia = AtomicReference<SesionMedia3?>()

    @Before
    fun preparar() {
        hiltRule.inject()
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        FixturesF19.verificar(assets)
        servidor = ServidorMedios(assets).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun elInventarioDelDispositivoDeclaraDecodersDeVideo() {
        val decoders = InventarioDecodificadores().decoders()

        assertTrue(decoders.isNotEmpty())
        assertTrue(decoders.all { it.mime.startsWith("video/") })
        assertTrue("El emulador garantiza decodificación H.264", decoders.any { it.mime == "video/avc" })
        assertTrue(decoders.all { it.tipo == TipoDecoder.HARDWARE || it.tipo == TipoDecoder.SOFTWARE })
    }

    @Test
    fun elVeredictoConservaLaConsistenciaConElInventario() {
        // E7-T12: el inventario de la sesión registra el decoder externo
        // software FFmpeg, así que MPEG-2 pasa de MIME_NO_SOPORTADO
        // (E7-T11) a SOPORTADO con fallback software; el veredicto sigue
        // dependiendo solo del inventario y del externo, no del dispositivo.
        val inventario = InventarioDecodificadores(externos = listOf(DecoderSoftwareVideo()))

        val mpeg2Nativo = inventario.decoders().any { it.mime == "video/mpeg2" }
        val veredicto = inventario.seleccionar(DeclaracionFormatoVideo(mime = "video/mpeg2"))
        assertEquals(
            "Con el externo registrado, MPEG-2 siempre queda soportado",
            MotivoSoporteVideo.SOPORTADO,
            veredicto.motivo,
        )
        if (!mpeg2Nativo) {
            assertEquals(
                "Sin decoder nativo, el externo software es el seleccionado",
                "ffmpeg:mpeg2video",
                veredicto.decoder,
            )
            assertEquals(TipoDecoder.SOFTWARE, veredicto.tipo)
        }

        val veredictoAvc = inventario.seleccionar(DeclaracionFormatoVideo(mime = "video/avc"))
        assertEquals(MotivoSoporteVideo.SOPORTADO, veredictoAvc.motivo)
        assertNotNull(veredictoAvc.decoder)
        assertNotNull(veredictoAvc.tipo)
        assertTrue(
            "H.264 nunca recae en el decoder externo software",
            veredictoAvc.decoder?.startsWith("ffmpeg:") != true,
        )
    }

    @Test
    fun laSesionPublicaLaSeleccionSobreUnFixtureH264() {
        composeRule.runOnUiThread {
            sesionDirectaReferencia.set(
                SesionMedia3(
                    composeRule.activity,
                    FixturesF19.url(servidor, FixturesF19.PISTAS),
                    ProtocoloStream.PROGRESIVO,
                    PreferenciasPista(),
                    intervaloMs = 250L,
                    configuracionImagen = ConfiguracionImagen(ventanaConfirmacionMs = 1_000L),
                ),
            )
        }
        val sesion = sesionDirectaReferencia.get() ?: error("No se creó la sesión directa")

        try {
            val estado =
                runBlocking {
                    withTimeout(ESPERA_MS) {
                        sesion.estado.first {
                            it.estado == EstadoReproductor.REPRODUCIENDO && it.decoderVideoSeleccionado != null
                        }
                    }
                }

            assertEquals(EstadoReproductor.REPRODUCIENDO, estado.estado)
            assertEquals(MotivoSoporteVideo.SOPORTADO, estado.motivoSoporteVideo)
            assertNotNull(estado.decoderVideoSeleccionado)
            assertNotNull(estado.tipoDecoderVideo)
        } catch (error: TimeoutCancellationException) {
            throw AssertionError("No llegó la selección de decoder en $ESPERA_MS ms", error)
        } finally {
            composeRule.runOnUiThread {
                sesion.liberar()
                sesionDirectaReferencia.set(null)
            }
        }
    }

    private companion object {
        const val ESPERA_MS = 30_000L
    }
}
