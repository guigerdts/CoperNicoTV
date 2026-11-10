package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.ui.TAG_REPRODUCTOR_DIAGNOSTICO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PANEL_DIAGNOSTICO
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T5 en el emulador de Android TV: el panel se abre, el texto es legible y
 * Back lo cierra devolviendo el foco al disparador, sin abandonar la
 * reproducción.
 */
@HiltAndroidTest
class ReproductorDiagnosticoTvTest {
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
    fun elPanelSeAbreConTextoLegibleYBackDevuelveElFoco() {
        sembrarCanal(catalogo, FixturesF19.url(servidor, FixturesF19.PISTAS))
        composeRule.abrirReproductor()
        composeRule.esperarFase("Reproduciendo")
        composeRule.mostrarControlesConDpad()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_DIAGNOSTICO).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_DIAGNOSTICO)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO)
        assertTrue("El panel no muestra el protocolo: $texto", texto.contains("Protocolo:"))
        assertTrue("El panel no muestra las pistas: $texto", texto.contains("Audio disponible:"))
        assertTrue("El panel no muestra el búfer: $texto", texto.contains("Búfer:"))

        composeRule.runOnUiThread { composeRule.activity.onBackPressedDispatcher.onBackPressed() }
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_PANEL_DIAGNOSTICO)
        composeRule.esperarFocoEn(TAG_REPRODUCTOR_DIAGNOSTICO)
    }
}
