package com.copernicotv.app

import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.media3.exoplayer.ExoPlayer
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
 * E7-T6 (S42d) en un emulador: enviar el reproductor a segundo plano y
 * traerlo de vuelta conserva la sesión. El motor vive en el ViewModel, así
 * que la instancia es la misma, la posición no se reinicia y la reproducción
 * sigue avanzando; se lee el estado y la posición en cada paso. También la
 * recreación de la Activity conserva la sesión y la posición.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class ReproductorCicloVidaTest {
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
        servidor = ServidorMedios(assets).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun elSegundoPlanoConservaLaMismaSesionYLaPosicion() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
        composeRule.esperarHasta { composeRule.posicionMs() > 1_000L }

        val viewModel = composeRule.viewModelReproductor()
        var motorAntes: ExoPlayer? = null
        composeRule.activityRule.scenario.onActivity { motorAntes = viewModel.motor }

        // Segundo plano: la Activity queda detenida (onStop) y el motor no
        // se recrea; la posición sigue avanzando.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        var posicionEnSegundoPlano = -1L
        var reproduciaEnSegundoPlano = false
        composeRule.activityRule.scenario.onActivity {
            posicionEnSegundoPlano = viewModel.motor?.currentPosition ?: -1L
            reproduciaEnSegundoPlano = viewModel.motor?.isPlaying == true
        }

        // Primer plano: la sesión es la misma instancia y la reproducción
        // continúa desde la posición conservada.
        composeRule.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        var motorDespues: ExoPlayer? = null
        composeRule.activityRule.scenario.onActivity { motorDespues = viewModel.motor }
        assertEquals("El motor se recreó al volver de segundo plano", motorAntes, motorDespues)
        assertTrue(
            "La posición se reinició en segundo plano: $posicionEnSegundoPlano",
            posicionEnSegundoPlano > 0L,
        )
        assertTrue("La reproducción se detuvo en segundo plano", reproduciaEnSegundoPlano)

        composeRule.esperarFase("Reproduciendo")
        composeRule.esperarHasta { composeRule.posicionMs() > posicionEnSegundoPlano }
    }

    @Test
    fun laSesionSobreviveARecreacionDeActivity() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))
        composeRule.abrirReproductor()
        composeRule.esperarFase("Reproduciendo")
        composeRule.esperarHasta { composeRule.posicionMs() > 1_000L }

        val viewModel = composeRule.viewModelReproductor()
        var motorAntes: ExoPlayer? = null
        composeRule.activityRule.scenario.onActivity { motorAntes = viewModel.motor }
        val posicionAntes = composeRule.posicionMs()

        // La Activity se destruye y se reconstruye (cambio de configuración):
        // el ViewModel y su sesión Media3 sobreviven, la navegación vuelve al
        // reproductor y la reproducción continúa desde la posición previa.
        // Esto NO es muerte del proceso: ahí el arnés E3 cubre la renovación.
        composeRule.activityRule.scenario.recreate()
        composeRule.esperarFase("Reproduciendo")
        var motorDespues: ExoPlayer? = null
        var posicionInmediata = -1L
        composeRule.activityRule.scenario.onActivity {
            motorDespues = viewModel.motor
            posicionInmediata = viewModel.motor?.currentPosition ?: -1L
        }
        assertEquals("La recreación de Activity recreó el motor", motorAntes, motorDespues)
        assertTrue(
            "La sesión arrancó desde cero tras recrear la Activity: $posicionInmediata",
            posicionInmediata >= posicionAntes,
        )

        composeRule.esperarHasta { composeRule.posicionMs() > posicionInmediata }
    }
}
