package com.copernicotv.app

import androidx.compose.ui.input.key.Key
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.assertIsFocused
import androidx.compose.ui.test.click
import androidx.compose.ui.test.junit4.AndroidComposeTestRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performKeyInput
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.pressKey
import androidx.compose.ui.test.printToString
import androidx.lifecycle.ViewModelProvider
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.ui.PREFIJO_TAG_CANAL
import com.copernicotv.app.ui.ReproductorViewModel
import com.copernicotv.app.ui.TAG_ACCION_REPRODUCIR
import com.copernicotv.app.ui.TAG_REPRODUCTOR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ALTERNAR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_CONTROLES
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ESTADO
import com.copernicotv.app.ui.TAG_REPRODUCTOR_FASE
import kotlinx.coroutines.runBlocking

/** Regla de la actividad única que usan todas las pruebas de reproducción. */
internal typealias ReglaPrincipal = AndroidComposeTestRule<ActivityScenarioRule<MainActivity>, MainActivity>

/** Margen de espera de las pruebas instrumentadas, en milisegundos. */
internal const val ESPERA_TEST_MS: Long = 45_000L

/** Siembra el Canal del catálogo que apunta a [url]. */
internal fun sembrarCanal(
    catalogo: CatalogoRepositorio,
    url: String,
) {
    val texto = "#EXTM3U\n#EXTINF:-1 tvg-id=\"e6\",Canal E6\n$url\n"
    runBlocking {
        catalogo.importar(texto, "Fuente Reproductor", "http://example.invalid/lista.m3u")
    }
}

/** Abre el primer Canal sembrado y espera a la pantalla de reproducción. */
internal fun ReglaPrincipal.abrirReproductor() {
    esperarTag(PREFIJO_TAG_CANAL + 0)
    onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
    esperarTag(TAG_ACCION_REPRODUCIR)
    onNodeWithTag(TAG_ACCION_REPRODUCIR).performClick()
    esperarTag(TAG_REPRODUCTOR)
    esperarTag(TAG_REPRODUCTOR_ESTADO)
}

/** Texto visible de la semántica con [tag], o cadena vacía si no existe. */
internal fun ReglaPrincipal.textoDeTag(tag: String): String =
    onAllNodesWithTag(tag)
        .fetchSemanticsNodes()
        .firstOrNull()
        ?.config
        ?.getOrNull(SemanticsProperties.Text)
        ?.joinToString("") { it.text }
        .orEmpty()

/** Espera a que exista un nodo con [tag]. */
internal fun ReglaPrincipal.esperarTag(
    tag: String,
    tiempoMs: Long = ESPERA_TEST_MS,
) {
    esperarHasta(tiempoMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

/** Espera a que desaparezca el nodo con [tag]. */
internal fun ReglaPrincipal.esperarTagAusente(
    tag: String,
    tiempoMs: Long = ESPERA_TEST_MS,
) {
    esperarHasta(tiempoMs) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
}

/** Espera a que el nodo con [tag] muestre exactamente [texto]. */
internal fun ReglaPrincipal.esperarTexto(
    tag: String,
    texto: String,
    tiempoMs: Long = ESPERA_TEST_MS,
) {
    esperarHasta(tiempoMs) { textoDeTag(tag) == texto }
}

/** Espera a una condición de UI y, si no llega, adjunta el árbol semántico. */
internal fun ReglaPrincipal.esperarHasta(
    tiempoMs: Long = ESPERA_TEST_MS,
    condicion: () -> Boolean,
) {
    val fallo =
        runCatching {
            waitUntil(tiempoMs, condicion)
        }.exceptionOrNull()
    if (fallo != null) {
        val arbol =
            runCatching { onRoot().printToString() }
                .getOrElse { "Sin árbol de semántica: $it" }
        throw AssertionError("La condición de UI no se cumplió a tiempo\n$arbol", fallo)
    }
}

/** Fase legible actual: `Reproduciendo`, `Pausado`, `Finalizado`, etc. */
internal fun ReglaPrincipal.faseActual(): String = textoDeTag(TAG_REPRODUCTOR_FASE)

/** Espera a que la fase legible sea [fase]. */
internal fun ReglaPrincipal.esperarFase(
    fase: String,
    tiempoMs: Long = ESPERA_TEST_MS,
) {
    esperarTexto(TAG_REPRODUCTOR_FASE, fase, tiempoMs)
}

/** Estado del motor y posición publicados por el diagnóstico de E6-T1. */
internal fun ReglaPrincipal.estadoMotor(): Pair<String, Long> {
    val texto = textoDeTag(TAG_REPRODUCTOR_ESTADO)
    val estado = texto.substringBefore('|').substringAfterLast(' ').trim()
    val posicion = texto.substringAfterLast('|').trim().toLongOrNull() ?: 0L
    return estado to posicion
}

/** Posición actual del motor en milisegundos. */
internal fun ReglaPrincipal.posicionMs(): Long = estadoMotor().second

/** Indica si la barra de controles está compuesta en este momento. */
internal fun ReglaPrincipal.hayControles(): Boolean = onAllNodesWithTag(TAG_REPRODUCTOR_CONTROLES).fetchSemanticsNodes().isNotEmpty()

/** Revela los controles con un toque cuando el auto-ocultado ya actuó. */
internal fun ReglaPrincipal.mostrarControlesConToque() {
    if (hayControles()) return
    onNodeWithTag(TAG_REPRODUCTOR).performTouchInput { click() }
    esperarTag(TAG_REPRODUCTOR_CONTROLES)
}

/** Revela los controles con el D-pad cuando el auto-ocultado ya actuó. */
@OptIn(ExperimentalTestApi::class)
internal fun ReglaPrincipal.mostrarControlesConDpad() {
    if (hayControles()) return
    onNodeWithTag(TAG_REPRODUCTOR).performKeyInput { pressKey(Key.DirectionDown) }
    esperarTag(TAG_REPRODUCTOR_CONTROLES)
}

/** Espera a que el foco del D-pad quede en el nodo con [tag]. */
internal fun ReglaPrincipal.esperarFocoEn(
    tag: String,
    tiempoMs: Long = ESPERA_TEST_MS,
) {
    esperarHasta(tiempoMs) {
        runCatching { onNodeWithTag(tag).assertIsFocused() }.isSuccess
    }
}

/** Espera a que el foco del D-pad quede en el botón play/pausa. */
internal fun ReglaPrincipal.esperarFocoEnAlternar() = esperarFocoEn(TAG_REPRODUCTOR_ALTERNAR)

/** ViewModel de la pantalla; permite inspeccionar la sesión viva en pruebas. */
internal fun ReglaPrincipal.viewModelReproductor(): ReproductorViewModel = ViewModelProvider(activity)[ReproductorViewModel::class.java]
