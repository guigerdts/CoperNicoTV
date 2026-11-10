package com.copernicotv.app

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.ReproductorUiState
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ANTERIOR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ERROR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SIGUIENTE
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLUMEN_BAJAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLUMEN_SUBIR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLUMEN_VALOR
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T6 en emulador móvil: zapeo al Canal siguiente y anterior sin volver al
 * Catálogo, límites de navegación, cambio rápido consecutivo, volumen con sus
 * límites y la sincronía con Media3, y el error tipado cuando el Canal destino
 * falla.
 */
@HiltAndroidTest
class ReproductorZapeoTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private lateinit var servidor: ServidorMedios

    private lateinit var ids: Map<String, Long>

    @Before
    fun preparar() {
        hiltRule.inject()
        runBlocking { db.clearAllTables() }
        FixturesF15.verificar(InstrumentationRegistry.getInstrumentation().context.assets)
        servidor = ServidorMedios(InstrumentationRegistry.getInstrumentation().context.assets).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun elZapeoSiguienteCambiaDeCanalSinVolverAlCatalogo() {
        sembrarTresCanales()
        abrirPrimerCanal()
        val viewModel = composeRule.viewModelReproductor()
        val sesionInicial = requireNotNull(viewModel.sesionActual())

        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()

        esperarCanal(ids.getValue("Canal B"))
        composeRule.esperarFase("Reproduciendo")
        // La sesión del Canal A se cerró y la sustituyó una sesión nueva:
        // el cambio de Canal no volvió a abrir el Catálogo ni reinició la
        // maquinaria de E6-T4.
        composeRule.esperarHasta {
            viewModel.sesionActual() != null && viewModel.sesionActual() !== sesionInicial
        }
    }

    @Test
    fun elZapeoAnteriorVuelveAlCanalPrecedente() {
        sembrarTresCanales()
        abrirPrimerCanal()
        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()
        esperarCanal(ids.getValue("Canal B"))
        composeRule.esperarFase("Reproduciendo")

        composeRule.mostrarControlesConToque()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ANTERIOR).performClick()

        esperarCanal(ids.getValue("Canal A"))
        composeRule.esperarFase("Reproduciendo")
    }

    @Test
    fun losLimitesDeNavegacionDeshabilitanLosBotones() {
        sembrarTresCanales()
        abrirPrimerCanal()
        composeRule.mostrarControlesConToque()

        // Primer Canal: sin anterior, con siguiente.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ANTERIOR).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).assertIsEnabled()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()
        esperarCanal(ids.getValue("Canal B"))
        composeRule.esperarFase("Reproduciendo")
        composeRule.mostrarControlesConToque()

        // Canal intermedio: dos vecinos.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ANTERIOR).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).assertIsEnabled()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()
        esperarCanal(ids.getValue("Canal C"))
        composeRule.esperarFase("Reproduciendo")
        composeRule.mostrarControlesConToque()

        // Último Canal: sin siguiente, con anterior.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ANTERIOR).assertIsEnabled()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).assertIsNotEnabled()
    }

    @Test
    fun unCanalUnicoDeshabilitaAmbosBotonesDeZapeo() {
        sembrarCanales("Canal A" to FixturesF15.url(servidor, FixturesF15.MP4))
        abrirPrimerCanal()
        composeRule.mostrarControlesConToque()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ANTERIOR).assertIsNotEnabled()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).assertIsNotEnabled()
    }

    @Test
    fun elZapeoRapidoConsecutivoMantieneLaCoherenciaDelCanalYLaSesion() {
        sembrarTresCanales()
        abrirPrimerCanal()
        composeRule.mostrarControlesConToque()
        val viewModel = composeRule.viewModelReproductor()

        // Dos zapeos casi simultáneos: la maquinaria de sustitución de E6-T4
        // debe terminar con una sola sesión viva, coherente con el Canal
        // publicado y sin estado de error.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()

        composeRule.esperarHasta {
            val estado = viewModel.estado.value
            estado is ReproductorUiState.Listo && estado.canalId != ids.getValue("Canal A")
        }
        composeRule.esperarHasta { viewModel.sesionActual() != null }
        composeRule.esperarFase("Reproduciendo")
        assertNotNull(viewModel.motor)
    }

    @Test
    fun elVolumenSeAplicaAlMotorRespetaLimitesYSeConservaEnElZapeo() {
        sembrarTresCanales()
        abrirPrimerCanal()
        val viewModel = composeRule.viewModelReproductor()
        composeRule.mostrarControlesConToque()

        repeat(5) { composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLUMEN_BAJAR).performClick() }
        composeRule.esperarHasta { viewModel.motor?.volume == 0.5f }
        assertEquals("Volumen: 50%", composeRule.textoDeTag(TAG_REPRODUCTOR_VOLUMEN_VALOR))

        // El volumen vigente sobrevive al zapeo: la sesión nueva lo hereda.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()
        esperarCanal(ids.getValue("Canal B"))
        composeRule.esperarFase("Reproduciendo")
        assertEquals(0.5f, viewModel.motor?.volume)

        composeRule.mostrarControlesConToque()
        repeat(6) { composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLUMEN_BAJAR).performClick() }
        composeRule.esperarHasta { viewModel.motor?.volume == 0.0f }
        assertEquals("Volumen: 0%", composeRule.textoDeTag(TAG_REPRODUCTOR_VOLUMEN_VALOR))
        // Límite inferior: otro paso no inventa un volumen negativo.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLUMEN_BAJAR).performClick()
        assertEquals(0.0f, viewModel.motor?.volume)

        repeat(10) { composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLUMEN_SUBIR).performClick() }
        composeRule.esperarHasta { viewModel.motor?.volume == 1.0f }
        assertEquals("Volumen: 100%", composeRule.textoDeTag(TAG_REPRODUCTOR_VOLUMEN_VALOR))
        // Límite superior: otro paso no se pasa de la escala de Media3.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_VOLUMEN_SUBIR).performClick()
        assertEquals(1.0f, viewModel.motor?.volume)
    }

    @Test
    fun elErrorDelCanalDestinoTerminaEnElErrorTipadoConLaSesionCerrada() {
        sembrarCanales(
            "Canal A" to FixturesF15.url(servidor, FixturesF15.MP4),
            "Canal B" to FixturesF15.url(servidor, "f15/ausente.mp4"),
        )
        abrirPrimerCanal()
        composeRule.mostrarControlesConToque()

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SIGUIENTE).performClick()

        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR)
        assertTrue("El error debe tipar el 4xx: $texto", texto.contains("http_cliente"))
        composeRule.esperarHasta { composeRule.viewModelReproductor().sesionActual() == null }
    }

    private fun abrirPrimerCanal() {
        composeRule.abrirReproductor()
        composeRule.esperarFase("Reproduciendo")
        esperarCanal(ids.getValue("Canal A"))
    }

    private fun esperarCanal(canalId: Long) {
        composeRule.esperarHasta {
            (composeRule.viewModelReproductor().estado.value as? ReproductorUiState.Listo)?.canalId == canalId
        }
    }

    /** Siembra Canales en una única Fuente, en orden de importación. */
    private fun sembrarCanales(
        vararg pares: Pair<String, String>,
    ) {
        ids =
            runBlocking {
                val texto = buildString {
                    append("#EXTM3U\n")
                    pares.forEachIndexed { indice, (nombre, url) ->
                        append("#EXTINF:-1 tvg-id=\"e6zapeo$indice\",$nombre\n$url\n")
                    }
                }
                catalogo.importar(texto, "Fuente Zapeo", "http://example.invalid/zapeo.m3u")
                db.canalDao().todos().associate { it.nombre.orEmpty() to it.id }
            }
    }

    private fun sembrarTresCanales() {
        sembrarCanales(
            "Canal A" to FixturesF15.url(servidor, FixturesF15.MP4),
            "Canal B" to FixturesF15.url(servidor, FixturesF15.MP4),
            "Canal C" to FixturesF15.url(servidor, FixturesF15.MP4),
        )
    }
}
