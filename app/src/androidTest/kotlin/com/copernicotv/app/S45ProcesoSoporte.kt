package com.copernicotv.app

import android.content.Context
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.copernicotv.app.ui.PREFIJO_TAG_COLECCION
import com.copernicotv.app.ui.PREFIJO_TAG_COLECCION_CANAL
import com.copernicotv.app.ui.TAG_COLECCIONES
import com.copernicotv.app.ui.TAG_COLECCION_ACTIVA
import com.copernicotv.app.ui.TAG_COLECCION_CANAL_BAJAR
import java.io.File
import java.util.Properties

/**
 * S45a/S45b para Colecciones: reordena desde la UI, termina el proceso real y
 * comprueba, en un proceso nuevo, que la secuencia visible conserva el orden
 * elegido y coincide exactamente con `ColeccionCanalDao.porColeccion`.
 *
 * Reutiliza el arnés de dos fases de E3-T8/S11d: instalación única de APK,
 * `am instrument -e clearPackageData false`, `am force-stop` y otra invocación
 * de instrumentación. La marca de la fase 1 se guarda en `filesDir`.
 */
internal const val ARCHIVO_MARCA_S45 = "s45-proceso.properties"

internal data class MarcaS45(
    val identidad: String,
    val coleccionId: Long,
    val filas: List<Long>,
    val nombres: List<String>,
)

/** Abre el diálogo de Colecciones y selecciona la Colección indicada. */
internal fun ReglaPrincipal.abrirColeccionS45(coleccionId: Long) {
    esperarTag(TAG_COLECCIONES)
    onNodeWithTag(TAG_COLECCIONES).performScrollTo().performClick()
    esperarTag(TAG_COLECCION_ACTIVA)
    onNodeWithTag(TAG_COLECCION_ACTIVA).performScrollTo().performClick()
    esperarTag(PREFIJO_TAG_COLECCION + coleccionId)
    onNodeWithTag(PREFIJO_TAG_COLECCION + coleccionId).performClick()
}

/** Pulsa la acción "bajar" de la fila indicada dentro del diálogo abierto. */
internal fun ReglaPrincipal.bajarFilaColeccionS45(filaId: Long) {
    onNodeWithTag(TAG_COLECCION_CANAL_BAJAR + filaId)
        .performScrollTo()
        .performClick()
}

/**
 * Secuencia visible de pertenencias, en el orden en que Compose las recorre.
 * Solo considera las etiquetas `coleccion-canal-<filaId>`.
 */
internal fun ReglaPrincipal.filasVisiblesColeccionS45(): List<Long> =
    onAllNodes(
        SemanticsMatcher("testTag empieza con $PREFIJO_TAG_COLECCION_CANAL") { nodo ->
            nodo.config
                .getOrNull(SemanticsProperties.TestTag)
                ?.startsWith(PREFIJO_TAG_COLECCION_CANAL) == true
        },
    ).fetchSemanticsNodes()
        .mapNotNull { nodo ->
            nodo.config
                .getOrNull(SemanticsProperties.TestTag)
                ?.removePrefix(PREFIJO_TAG_COLECCION_CANAL)
                ?.toLongOrNull()
        }

/** Nombres visibles en el mismo orden que [filasVisiblesColeccionS45]. */
internal fun ReglaPrincipal.nombresVisiblesColeccionS45(): List<String> =
    filasVisiblesColeccionS45().map { filaId -> textoDeTag(PREFIJO_TAG_COLECCION_CANAL + filaId) }

/** Espera a que el diálogo muestre exactamente [cantidad] pertenencias. */
internal fun ReglaPrincipal.esperarFilasColeccionS45(cantidad: Int) {
    esperarHasta(TIEMPO_ESPERA_S45) { filasVisiblesColeccionS45().size == cantidad }
}

/** Espera a que la secuencia visible sea exactamente [esperadas]. */
internal fun ReglaPrincipal.esperarOrdenColeccionS45(esperadas: List<Long>) {
    esperarHasta(TIEMPO_ESPERA_S45) { filasVisiblesColeccionS45() == esperadas }
}

private fun archivoMarca(contexto: Context): File = File(contexto.filesDir, ARCHIVO_MARCA_S45)

internal fun borrarMarcaS45(contexto: Context) {
    archivoMarca(contexto).delete()
}

internal fun escribirMarcaS45(
    contexto: Context,
    marca: MarcaS45,
) {
    val propiedades = Properties()
    propiedades["identidad"] = marca.identidad
    propiedades["coleccion"] = marca.coleccionId.toString()
    propiedades["filas"] = marca.filas.joinToString(",")
    propiedades["nombres"] = marca.nombres.joinToString(",")
    archivoMarca(contexto).outputStream().use { propiedades.store(it, null) }
}

internal fun leerMarcaS45(contexto: Context): MarcaS45 {
    val archivo = archivoMarca(contexto)
    check(archivo.isFile) {
        "Falta la marca de la fase 1; ejecute S45ProcesoFase1Test antes de la fase 2"
    }
    val propiedades = Properties()
    archivo.inputStream().use { propiedades.load(it) }

    fun requerida(clave: String): String = requireNotNull(propiedades.getProperty(clave)) { "La marca no contiene '$clave'" }
    return MarcaS45(
        identidad = requerida("identidad"),
        coleccionId = requerida("coleccion").toLong(),
        filas = requerida("filas").split(',').map { it.toLong() },
        nombres = requerida("nombres").split(','),
    )
}

private const val TIEMPO_ESPERA_S45 = 20_000L
