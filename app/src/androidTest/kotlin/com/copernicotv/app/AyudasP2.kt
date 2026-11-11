package com.copernicotv.app

import android.content.ContentValues
import android.graphics.Bitmap
import android.provider.MediaStore
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.printToString
import androidx.test.platform.app.InstrumentationRegistry
import java.io.File
import java.io.FileInputStream
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
 * DC-11. Escribe el archivo en el directorio externo de la app y lo publica
 * además en `Pictures/capturas-p2` vía MediaStore: en imágenes production el
 * shell user no puede leer `Android/data` (sin `adb root` ni `run-as`), así
 * que CI extrae las capturas con `adb pull` desde el almacenamiento
 * compartido. El procedimiento queda documentado en la checklist física de
 * E7-T7.
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
    publicarP2EnGaleria(nombre, destino)
    println("CAPTURA_P2: ${destino.absolutePath}")
    return destino
}

/** Copia una captura ya escrita al almacenamiento compartido vía MediaStore
 * con ruta relativa `Pictures/capturas-p2`, visible para el shell user de CI
 * sin permisos adicionales en API 34. */
internal fun ReglaPrincipal.publicarP2EnGaleria(nombre: String, origen: File) {
    val contexto = InstrumentationRegistry.getInstrumentation().targetContext
    val valores = ContentValues().apply {
        put(MediaStore.Images.Media.DISPLAY_NAME, "$nombre.png")
        put(MediaStore.Images.Media.MIME_TYPE, "image/png")
        put(MediaStore.Images.Media.RELATIVE_PATH, "Pictures/capturas-p2")
    }
    val uri = contexto.contentResolver.insert(
        MediaStore.Images.Media.EXTERNAL_CONTENT_URI,
        valores,
    ) ?: throw AssertionError("MediaStore rechazó la captura $nombre")
    contexto.contentResolver.openOutputStream(uri)?.use { flujo ->
        FileInputStream(origen).use { entrada -> entrada.copyTo(flujo) }
    } ?: throw AssertionError("Sin salida para la captura $nombre")
}
