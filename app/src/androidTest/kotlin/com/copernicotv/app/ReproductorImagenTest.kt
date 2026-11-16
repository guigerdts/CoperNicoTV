package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.media3.common.C
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.AccionesImagen
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.player.SesionMedia3
import com.copernicotv.core.player.CausaImagen
import com.copernicotv.core.player.ConfiguracionImagen
import com.copernicotv.core.player.EstadoImagen
import com.copernicotv.core.player.ProtocoloStream
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicReference
import javax.inject.Inject

/**
 * Recuperación de imagen y estabilidad audiovisual (E7-T10, S5).
 *
 * La detección real «audio activo sin imagen» exige provocar un caso que el
 * emulador no siempre permite, así que la sesión expone el gancho
 * `forzarCaso` sobre una sesión construida sin superficie (la prueba
 * nunca la adjunta a una vista). Los casos de reproducción real (arranque
 * normal, pérdida de red) se prueban sin el gancho: la máquina no debe abrir
 * casos espurios, y una pérdida de red no es un problema de imagen.
 */
@HiltAndroidTest
class ReproductorImagenTest {
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

    private val sesionDirectaReferencia = AtomicReference<SesionMedia3?>()

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
    fun elVideoDeshabilitadoSeReponeYLaImagenVuelveSinDuplicarAudio() {
        abrir(FixturesF19.PISTAS)
        val viewModel = composeRule.viewModelReproductor()
        val sesion = viewModel.sesionActual() ?: error("Sin sesión activa")
        composeRule.runOnUiThread {
            sesion.reproductor.trackSelectionParameters =
                sesion.reproductor.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
        }

        // La escalera arranca por el nivel menos invasivo: re-attach de superficie.
        esperarSesionVm("solicitud de re-attach de superficie") {
            viewModel.solicitudSuperficie.value > 0
        }
        // La re-selección repone la pista de video y la imagen vuelve.
        esperarSesionVm("imagen recuperada") {
            it.estadoImagen == EstadoImagen.IMAGEN_RECUPERADA
        }
        composeRule.runOnUiThread {
            assertFalse(
                "El reemplazo de selección dejó el video deshabilitado",
                sesion.reproductor.trackSelectionParameters.disabledTrackTypes.contains(C.TRACK_TYPE_VIDEO),
            )
        }
        assertSame(
            "La recuperación de imagen recreó la sesión Media3",
            sesion,
            viewModel.sesionActual(),
        )
        assertEquals(
            "La recuperación de imagen consumió reintentos de conexión",
            0,
            viewModel.sesionEstado.value.reintento,
        )
        assertTrue(
            "El audio activo se perdió durante la recuperación",
            viewModel.estadoPistas.value.audio.any { it.activa },
        )
    }

    @Test
    fun elZapeoCancelaLaRecuperacionDeImagen() {
        sembrarDosCanales()
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
        val viewModel = composeRule.viewModelReproductor()
        val anterior = viewModel.sesionActual() ?: error("Sin sesión activa")
        composeRule.runOnUiThread {
            anterior.reproductor.trackSelectionParameters =
                anterior.reproductor.trackSelectionParameters
                    .buildUpon()
                    .setTrackTypeDisabled(C.TRACK_TYPE_VIDEO, true)
                    .build()
        }
        esperarSesionVm("solicitud de re-attach en el canal A") {
            viewModel.solicitudSuperficie.value > 0
        }
        val contadorAlZapear = viewModel.solicitudSuperficie.value

        viewModel.zapearSiguiente()
        esperarSesionVm("reproducción del canal B") {
            it.estado == EstadoReproductor.REPRODUCIENDO
        }
        assertNotSame(
            "El zapeo no cambió de sesión",
            anterior,
            viewModel.sesionActual(),
        )
        assertTrue("El zapeo no liberó la sesión anterior", anterior.estaLiberada())
        Thread.sleep(3_000)
        assertEquals(
            "La escalera cancelada siguió ejecutando niveles",
            contadorAlZapear,
            viewModel.solicitudSuperficie.value,
        )
    }

    @Test
    fun elArranqueNormalNoGeneraFalsosPositivos() {
        abrir(FixturesF19.PISTAS)
        val viewModel = composeRule.viewModelReproductor()
        composeRule.esperarReproduccionAvanzando()
        Thread.sleep(3_000)
        val imagen = viewModel.sesionEstado.value.estadoImagen
        assertTrue(
            "El arranque normal abrió un caso de imagen: $imagen",
            imagen == null ||
                imagen == EstadoImagen.PREPARANDO_VIDEO ||
                imagen == EstadoImagen.ESPERANDO_PRIMER_FRAME,
        )
        assertEquals(
            "El arranque normal pidió un re-attach de superficie",
            0,
            viewModel.solicitudSuperficie.value,
        )
    }

