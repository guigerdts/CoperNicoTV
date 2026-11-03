package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ERROR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_REINTENTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLVER
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T4 en el emulador de Android TV: recuperación y error definitivo con
 * D-pad.
 *
 * La espera del reintento se libera desde la prueba con [PuertaReintentos]; el
 * aviso no debe robar el foco de los controles ni dejar la pantalla sin salida
 * cuando S20 se agota.
 */
@HiltAndroidTest
class ReproductorReintentosTvTest {
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
        PuertaReintentos.reiniciar()
        servidor =
            ServidorMedios(assets) { ruta, peticion ->
                if (ruta == FixturesF15.MP4 && peticion == 1) 500 else null
            }.also { it.iniciar() }
    }

    @After
    fun cerrar() {
        if (::servidor.isInitialized) servidor.close()
    }

    @Test
    fun elAvisoDeReintentoEsVisibleYNoPierdeElFocoEnTv() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConDpad()

        val reintento = esperarSesion { it.estado == EstadoReproductor.REINTENTANDO }
        assertEquals(1, reintento.reintento)
        assertEquals(3, reintento.reintentosMaximos)
        composeRule.mostrarControlesConDpad()
        composeRule.esperarFase("Reintentando")
        composeRule.esperarTag(TAG_REPRODUCTOR_REINTENTO)
        assertTrue(composeRule.textoDeTag(TAG_REPRODUCTOR_REINTENTO).contains("1 de 3"))
        composeRule.esperarFocoEnAlternar()
        val sesion = requireNotNull(composeRule.viewModelReproductor().sesionActual())

        PuertaReintentos.liberar()

        esperarSesion {
            it.estado == EstadoReproductor.REPRODUCIENDO || it.estado == EstadoReproductor.PAUSADO
        }
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_REINTENTO)
        assertSame(sesion, composeRule.viewModelReproductor().sesionActual())
    }

    @Test
    fun elErrorDefinitivoSeMuestraConVolverEnTv() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, "f15/ausente.mp4"))
        composeRule.abrirReproductor()

        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        composeRule.esperarTag(TAG_REPRODUCTOR_VOLVER)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR)
        assertTrue("El error debe tipar el 4xx: $texto", texto.contains("http_cliente"))
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_REINTENTO)
    }

    private fun esperarSesion(predicado: (EstadoSesion) -> Boolean): EstadoSesion =
        runBlocking {
            withTimeout(ESPERA_ESTADO_MS) {
                composeRule.viewModelReproductor().sesionEstado.first(predicado)
            }
        }

    private companion object {
        const val ESPERA_ESTADO_MS = 15_000L
    }
}
