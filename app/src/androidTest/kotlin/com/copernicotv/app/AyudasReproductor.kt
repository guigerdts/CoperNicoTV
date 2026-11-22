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
    abrirPantallaReproductor()
    esperarTag(TAG_REPRODUCTOR_ESTADO)
}

/**
 * Abre el primer Canal sembrado y espera solo la raíz de la pantalla.
 *
 * Sirve cuando el contenido falla de inmediato: el error definitivo de S20
 * sustituye al reproductor y el diagnóstico de E6-T2 nunca llega a existir, así
 * que esperar [TAG_REPRODUCTOR_ESTADO] sería una carrera contra el fallo.
 */
internal fun ReglaPrincipal.abrirPantallaReproductor() {
    esperarTag(PREFIJO_TAG_CANAL + 0)
    onNodeWithTag(PREFIJO_TAG_CANAL + 0).performClick()
    esperarTag(TAG_ACCION_REPRODUCIR)
    onNodeWithTag(TAG_ACCION_REPRODUCIR).performClick()
    esperarTag(TAG_REPRODUCTOR)
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
    descripcion: String = "aparece el nodo '$tag'",
) {
    esperarHasta(tiempoMs, descripcion) { onAllNodesWithTag(tag).fetchSemanticsNodes().isNotEmpty() }
}

/** Espera a que desaparezca el nodo con [tag]. */
internal fun ReglaPrincipal.esperarTagAusente(
    tag: String,
    tiempoMs: Long = ESPERA_TEST_MS,
    descripcion: String = "desaparece el nodo '$tag'",
) {
    esperarHasta(tiempoMs, descripcion) { onAllNodesWithTag(tag).fetchSemanticsNodes().isEmpty() }
}

/** Espera a que el nodo con [tag] muestre exactamente [texto]. */
internal fun ReglaPrincipal.esperarTexto(
    tag: String,
    texto: String,
    tiempoMs: Long = ESPERA_TEST_MS,
    descripcion: String = "el nodo '$tag' muestra '$texto'",
) {
    esperarHasta(tiempoMs, descripcion) { textoDeTag(tag) == texto }
}

/**
 * Espera a una condición de UI y, si no llega, informa el paso, el margen y el
 * último estado observable.
 *
 * [descripcion] identifica el paso que agota el margen. Sin ella, un fallo
 * intermitente sólo dice «no se cumplió a tiempo» y obliga a adivinar cuál de
 * las esperas de la prueba fue; con ella, el informe lo nombra. El árbol
 * semántico se acota para no inundar el log de CI.
 */
internal fun ReglaPrincipal.esperarHasta(
    tiempoMs: Long = ESPERA_TEST_MS,
    descripcion: String = "condición de UI sin descripción",
    condicion: () -> Boolean,
) {
    val fallo =
        runCatching {
            waitUntil(tiempoMs, condicion)
        }.exceptionOrNull()
    if (fallo != null) {
        throw AssertionError(informeEspera(descripcion, tiempoMs), fallo)
    }
}

/** Líneas del árbol semántico que se adjuntan al informe de un timeout. */
private const val LINEAS_ARBOL_MAX = 80

/** Etiquetas de prueba distintas que se listan en el informe. */
private const val ETIQUETAS_MAX = 12

/** Textos visibles distintos que se listan en el informe. */
private const val TEXTOS_MAX = 8

/** Recorte de cada texto listado, para que una cadena larga no domine el log. */
private const val TEXTO_RECORTE = 60

private val NODO_SEMANTICO = Regex("""Node #\d+""")
private val ETIQUETA_SEMANTICA = Regex("""TestTag = '([^']*)'""")
private val TEXTO_SEMANTICO = Regex("""Text = '([^']*)'""")

/**
 * Mensaje de un timeout: paso, margen y último estado observable acotado.
 *
 * Se arma justo después de que `waitUntil` se rinde, así que el árbol que
 * adjunta es el último que la prueba pudo observar.
 */
