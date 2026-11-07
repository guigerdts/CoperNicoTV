package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.ui.ReproductorUiState
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ERROR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_REINTENTO
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * E6-T4 en emulador móvil: recuperación y reintentos de S20.
 *
 * La espera entre reintentos se sustituye por [PuertaReintentos]: cada intento
 * queda suspendido hasta que la prueba lo libera. Así se observan los tres
 * reintentos, el agotamiento, la cancelación y el error definitivo sin dormir y
 * sin depender del reloj. El motor es Media3 real y el servidor HTTP local
 * responde 500 o 404 según la prueba.
 */
@HiltAndroidTest
class ReproductorReintentosTest {
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
        FixturesF15.verificar(InstrumentationRegistry.getInstrumentation().context.assets)
        PuertaReintentos.reiniciar()
    }

    @After
    fun cerrar() {
        if (::servidor.isInitialized) servidor.close()
    }

    @Test
    fun elFalloTransitorioReintentaYLlegaAReadSinRecrearLaSesion() {
        // El fallo se mantiene hasta que la prueba lo apaga: así no depende de
        // cuántas peticiones haga Media3 antes de la primera carga útil.
        val fallar = AtomicBoolean(true)
        servidor =
            servidorConFallos { ruta, _ ->
                if (ruta == FixturesF15.MP4 && fallar.get()) 500 else null
            }
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))

        composeRule.abrirReproductor()

        val reintento = esperarSesion("primer reintento") { it.estado == EstadoReproductor.REINTENTANDO }
        assertEquals(1, reintento.reintento)
        assertEquals(3, reintento.reintentosMaximos)
        composeRule.esperarTag(TAG_REPRODUCTOR_REINTENTO)
        assertTrue(composeRule.textoDeTag(TAG_REPRODUCTOR_REINTENTO).contains("1 de 3"))
        val sesion = requireNotNull(composeRule.viewModelReproductor().sesionActual())

        fallar.set(false)
        PuertaReintentos.liberar()

        esperarSesion("recuperación tras el reintento") {
            it.estado == EstadoReproductor.REPRODUCIENDO || it.estado == EstadoReproductor.PAUSADO
        }
        assertSame(sesion, composeRule.viewModelReproductor().sesionActual())
        assertTrue(servidor.peticionesDe(FixturesF15.MP4) >= 2)
    }

    @Test
    fun un404NoSeReintentaYTerminaEnErrorTipado() {
        servidor = servidorConFallos { _, _ -> null }
        sembrarCanal(catalogo, FixturesF15.url(servidor, "f15/ausente.mp4"))

        composeRule.abrirPantallaReproductor()

        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR)
        assertTrue("El error debe tipar el 4xx: $texto", texto.contains("http_cliente"))
        assertEquals(0, PuertaReintentos.esperasRegistradas)
        assertNull(composeRule.viewModelReproductor().sesionActual())
        // Una sola petición: ni la aplicación ni Media3 reintentan un 4xx.
        assertEquals(1, servidor.peticionesDe("f15/ausente.mp4"))
    }

    @Test
    fun losTresReintentosSeVenEnLaInterfazYTerminanEnErrorDefinitivo() {
        servidor = servidorConFallos { ruta, _ -> if (ruta == FixturesF15.MP4) 500 else null }
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))

        composeRule.abrirReproductor()

        repeat(3) { indice ->
            val numero = indice + 1
            esperarSesion("reintento $numero de 3") {
                it.estado == EstadoReproductor.REINTENTANDO && it.reintento == numero
            }
            composeRule.esperarTag(TAG_REPRODUCTOR_REINTENTO)
            assertTrue(
                "El aviso debe numerar el reintento $numero",
                composeRule.textoDeTag(TAG_REPRODUCTOR_REINTENTO).contains("$numero de 3"),
            )
            PuertaReintentos.liberar()
        }

        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_REINTENTO)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR)
        assertTrue("El error definitivo debe tipar el 5xx: $texto", texto.contains("http_servidor"))
        // Un intento por cada prepare: el inicial más los tres de S20.
        assertEquals(4, servidor.peticionesDe(FixturesF15.MP4))
        assertNull(composeRule.viewModelReproductor().sesionActual())
    }

    @Test
    fun cambiarDeCanalCancelaElReintentoPendiente() {
        servidor = servidorConFallos { ruta, _ -> if (ruta == FixturesF15.MP4) 500 else null }
        val (canalA, canalB) =
            sembrarDosCanales(
                FixturesF15.url(servidor, FixturesF15.MP4),
                FixturesF15.url(servidor, FixturesF19.PISTAS),
            )
        composeRule.abrirReproductor()
        val viewModel = composeRule.viewModelReproductor()
        // Deja el Canal que falla al frente sin depender del orden del catálogo.
        composeRule.runOnUiThread { viewModel.cargar(canalA) }
        esperarSesion("reintento del Canal A") { it.estado == EstadoReproductor.REINTENTANDO }
        val sesionA = requireNotNull(viewModel.sesionActual())

        composeRule.runOnUiThread { viewModel.cargar(canalB) }

        // `cargar` suelta A y abre B en el mismo trabajo: hay una ventana con
        // `sesion == null`. Esperar una sesión nueva y distinta evita leerla.
        composeRule.waitUntil(ESPERA_ESTADO_MS) {
            sesionA.estaLiberada() && viewModel.sesionActual()?.let { it !== sesionA } == true
        }
        val sesionB = requireNotNull(viewModel.sesionActual())
        PuertaReintentos.liberar()
        esperarSesion("reproducción del Canal B") {
            it.estado == EstadoReproductor.REPRODUCIENDO || it.estado == EstadoReproductor.PAUSADO
        }
        assertSame(sesionB, viewModel.sesionActual())
        assertTrue(viewModel.estado.value is ReproductorUiState.Listo)
    }

    @Test
    fun salirDelReproductorCancelaElReintentoPendiente() {
        servidor = servidorConFallos { ruta, _ -> if (ruta == FixturesF15.MP4) 500 else null }
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        esperarSesion("reintento antes de salir") { it.estado == EstadoReproductor.REINTENTANDO }
        val viewModel = composeRule.viewModelReproductor()
        val sesion = requireNotNull(viewModel.sesionActual())

        composeRule.runOnUiThread { viewModel.cerrar() }

        assertTrue(sesion.estaLiberada())
        PuertaReintentos.liberar()
        assertNull(viewModel.sesionActual())
        assertEquals(ReproductorUiState.Cargando, viewModel.estado.value)
        assertEquals(EstadoSesion(), viewModel.sesionEstado.value)
    }

    @Test
    fun unCallbackAntiguoNoModificaLaSesionNueva() {
        servidor = servidorConFallos { ruta, _ -> if (ruta == FixturesF15.MP4) 500 else null }
        val (canalA, canalB) =
            sembrarDosCanales(
                FixturesF15.url(servidor, FixturesF15.MP4),
                FixturesF15.url(servidor, FixturesF19.PISTAS),
            )
        composeRule.abrirReproductor()
        val viewModel = composeRule.viewModelReproductor()
        composeRule.runOnUiThread { viewModel.cargar(canalA) }
        esperarSesion("reintento del Canal A") { it.estado == EstadoReproductor.REINTENTANDO }
        val sesionA = requireNotNull(viewModel.sesionActual())

        composeRule.runOnUiThread { viewModel.cargar(canalB) }
        composeRule.waitUntil(ESPERA_ESTADO_MS) {
            viewModel.sesionActual()?.let { it !== sesionA } == true
        }
        val sesionB = requireNotNull(viewModel.sesionActual())

        PuertaReintentos.liberar()

        esperarSesion("reproducción del Canal B") {
            it.estado == EstadoReproductor.REPRODUCIENDO || it.estado == EstadoReproductor.PAUSADO
        }
        assertSame(sesionB, viewModel.sesionActual())
        assertNull(viewModel.sesionEstado.value.codigoError)
        assertTrue(viewModel.estado.value is ReproductorUiState.Listo)
    }

    private fun servidorConFallos(decidir: (ruta: String, peticion: Int) -> Int?): ServidorMedios =
        ServidorMedios(InstrumentationRegistry.getInstrumentation().context.assets, decidirFallo = decidir).also { it.iniciar() }

    private fun esperarSesion(
        descripcion: String,
        predicado: (EstadoSesion) -> Boolean,
    ): EstadoSesion =
        try {
            runBlocking {
                withTimeout(ESPERA_ESTADO_MS) {
                    composeRule.viewModelReproductor().sesionEstado.first(predicado)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError(diagnostico(descripcion), error)
        }

    /** Contexto acotado del fallo: nunca incluye la URL del flujo (S57a). */
    private fun diagnostico(descripcion: String): String {
        val viewModel = composeRule.viewModelReproductor()
        val ui = viewModel.estado.value
        return buildString {
            append("No se alcanzó «").append(descripcion).append("» en ").append(ESPERA_ESTADO_MS).append(" ms.")
            append(" estadoSesion=").append(viewModel.sesionEstado.value)
            append(" estadoUi=").append(if (ui is ReproductorUiState.Listo) "Listo" else ui.toString())
            append(" haySesion=").append(viewModel.sesionActual() != null)
            append(" esperasRegistradas=").append(PuertaReintentos.esperasRegistradas)
            append(" peticionesMp4=").append(servidor.peticionesDe(FixturesF15.MP4))
        }
    }

    private fun sembrarDosCanales(
        urlA: String,
        urlB: String,
    ): Pair<Long, Long> =
        runBlocking {
            val texto =
                "#EXTM3U\n#EXTINF:-1 tvg-id=\"e6a\",Canal A\n$urlA\n" +
                    "#EXTINF:-1 tvg-id=\"e6b\",Canal B\n$urlB\n"
            catalogo.importar(texto, "Fuente Reintentos", "http://example.invalid/reintentos.m3u")
            val canales = db.canalDao().todos()
            val canalA = canales.first { it.nombre == "Canal A" }.id
            val canalB = canales.first { it.nombre == "Canal B" }.id
            canalA to canalB
        }

    private companion object {
        const val ESPERA_ESTADO_MS = 15_000L
    }
}