    @Test
    fun laPerdidaDeRedNoSeConfundeConUnProblemaDeImagen() {
        abrir(FixturesF19.PISTAS)
        val viewModel = composeRule.viewModelReproductor()
        composeRule.esperarReproduccionAvanzando()

        ConectividadPrueba.desconectar()
        esperarSesionVm("esperando conexión") {
            it.estado == EstadoReproductor.ESPERANDO_CONEXION
        }
        assertEquals(
            "La pérdida de red abrió un caso de imagen",
            0,
            viewModel.solicitudSuperficie.value,
        )
        val imagenEnCorte = viewModel.sesionEstado.value.estadoImagen
        assertTrue(
            "La pérdida de red publicó un estado de recuperación de imagen: $imagenEnCorte",
            imagenEnCorte == null ||
                imagenEnCorte == EstadoImagen.PREPARANDO_VIDEO ||
                imagenEnCorte == EstadoImagen.ESPERANDO_PRIMER_FRAME,
        )

        ConectividadPrueba.conectar()
        PuertaReintentos.liberar()
        esperarSesionVm("reproducción tras volver la red") {
            it.estado == EstadoReproductor.REPRODUCIENDO
        }
        assertEquals(
            "La recuperación de red pidió un re-attach de superficie",
            0,
            viewModel.solicitudSuperficie.value,
        )
    }

    @Test
    fun laReconstruccionPreservaLaPosicionYArrancaUnaSesionNueva() {
        abrir(FixturesF19.PISTAS)
        val viewModel = composeRule.viewModelReproductor()
        val anterior = viewModel.sesionActual() ?: error("Sin sesión activa")

        composeRule.runOnUiThread { viewModel.reconstruirSesionParaPruebas(500L) }
        esperarSesionVm("reproducción de la sesión reconstruida") {
            it.estado == EstadoReproductor.REPRODUCIENDO && it.posicionMs in 200L..2_500L
        }
        assertNotSame(
            "La reconstrucción reutilizó la sesión anterior",
            anterior,
            viewModel.sesionActual(),
        )
        assertTrue(
            "La sesión anterior no quedó liberada tras la reconstrucción",
            anterior.estaLiberada(),
        )
    }

    @Test
    fun laEscaleraDeSuperficiePerdidaTerminaSinReconstruir() {
        val acciones = AccionesContadas()
        val sesion = sesionDirecta(acciones, permitirReconstruccion = false)
        try {
            esperarDirecto("reproducción directa") {
                it.estado == EstadoReproductor.REPRODUCIENDO
            }
            composeRule.runOnUiThread { sesion.escaleraImagen.forzarCaso(CausaImagen.SUPERFICIE_PERDIDA) }

            // El re-attach externo ejecutado, la sesión reprepara con su motor
            // (se observa el estado publicado) y la escalera termina pegajosa.
            esperarDirecto("restauración de superficie") {
                acciones.restauraciones >= 1
            }
            esperarDirecto("repreparación con el motor propio") {
                it.estadoImagen == EstadoImagen.REINICIALIZANDO_RENDERER
            }
            esperarDirecto("terminal de la escalera") {
                it.estadoImagen == EstadoImagen.ERROR_DECODIFICACION ||
                    it.estadoImagen == EstadoImagen.ERROR_RENDERIZADO
            }
            assertEquals(
                "La escalera restauró la superficie más de una vez",
                1,
                acciones.restauraciones,
            )
            assertEquals(
                "La escalera reconstruyó sin permiso",
                0,
                acciones.reconstrucciones,
            )
            val posicionAntes = posicionDirecta()
            Thread.sleep(3_000)
            assertEquals(
                "El terminal pegajoso reabrió niveles (bucle)",
                1,
                acciones.restauraciones,
            )
            assertTrue(
                "El audio no siguió vivo tras agotar la escalera",
                posicionDirecta() > posicionAntes,
            )
            assertFalse("La escalera liberó la sesión", sesion.estaLiberada())
        } finally {
            liberarDirecta(sesion)
        }
    }

