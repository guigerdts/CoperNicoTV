package com.copernicotv.app

import android.content.Context
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.Properties

/**
 * Puerto fijo reservado a S47: la fase 1 no reproduce medios, pero el Canal
 * sembrado apunta a este puerto para dejar claro que el borrado no depende de
 * la red ni del servidor (S47d).
 */
internal const val PUERTO_S47 = 43118

/**
 * E7-T6 (S47a/S47b/S47c) para el catálogo: borra el Historial desde la UI,
 * termina el proceso real y comprueba, en un proceso nuevo, que el borrado
 * persistió y que los Favoritos se conservaron; después borra también los
 * Favoritos desde la UI.
 *
 * Reutiliza el arnés de dos fases de E3-T8/S11d/S45/E7-T5: instalación única
 * de APK, `am instrument -e clearPackageData false`, `am force-stop` y otra
 * invocación de instrumentación. La marca de la fase 1 se guarda en
 * `filesDir`.
 */
internal const val ARCHIVO_MARCA_S47 = "s47-proceso.properties"

internal data class MarcaS47(
    val identidad: String,
    val favoritos: Int,
    val historial: Int,
)

private fun archivoMarca(contexto: Context): File = File(contexto.filesDir, ARCHIVO_MARCA_S47)

internal fun borrarMarcaS47(contexto: Context) {
    archivoMarca(contexto).delete()
}

internal fun escribirMarcaS47(
    contexto: Context,
    marca: MarcaS47,
) {
    val propiedades = Properties()
    propiedades["identidad"] = marca.identidad
    propiedades["favoritos"] = marca.favoritos.toString()
    propiedades["historial"] = marca.historial.toString()
    archivoMarca(contexto).outputStream().use { propiedades.store(it, null) }
}

internal fun leerMarcaS47(contexto: Context): MarcaS47 {
    val archivo = archivoMarca(contexto)
    check(archivo.isFile) {
        "Falta la marca de la fase 1; ejecute S47ProcesoFase1Test antes de la fase 2"
    }
    val propiedades = Properties()
    archivo.inputStream().use { propiedades.load(it) }
    return MarcaS47(
        identidad = propiedades.getProperty("identidad"),
        favoritos = propiedades.getProperty("favoritos").toInt(),
        historial = propiedades.getProperty("historial").toInt(),
    )
}

/** Limpia la base de la fase o prueba anterior antes de sembrar. */
internal fun limpiarBaseS47(db: AppDatabase) {
    runBlocking { db.clearAllTables() }
}

/** Siembra una Fuente con un único Canal por importación M3U (sin red real). */
internal fun sembrarCanalS47(catalogo: CatalogoRepositorio) {
    val texto = "#EXTM3U\n#EXTINF:-1 tvg-id=\"s47\",Canal S47\nhttps://example.invalid/s47.m3u8\n"
    runBlocking {
        catalogo.importar(texto, "Fuente S47", "http://example.invalid/s47.m3u")
    }
}
