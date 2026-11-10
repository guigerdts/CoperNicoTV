package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T1 en el emulador de Android TV: reproducción real de MPEG-DASH.
 *
 * La TV comparte la misma sesión Media3 y la misma clasificación de `:core`
 * que el móvil; este canario comprueba que la reproducción DASH con la fuente
 * seleccionada por protocolo también arranca en el motor de TV.
 */
@HiltAndroidTest
class ReproductorProtocolosTvTest {
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
    fun unDashReproduceEnTv() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.DASH))

        composeRule.abrirReproductor()

        composeRule.esperarReproduccionAvanzando()
    }
}