private fun ReglaPrincipal.informeEspera(
    descripcion: String,
    tiempoMs: Long,
): String {
    val arbol =
        runCatching { onRoot().printToString() }
            .getOrElse { "Sin árbol de semántica: $it" }
    val nodos = NODO_SEMANTICO.findAll(arbol).count()
    val etiquetas =
        ETIQUETA_SEMANTICA.findAll(arbol)
            .map { it.groupValues[1] }
            .filter { it.isNotBlank() }
            .distinct()
            .take(ETIQUETAS_MAX)
            .toList()
    val textos =
        TEXTO_SEMANTICO.findAll(arbol)
            .map { it.groupValues[1].trim().trimStart('[').trimEnd(']').take(TEXTO_RECORTE) }
            .filter { it.isNotBlank() }
            .distinct()
            .take(TEXTOS_MAX)
            .toList()
    val lineas = arbol.lines()
    val visibles = lineas.take(LINEAS_ARBOL_MAX)
    val restantes = lineas.size - visibles.size
    return buildString {
        appendLine("La condición de UI no se cumplió a tiempo")
        appendLine("Paso: $descripcion")
        appendLine("Timeout: $tiempoMs ms")
        appendLine("Nodos en el árbol: $nodos")
        appendLine("Etiquetas presentes: ${etiquetas.ifEmpty { listOf("(ninguna)") }.joinToString(", ")}")
        appendLine("Textos presentes: ${textos.ifEmpty { listOf("(ninguno)") }.joinToString(", ") { "\"$it\"" }}")
        append("Árbol semántico (${visibles.size} de ${lineas.size} líneas):\n")
        append(visibles.joinToString("\n"))
        if (restantes > 0) appendLine("\n… $restantes líneas más")
    }
}

/** Fase legible actual: `Reproduciendo`, `Pausado`, `Finalizado`, etc. */
internal fun ReglaPrincipal.faseActual(): String = textoDeTag(TAG_REPRODUCTOR_FASE)

/** Espera a que la fase legible sea [fase]. */
internal fun ReglaPrincipal.esperarFase(
    fase: String,
    tiempoMs: Long = ESPERA_TEST_MS,
    descripcion: String = "la fase del reproductor es '$fase'",
) {
    esperarTexto(TAG_REPRODUCTOR_FASE, fase, tiempoMs, descripcion)
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

/**
 * Espera a que el motor informe un estado reproducible y una posición mayor
 * que cero. Acepta `READY` o `ENDED` porque el fixture puede terminar antes
 * de que el sondeo lea la etiqueta.
 */
internal fun ReglaPrincipal.esperarReproduccionAvanzando() {
    esperarHasta(descripcion = "el motor informa un estado reproducible con posición mayor que cero") {
        val (estado, posicion) = estadoMotor()
        (estado == "READY" || estado == "ENDED") && posicion > 0L
    }
}

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

/**
 * Espera a que [tag] esté disponible en el árbol merged y la UI quede ociosa.
 *
 * Justo después de un zapeo los controles se recomponen mientras la sesión se
 * sustituye. Si la prueba consulta la semántica en mitad de esa reconstrucción,
 * `performClick` puede fallar con «Failed to inject touch input»: el nodo existe
 * en el árbol unmerged pero todavía no en el merged. No es una fusión
 * estructural —los ancestros no tienen `mergeDescendants`— sino una lectura sin
 * sincronizar. Esperar la condición observable y sincronizar el reloj elimina
 * la dependencia del frame sin esperas arbitrarias.
 */
internal fun ReglaPrincipal.esperarAccionDisponible(
    tag: String,
    tiempoMs: Long = ESPERA_TEST_MS,
    descripcion: String = "la acción '$tag' está disponible en el árbol fusionado",
) {
    esperarHasta(tiempoMs, descripcion) {
        onAllNodesWithTag(tag).fetchSemanticsNodes().size == 1
    }
    waitForIdle()
}

/** Espera a que el foco del D-pad quede en el nodo con [tag]. */
internal fun ReglaPrincipal.esperarFocoEn(
    tag: String,
    tiempoMs: Long = ESPERA_TEST_MS,
    descripcion: String = "el foco del D-pad queda en '$tag'",
) {
    esperarHasta(tiempoMs, descripcion) {
        runCatching { onNodeWithTag(tag).assertIsFocused() }.isSuccess
    }
}

/** Espera a que el foco del D-pad quede en el botón play/pausa. */
internal fun ReglaPrincipal.esperarFocoEnAlternar() = esperarFocoEn(TAG_REPRODUCTOR_ALTERNAR)

/** ViewModel de la pantalla; permite inspeccionar la sesión viva en pruebas. */
internal fun ReglaPrincipal.viewModelReproductor(): ReproductorViewModel = ViewModelProvider(activity)[ReproductorViewModel::class.java]

/**
 * Volumen vigente del motor Media3, leído en el hilo principal como exige
 * Media3 (`Player is accessed on the wrong thread`); la condición de un
 * `esperarHasta` corre en el hilo de instrumentación y no puede leerlo directo.
 */
internal fun ReglaPrincipal.volumenMotorEn(viewModel: ReproductorViewModel): Float? {
    var valor: Float? = null
    runOnUiThread { valor = viewModel.motor?.volume }
    return valor
}
