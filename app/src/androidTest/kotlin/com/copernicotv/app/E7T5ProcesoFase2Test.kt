package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ASPECTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AUDIO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AVANZAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_FASE
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PROGRESO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_RETROCEDER
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SECUNDARIOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SUBTITULOS
import com.copernicotv.app.ui.TAG_REPRODUCTOR_VOLVER
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E7-T5 (S44b, S17b), fase 2: se ejecuta después de `am force-stop` y de una
 * nueva invocación de instrumentación. Comprueba que el modo de aspecto
 * guardado se restauró y se aplica a la `PlayerView`, y que los controles
 * secundarios siguen ocultos; Volver, play/pausa, progreso y fase siguen
 * presentes. Al volver a mostrar los secundarios, reaparecen los cinco.
 *
 * No hay limpieza de Room ni del DataStore en `@Before`: los datos son los
 * que quedaron en disco al terminar el proceso anterior. El servidor de esta
 * fase usa un puerto efímero nuevo, así que la siembra re-importa el mismo
 * `tvg-id="e6"` y la reconciliación de ADR-0004 actualiza la URL del Canal.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class E7T5ProcesoFase2Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    private lateinit var marca: MarcaE7T5
    private lateinit var servidor: ServidorMedios

    @Before
    fun preparar() {
        hiltRule.inject()
        marca = leerMarcaE7T5(contexto)
        assertNotEquals(
            "La fase 2 se ejecutó en el mismo proceso que la fase 1",
            marca.identidad,
            identidadProceso(),
        )
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        FixturesF19.verificar(assets)
        servidor = ServidorMedios(assets).also { it.iniciar() }

        sembrarCanal(catalogo, FixturesF19.url(servidor, FixturesF19.PISTAS))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun trasNuevoProcesoElModoSeRestauraYLosSecundariosSiguenOcultos() {
        assertEquals("RELLENAR", marca.modoAspecto)
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Rellenar")
        composeRule.esperarResizeModeFillE7T5()

        // S17b: ocultos exactamente los cinco secundarios; el control de la
        // preferencia y los controles primarios permanecen.
        composeRule.esperarTag(TAG_REPRODUCTOR_SECUNDARIOS)
        composeRule.esperarTag(TAG_REPRODUCTOR_ALTERNAR)
        composeRule.esperarTag(TAG_REPRODUCTOR_VOLVER)
        composeRule.esperarTag(TAG_REPRODUCTOR_PROGRESO)
        composeRule.esperarTag(TAG_REPRODUCTOR_FASE)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_RETROCEDER)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AVANZAR)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_ASPECTO)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AUDIO)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_SUBTITULOS)

        // Volver a mostrar los secundarios funciona tras el reinicio real.
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SECUNDARIOS).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_RETROCEDER)
        composeRule.esperarTag(TAG_REPRODUCTOR_AVANZAR)
        composeRule.esperarTag(TAG_REPRODUCTOR_ASPECTO)
        composeRule.esperarTag(TAG_REPRODUCTOR_AUDIO)
        composeRule.esperarTag(TAG_REPRODUCTOR_SUBTITULOS)
    }
}
