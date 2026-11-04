package com.copernicotv.app.debug

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import java.io.File

/**
 * Sirve el fixture canario desde `content://com.copernicotv.app.fixture/...`.
 *
 * `CatalogoRepositorio.importarFixture` lee el asset directamente y guarda esa
 * ubicación como origen de la Lista. Sin un proveedor que responda en esa URI,
 * una Actualización manual de la Lista de fixture termina en
 * `archivo_inaccesible` y el camino completo nunca se ejecuta en Android.
 *
 * El proveedor existe solo en la variante `debug`; no forma parte de release.
 * `exported="false"` basta porque la app se lee a sí misma.
 */
class FixtureProvider : ContentProvider() {
    override fun onCreate(): Boolean = true

    override fun openFile(
        uri: Uri,
        mode: String,
    ): ParcelFileDescriptor? {
        val nombre = uri.lastPathSegment ?: return null
        if (nombre != FIXTURE) return null
        val contexto = context ?: return null
        val archivo = File(contexto.cacheDir, nombre)
        // Se reescribe en cada apertura: el asset puede cambiar entre builds y
        // la caché sobrevive a una reinstalación con `adb install -r`.
        contexto.assets.open(nombre).use { entrada ->
            archivo.outputStream().use { salida -> entrada.copyTo(salida) }
        }
        return ParcelFileDescriptor.open(archivo, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun getType(uri: Uri): String? = TIPO_M3U

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?,
    ): Cursor? = null

    override fun insert(
        uri: Uri,
        values: ContentValues?,
    ): Uri? = null

    override fun delete(
        uri: Uri,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?,
    ): Int = 0

    private companion object {
        const val FIXTURE = "fixture-canario.m3u"
        const val TIPO_M3U = "audio/x-mpegurl"
    }
}
