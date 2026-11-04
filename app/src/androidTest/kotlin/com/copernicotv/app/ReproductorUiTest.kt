package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ERROR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T1 en un emulador: un Canal real se abre desde el catálogo y Media3
 * reproduce un MP4 progresivo y un HLS servidos por HTTP local.
 *
 * No se simula el motor: se lee `playbackState` y `currentPosition` de la sesión
 * real a través de la etiqueta de estado de la pantalla. El caso de error usa
 * un recurso ausente y comprueba el código propio, sin URL (S57a).
 */
@HiltAndroidTest
class ReproductorUiTest {
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
    fun unMp4ProgresivoReproduceYAvanceLaPosicion() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))

        composeRule.abrirReproductor()

        esperarReproduccionAvanzando()
    }

    @Test
    fun unHlsReproduceYAvanceLaPosicion() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.HLS))

        composeRule.abrirReproductor()

        esperarReproduccionAvanzando()
    }

    @Test
    fun unRecursoAusenteMuestraUnCodigoPropioSinUrl() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, "f15/ausente.mp4"))

        composeRule.abrirPantallaReproductor()

        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR)
        if (texto.contains("127.0.0.1") || texto.contains("ausente")) {
            throw AssertionError("El error expone la URL del flujo: $texto")
        }
    }

    /**
     * Espera a que el motor informe un estado reproducible y una posición
     * mayor que cero. Acepta `READY` o `ENDED` porque el fixture puede terminar
     * antes de que el sondeo lea la etiqueta.
     */
    private fun esperarReproduccionAvanzando() {
        composeRule.esperarHasta {
            val (estado, posicion) = composeRule.estadoMotor()
            (estado == "READY" || estado == "ENDED") && posicion > 0L
        }
    }
}
