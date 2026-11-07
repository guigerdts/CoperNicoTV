package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ASPECTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_AVANZAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_RETROCEDER
import com.copernicotv.app.ui.TAG_REPRODUCTOR_SECUNDARIOS
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import org.junit.After
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E7-T5 (S44b, S17b), fase 1: cambia el modo de aspecto y oculta los
 * controles secundarios desde la UI del reproductor, y deja la marca que
 * leerá la fase 2 después de `am force-stop` y una nueva invocación de
 * instrumentación.
 */
@OptIn(ExperimentalTestApi::class)
@HiltAndroidTest
class E7T5ProcesoFase1Test {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private lateinit var servidor: ServidorMedios

    private val contexto: Context
        get() = InstrumentationRegistry.getInstrumentation().targetContext

    @Before
    fun preparar() {
        hiltRule.inject()
        limpiarBaseE7T5(db)
        borrarMarcaE7T5(contexto)
        val assets = InstrumentationRegistry.getInstrumentation().context.assets
        FixturesF19.verificar(assets)
        servidor = ServidorMedios(assets, puertoFijo = PUERTO_E7T5).also { it.iniciar() }
    }

    @After
    fun cerrar() {
        servidor.close()
    }

    @Test
    fun cambiaElModoYOcultaLosSecundariosDesdeLaUi() {
        sembrarCanal(catalogo, FixturesF19.url(servidor, FixturesF19.PISTAS))
        composeRule.abrirReproductor()
        composeRule.mostrarControlesConToque()
        composeRule.esperarFase("Reproduciendo")

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_ASPECTO).performClick()
        composeRule.esperarTexto(TAG_REPRODUCTOR_ASPECTO, "Rellenar")
        composeRule.onNodeWithTag(TAG_REPRODUCTOR_SECUNDARIOS).performClick()
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_RETROCEDER)
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_AVANZAR)

        escribirMarcaE7T5(
            contexto,
            MarcaE7T5(
                identidad = identidadProceso(),
                modoAspecto = "RELLENAR",
                secundariosVisibles = false,
            ),
        )
    }
}
