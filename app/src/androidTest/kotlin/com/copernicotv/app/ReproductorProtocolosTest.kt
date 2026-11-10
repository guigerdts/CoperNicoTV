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
 * E6-T1 en un emulador: reproducción real de MPEG-DASH y SmoothStreaming y
 * error de formato, sobre HTTP local.
 *
 * Los fixtures DASH y SmoothStreaming se generaron con ffmpeg y viajan en el
 * APK de pruebas; la clasificación de la URL llega a la sesión como
 * `mimeType` del `MediaItem`, así que la fábrica selecciona la fuente por el
 * protocolo declarado y no por la extensión. Lo que se comprueba es el motor
 * real: estado `READY`/`ENDED` y posición en avance, sin simular Media3.
 *
 * RTSP y RTMP no se reproducen aquí: no hay servidor RTSP en el entorno de
 * pruebas y RTMP se rechaza antes de la sesión (`stream_rtmp_no_soportado`,
 * cubierto por pruebas JVM de `:core`).
 */
@HiltAndroidTest
class ReproductorProtocolosTest {
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
    fun unDashReproduceYAvanzaLaPosicion() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.DASH))

        composeRule.abrirReproductor()

        composeRule.esperarReproduccionAvanzando()
    }

    @Test
    fun unSmoothStreamingReproduceYAvanzaLaPosicion() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.SMOOTH))

        composeRule.abrirReproductor()

        composeRule.esperarReproduccionAvanzando()
    }

    @Test
    fun unContenidoCorruptoMuestraUnErrorSinUrl() {
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.CORRUPTO))

        composeRule.abrirPantallaReproductor()

        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR)
        if (texto.contains("127.0.0.1") || texto.contains("corrupto")) {
            throw AssertionError("El error expone la URL del flujo: $texto")
        }
    }
}