    @Test
    fun laReconstruccionEsElUltimoNivelDeLaEscalera() {
        val acciones = AccionesContadas()
        val sesion = sesionDirecta(acciones, permitirReconstruccion = true)
        try {
            esperarDirecto("reproducción directa") {
                it.estado == EstadoReproductor.REPRODUCIENDO
            }
            composeRule.runOnUiThread { sesion.escaleraImagen.forzarCaso(CausaImagen.SUPERFICIE_PERDIDA) }

            esperarDirecto("reconstrucción de la sesión") {
                acciones.reconstrucciones >= 1
            }
            assertEquals("La escalera restauró la superficie más de una vez", 1, acciones.restauraciones)
            assertTrue(
                "La reconstrucción no recibió la posición de la sesión viva",
                (acciones.ultimaPosicionReconstruida ?: -1L) >= 0L,
            )
            assertFalse("La escalera liberó la sesión", sesion.estaLiberada())
        } finally {
            liberarDirecta(sesion)
        }
    }

    // ===================== Ayudas =====================

    private fun abrir(ruta: String) {
        sembrarCanal(catalogo, FixturesF19.url(servidor, ruta))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
    }

    private fun sembrarDosCanales() {
        runBlocking {
            val texto =
                buildString {
                    append("#EXTM3U\n")
                    listOf("Canal A", "Canal B").forEachIndexed { indice, nombre ->
                        append("#EXTINF:-1 tvg-id=\"e7t10img$indice\",$nombre\n")
                        append(FixturesF19.url(servidor, FixturesF19.PISTAS))
                        append("\n")
                    }
                }
            catalogo.importar(texto, "Fuente Imagen", "http://example.invalid/imagen.m3u")
        }
    }

    /**
     * Construye la sesión de la prueba en el hilo principal, como exige
     * Media3, y la deja accesible para los esperas de estado. No se adjunta
     * superficie: la prueba de la escalera depende de que la superficie
     * nunca vuelva.
     */
    private fun sesionDirecta(
        acciones: AccionesImagen,
        permitirReconstruccion: Boolean,
    ): SesionMedia3 {
        composeRule.runOnUiThread {
            sesionDirectaReferencia.set(
                SesionMedia3(
                    composeRule.activity,
                    FixturesF19.url(servidor, FixturesF19.PISTAS),
                    ProtocoloStream.PROGRESIVO,
                    PreferenciasPista(),
                    intervaloMs = 250L,
                    configuracionImagen = ConfiguracionImagen(ventanaConfirmacionMs = 1_000L),
                    permitirReconstruccion = permitirReconstruccion,
                    accionesImagen = acciones,
                ),
            )
        }
        return sesionDirectaReferencia.get() ?: error("No se creó la sesión directa")
    }

    private fun liberarDirecta(sesion: SesionMedia3) {
        composeRule.runOnUiThread {
            sesion.liberar()
            sesionDirectaReferencia.set(null)
        }
    }

    private fun posicionDirecta(): Long =
        esperarDirecto("posición avanzando en la sesión directa") {
            it.posicionMs > 0L
        }.posicionMs

    private fun esperarSesionVm(
        descripcion: String,
        predicado: (EstadoSesion) -> Boolean,
    ): EstadoSesion =
        try {
            runBlocking {
                withTimeout(ESPERA_TEST_MS) {
                    composeRule.viewModelReproductor().sesionEstado.first(predicado)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError(
                "No llegó $descripcion en $ESPERA_TEST_MS ms. " +
                    "estado=${composeRule.viewModelReproductor().sesionEstado.value}",
                error,
            )
        }

    private fun esperarDirecto(
        descripcion: String,
        predicado: (EstadoSesion) -> Boolean,
    ): EstadoSesion {
        val sesion =
            sesionDirectaReferencia.get() ?: error("Sesión directa no creada")
        return try {
            runBlocking {
                withTimeout(ESPERA_TEST_MS) {
                    sesion.estado.first(predicado)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError(
                "No llegó $descripcion en $ESPERA_TEST_MS ms. estado=${sesion.estado.value}",
                error,
            )
        }
    }

    /**
     * Acciones de imagen que cuentan sus ejecuciones. Los niveles que la
     * sesión ejecuta con su propio motor (REPONER_SELECCION, REPREPARAR) se
     * observan por el estado publicado, no por aquí.
     */
    private class AccionesContadas : AccionesImagen {
        var restauraciones = 0
        var reconstrucciones = 0
        var ultimaPosicionReconstruida: Long? = null

        override fun restaurarSuperficie() {
            restauraciones += 1
        }

        override fun reconstruirSesion(posicionMs: Long) {
            reconstrucciones += 1
            ultimaPosicionReconstruida = posicionMs
        }
    }
}
