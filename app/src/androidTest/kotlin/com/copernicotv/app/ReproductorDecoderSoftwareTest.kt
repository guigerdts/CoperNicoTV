package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.core.player.MotivoSoporteVideo
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
import javax.inject.Inject

/**
 * Reproducción end-to-end con el decoder software FFmpeg (E7-T12, S5/S6).
 *
 * MPEG-2 no tiene decoder MediaCodec garantizado en el emulador: es el caso
 * donde la política debe recaer en el decoder externo FFmpeg y producir
 * imagen real en Surface (S9: no se declara soporte hasta el primer frame
 * observado). MPEG-4 y H.263 SÍ tienen decoders MediaCodec software en el
 * emulador, así que sus fixtures validan reproducción sin exigir quién
 * decodifica. La regresión H.264 usa [FixturesF19.PISTAS]: hardware
 * obligatorio, nunca el decoder software.
 */
@HiltAndroidTest
class ReproductorDecoderSoftwareTest {
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
        FixturesE7T12.verificar(assets)
        FixturesF19.verificar(assets)
        servidor = ServidorMedios(assets).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun elMpeg2SeReproducePorSoftwareConPrimerFrameYAudio() {
        abrir(FixturesE7T12.MPEG2)

        val estado =
            esperar(
                "reproducción MPEG-2 con primer frame y audio",
            ) {
                it.estado == EstadoReproductor.REPRODUCIENDO &&
                    it.msHastaPrimerFrameVideo != null &&
                    it.decoderVideo != null &&
                    it.audioRendererActivo == true
            }
        assertEquals(
            "El diagnóstico debe nombrar al decoder software FFmpeg",
            "ffmpeg:mpeg2video",
            estado.decoderVideo,
        )
        assertEquals(TipoDecoder.SOFTWARE, estado.tipoDecoderVideo)
        assertEquals(MotivoSoporteVideo.SOPORTADO, estado.motivoSoporteVideo)
        assertNotNull("Debe registrar el tiempo de inicialización", estado.msInicializacionDecoderVideo)
        assertTrue(
            "El primer frame debe llegar en la sesión",
            (estado.msHastaPrimerFrameVideo ?: -1L) >= 0L,
        )

        // Liberación doble: `liberar()` es idempotente y la segunda llamada
        // no debe corromper el estado ni el hilo de decodificación nativo.
        val sesion = viewModelReproductor().sesionActual() ?: error("Sin sesión activa")
        composeRule.runOnUiThread {
            sesion.liberar()
            sesion.liberar()
        }
        assertTrue("La sesión debe quedar liberada", sesion.estaLiberada())
    }

    @Test
    fun elMpeg4Parte2SeReproduceEnElEmulador() {
        abrir(FixturesE7T12.MPEG4)
        esperar("reproducción MPEG-4 Part 2 con primer frame") {
            it.estado == EstadoReproductor.REPRODUCIENDO &&
                it.msHastaPrimerFrameVideo != null &&
                it.audioRendererActivo == true
        }
        // El emulador puede decodificarlo con MediaCodec o con el externo:
        // lo que la política exige es un decoder, no uno concreto.
        val estado = viewModelReproductor().sesionEstado.value
        assertTrue(
            "Debe haber decoder de video asignado",
            estado.decoderVideo != null || estado.decoderVideoSeleccionado != null,
        )
    }

    @Test
    fun elH263SeReproduceEnElEmulador() {
        abrir(FixturesE7T12.H263)
        esperar("reproducción H.263 con primer frame") {
            it.estado == EstadoReproductor.REPRODUCIENDO &&
                it.msHastaPrimerFrameVideo != null &&
                it.audioRendererActivo == true
        }
    }

    @Test
    fun elH264NuncaRecaeEnElDecoderSoftware() {
        abrir(FixturesF19.PISTAS)
        val estado =
            esperar("reproducción H.264 con primer frame") {
                it.estado == EstadoReproductor.REPRODUCIENDO &&
                    it.msHastaPrimerFrameVideo != null &&
                    it.decoderVideo != null
            }
        // En emulador el MediaCodec elegido puede ser software (c2.*) o
        // hardware (OMX.*); lo que la política prohíbe es que H.264 salga
        // por el decoder externo FFmpeg (regresión E7-T12, S3).
        assertTrue(
            "El decoder software no debe intervenir en H.264",
            estado.decoderVideo?.startsWith("ffmpeg:") != true,
        )
        assertNotNull(
            "El veredicto debe nombrar el MediaCodec del dispositivo",
            estado.decoderVideoSeleccionado,
        )
    }

    // ===================== Ayudas =====================

    private fun abrir(ruta: String) {
        sembrarCanal(catalogo, FixturesE7T12.url(servidor, ruta))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
    }

    private fun esperar(
        descripcion: String,
        predicado: (EstadoSesion) -> Boolean,
    ): EstadoSesion =
        try {
            runBlocking {
                withTimeout(ESPERA_TEST_MS) {
                    viewModelReproductor().sesionEstado.first(predicado)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError(
                "No llegó $descripcion en $ESPERA_TEST_MS ms. " +
                    "estado=${viewModelReproductor().sesionEstado.value}",
                error,
            )
        }
}
