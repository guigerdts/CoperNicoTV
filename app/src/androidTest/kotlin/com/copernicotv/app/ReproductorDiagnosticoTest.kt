package com.copernicotv.app

import android.content.ClipboardManager
import android.content.Context
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.RepositorioPreferenciasPista
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.PreferenciasPista
import com.copernicotv.app.ui.TAG_REPRODUCTOR_COPIAR_DIAGNOSTICO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_DIAGNOSTICO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ERROR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_PANEL_DIAGNOSTICO
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import javax.inject.Inject

/**
 * E6-T5 en un emulador móvil: el diagnóstico se deriva de la sesión real,
 * muestra los cuatro campos de S22a, copia el mismo texto y nunca filtra la
 * URL original ni sus credenciales (S57a).
 *
 * Se usa F19 porque ofrece dos audio y dos subtítulos reales; no existe un
 * fixture F22 en el repositorio.
 */
@HiltAndroidTest
class ReproductorDiagnosticoTest {
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
    fun elDiagnosticoMuestraLosCamposYNoFiltraCredenciales() {
        abrir(FixturesF19.PISTAS, "?token=secreto")

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_DIAGNOSTICO).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_DIAGNOSTICO)
        composeRule.esperarTag(TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO)

        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO)
        assertTrue("Falta el protocolo: $texto", texto.contains("Protocolo: HTTP/HTTPS progresivo"))
        assertTrue(
            "Falta la URL saneada: $texto",
            texto.contains("URL: http://127.0.0.1:${servidor.puerto}"),
        )
        assertTrue("Falta el búfer: $texto", texto.contains("Búfer:"))
        assertTrue("Falta el audio disponible: $texto", texto.contains("Audio disponible:"))
        assertTrue("Falta el audio seleccionado: $texto", texto.contains("Audio seleccionado:"))
        assertTrue("Faltan los subtítulos: $texto", texto.contains("Subtítulos disponibles:"))
        assertTrue("Falta el último error: $texto", texto.contains("Último error: ninguno"))
        assertFalse("Se filtró el token: $texto", texto.contains("token"))
        assertFalse("Se filtró el secreto: $texto", texto.contains("secreto"))
        assertFalse("Se filtró la ruta: $texto", texto.contains("f19_pistas"))
        assertFalse("Se filtró la query: $texto", texto.contains("?"))

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_COPIAR_DIAGNOSTICO).performClick()
        // El texto "Copiado" solo aparece cuando la escritura devolvió éxito:
        // su ausencia apunta al onClick, no a la lectura del portapapeles.
        composeRule.esperarHasta {
            composeRule.onAllNodesWithText("Copiado").fetchSemanticsNodes().isNotEmpty()
        }
        val copiado = esperarPortapapeles()
        assertTrue("El portapapeles no copió el diagnóstico: [$copiado]", copiado.contains("Protocolo: HTTP/HTTPS progresivo"))
        assertTrue(
            "El portapapeles no copió la URL saneada: [$copiado]",
            copiado.contains("URL: http://127.0.0.1:${servidor.puerto}"),
        )
        assertFalse("El portapapeles filtró el token: [$copiado]", copiado.contains("token"))
        assertFalse("El portapapeles filtró el secreto: [$copiado]", copiado.contains("secreto"))
        assertFalse("El portapapeles filtró la ruta: [$copiado]", copiado.contains("f19_pistas"))
    }

    @Test
    fun elError404ConservaElDiagnosticoEnLaPantallaDeError() {
        sembrarCanal(catalogo, FixturesF19.url(servidor, "f19/ausente.mp4"))
        composeRule.abrirPantallaReproductor()
        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        assertTrue(composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR).contains("http_cliente"))

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_DIAGNOSTICO).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_DIAGNOSTICO)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO)

        assertTrue("Falta el protocolo tras el error: $texto", texto.contains("Protocolo: HTTP/HTTPS progresivo"))
        assertTrue("Falta el búfer tras el error: $texto", texto.contains("Búfer:"))
        assertTrue("Faltan las pistas tras el error: $texto", texto.contains("Audio disponible: sin pistas"))
        assertTrue("Falta el error tipado: $texto", texto.contains("Último error: http_cliente"))
    }

    @Test
    fun elFlujoSinPistasNoInventaPistas() {
        abrir(FixturesF19.SIN_PISTAS)

        composeRule.onNodeWithTag(TAG_REPRODUCTOR_DIAGNOSTICO).performClick()
        composeRule.esperarTag(TAG_REPRODUCTOR_PANEL_DIAGNOSTICO)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_DIAGNOSTICO_TEXTO)

        assertTrue("Inventó audio: $texto", texto.contains("Audio disponible: sin pistas"))
        assertTrue("Inventó subtítulos: $texto", texto.contains("Subtítulos disponibles: sin pistas"))
        assertTrue("Inventó un error: $texto", texto.contains("Último error: ninguno"))
    }

    private fun abrir(
        ruta: String,
        sufijo: String = "",
    ) {
        sembrarCanal(catalogo, FixturesF19.url(servidor, ruta) + sufijo)
        composeRule.abrirReproductor()
        composeRule.esperarFase("Reproduciendo")
        composeRule.mostrarControlesConToque()
    }

    /**
     * La escritura del portapapeles y su lectura compiten con el frame bajo
     * prueba en el emulador: se sondea un tiempo acotado antes de declarar el
     * fallo, y el contenido observado queda en el mensaje de la aserción.
     */
    private fun esperarPortapapeles(): String {
        var texto = ""
        repeat(20) {
            texto = leerPortapapeles()
            if (texto.contains("Protocolo:")) return texto
            Thread.sleep(200)
        }
        val observacion =
            textoDePortapapeles(
                InstrumentationRegistry.getInstrumentation().targetContext,
                "via targetContext",
            ) + " / " + textoDePortapapeles(composeRule.activity, "via activity")
        return "$texto $observacion"
    }

    /** Lee el portapapeles intentando dos contextos del mismo paquete. */
    private fun leerPortapapeles(): String {
        val viaTarget =
            textoDePortapapeles(
                InstrumentationRegistry.getInstrumentation().targetContext,
                "via targetContext",
            )
        if (viaTarget.isNotBlank()) return viaTarget
        return textoDePortapapeles(composeRule.activity, "via activity")
    }

    /** Lee el clip primario sin fallar si el sistema lo restringe. */
    private fun textoDePortapapeles(
        contexto: Context,
        via: String,
    ): String {
        val gestor = contexto.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
        val clip = gestor?.primaryClip
        if (clip != null && clip.itemCount > 0) {
            val texto = clip.getItemAt(0).coerceToText(contexto)?.toString().orEmpty()
            if (texto.isNotBlank()) return texto
        }
        return "[$via sin texto accesible]"
    }
}
