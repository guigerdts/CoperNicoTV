package com.copernicotv.app.crypto

import android.content.Context
import android.util.Base64
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import javax.inject.Singleton

/**
 * Persistencia del sobre de la clave de datos (E7-T7 ronda 8, S2/S3).
 *
 * Guarda EXCLUSIVAMENTE la clave de datos envuelta con la raíz de Keystore
 * (sobre `0x01`): el material de clave en claro nunca se persiste. La
 * interfaz es síncrona a propósito: la clave de sesión se resuelve una vez
 * por proceso, al primer uso, sin suspender las rutas de cifrado.
 */
interface AlmacenClaveDatos {
    /** Sobre envuelto persistido, o `null` si no existe aún. */
    fun leer(): ByteArray?

    /** Persiste el sobre envuelto (atomicidad: archivo temporal + rename). */
    fun guardar(sobre: ByteArray)
}

/**
 * Archivo en `noBackupFilesDir` del proceso: privado de la app, sin copia de
 * nube ni backup automático. El sobre es ininteligible sin la raíz.
 */
class AlmacenClaveDatosArchivo(
    archivo: File,
) : AlmacenClaveDatos {
    private val destino = File(archivo.parentFile, ARCHIVO)

    override fun leer(): ByteArray? {
        if (!destino.exists()) return null
        return try {
            Base64.decode(destino.readText(), Base64.NO_WRAP)
        } catch (_: IllegalArgumentException) {
            null
        }
    }

    override fun guardar(sobre: ByteArray) {
        val temporal = File(destino.parentFile, ARCHIVO + ".tmp")
        temporal.writeText(Base64.encodeToString(sobre, Base64.NO_WRAP))
        if (!temporal.renameTo(destino)) {
            destino.writeText(temporal.readText())
            temporal.delete()
        }
    }

    private companion object {
        const val ARCHIVO = "clave_datos_sesion.sobre"
    }
}

@Module
@InstallIn(SingletonComponent::class)
object ModuloAlmacenClaveDatos {
    @Provides
    @Singleton
    fun almacen(
        @ApplicationContext contexto: Context,
    ): AlmacenClaveDatos = AlmacenClaveDatosArchivo(File(contexto.noBackupFilesDir, "clave_datos"))
}
