package com.copernicotv.app

import android.content.Context
import android.view.View
import android.view.ViewGroup
import androidx.media3.ui.AspectRatioFrameLayout
import androidx.media3.ui.PlayerView
import com.copernicotv.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties

/**
 * E7-T5 (S44b, S17b) para el reproductor: cambia las preferencias desde la
 * UI, termina el proceso real y comprueba, en un proceso nuevo, que el modo
 * de aspecto restaurado se aplica a la `PlayerView` y que la visibilidad de
 * los controles secundarios se conservó.
 *
 * Reutiliza el arnés de dos fases de E3-T8/S11d/S45: instalación única de
 * APK, `am instrument -e clearPackageData false`, `am force-stop` y otra
 * invocación de instrumentación. La marca de la fase 1 se guarda en
 * `filesDir`, y la siembra re-importa el mismo `tvg-id="e6"` en la fase 2
 * para que la reconciliación de ADR-0004 apunte el Canal al puerto efímero
 * del servidor de esa fase.
 */
internal const val ARCHIVO_MARCA_E7T5 = "e7t5-proceso.properties"

internal data class MarcaE7T5(
    val identidad: String,
    val modoAspecto: String,
    val secundariosVisibles: Boolean,
)

private fun archivoMarca(contexto: Context): File = File(contexto.filesDir, ARCHIVO_MARCA_E7T5)

internal fun borrarMarcaE7T5(contexto: Context) {
    archivoMarca(contexto).delete()
}

internal fun escribirMarcaE7T5(
    contexto: Context,
    marca: MarcaE7T5,
) {
    val propiedades = Properties()
    propiedades["identidad"] = marca.identidad
    propiedades["modo_aspecto"] = marca.modoAspecto
    propiedades["secundarios"] = marca.secundariosVisibles.toString()
    archivoMarca(contexto).outputStream().use { propiedades.store(it, null) }
}

internal fun leerMarcaE7T5(contexto: Context): MarcaE7T5 {
    val archivo = archivoMarca(contexto)
    check(archivo.isFile) {
        "Falta la marca de la fase 1; ejecute E7T5ProcesoFase1Test antes de la fase 2"
    }
    val propiedades = Properties()
    archivo.inputStream().use { propiedades.load(it) }
    return MarcaE7T5(
        identidad = propiedades.getProperty("identidad"),
        modoAspecto = propiedades.getProperty("modo_aspecto"),
        secundariosVisibles = propiedades.getProperty("secundarios").toBoolean(),
    )
}

/** Limpia la base de la fase anterior antes de la siembra de la fase 1. */
internal fun limpiarBaseE7T5(db: AppDatabase) {
    runBlocking { db.clearAllTables() }
}

/** El `resizeMode` vigente de la única `PlayerView` de la jerarquía (S44b). */
internal fun ReglaPrincipal.resizeModeE7T5(): Int {
    var leido = -1
    runOnUiThread {
        leido = buscarPlayerViewE7T5(activity.window.decorView)?.resizeMode ?: -1
    }
    return leido
}

/** Espera a que la `PlayerView` adopte el modo completo (Rellenar). */
internal fun ReglaPrincipal.esperarResizeModeFillE7T5() {
    esperarHasta(ESPERA_TEST_MS) {
        resizeModeE7T5() == AspectRatioFrameLayout.RESIZE_MODE_FILL
    }
}

private fun buscarPlayerViewE7T5(raiz: View): PlayerView? {
    if (raiz is PlayerView) return raiz
    if (raiz is ViewGroup) {
        for (indice in 0 until raiz.childCount) {
            buscarPlayerViewE7T5(raiz.getChildAt(indice))?.let { return it }
        }
    }
    return null
}
