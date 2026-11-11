package com.copernicotv.app

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileOutputStream

/**
 * Número de nodos con foco activo en el árbol fusionado: es el foco que el
 * espectador ve en pantalla. La propiedad `Focused` aparece en el árbol
 * fusionado, como muestra el árbol adjunto a los fallos de CI; el matcher
 * acepta cualquier nodo para recorrer toda la jerarquía. Las ayudas de este
 * archivo sostienen los criterios de foco de E7 (un solo foco visible por
 * pantalla) y la captura reproducible de la revisión visual P2 (DC-11).
 */
internal fun ReglaPrincipal.contarConFoco(): Int =
    onAllNodes(
        SemanticsMatcher("nodoCualquiera") { true },
        useUnmergedTree = false,
    ).fetchSemanticsNodes().count { nodo ->
        nodo.config.getOrNull(SemanticsProperties.Focused) == true
    }

/**
 * Asegura que en pantalla hay exactamente un foco visible: no debe haber ni
 * cero ni varios nodos enfocados; los D-pad de TV exigen pleno determinismo.
 */
internal fun ReglaPrincipal.focoUnico() {
    val focos = contarConFoco()
    if (focos != 1) {
        val arbol =
            runCatching { onRoot().printToString() }
                .getOrElse { "Sin árbol de semántica: $it" }
        throw AssertionError("Se esperaba exactamente un foco visible y hay $focos\nÁrbol:\n$arbol")
    }
}

/** Ruta determinista de una captura P2 dentro del almacenamiento externo. */
internal fun ReglaPrincipal.destinoCaptura(nombre: String): File =
    File(
        InstrumentationRegistry.getInstrumentation().targetContext
            .getExternalFilesDir("capturas-p2"),
        "$nombre.png",
    )

/**
 * Captura la pantalla como PNG reproducible para la revisión visual P2 de
 * DC-11 y la guarda en el directorio externo de la app. CI lo recoge con
 * `adb pull`; el procedimiento de recuperación queda documentado en la
 * checklist física de E7-T7.
 */
internal fun ReglaPrincipal.capturarP2(nombre: String): File {
    val destino = destinoCaptura(nombre)
    destino.parentFile?.mkdirs()
    val imagen = onRoot().captureToImage().asAndroidBitmap()
    FileOutputStream(destino).use { flujo ->
        imagen.compress(Bitmap.CompressFormat.PNG, 100, flujo)
    }
    if (!destino.exists() || destino.length() <= 0L) {
        throw AssertionError("La captura $nombre quedó vacía o inexistente: $destino")
    }
    println("CAPTURA_P2: ${destino.absolutePath}")
    return destino
}
