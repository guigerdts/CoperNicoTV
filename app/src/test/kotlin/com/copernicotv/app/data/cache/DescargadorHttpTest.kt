package com.copernicotv.app.data.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.nio.file.Files

/**
 * Descargador HTTP de la caché de transporte (E2-T7, S51c).
 *
 * Solo se admiten `http` y `https`: un archivo SAF local, una imagen/logo o un
 * stream nunca entran a la caché porque el descargador rechaza el esquema antes
 * de abrir cualquier conexión.
 */
class DescargadorHttpTest {
    @Test
    fun `un esquema saf local se rechaza sin abrir conexion`() =
        runBlocking {
            val destino = destinoTemporal()

            val resultado = DescargadorHttp().descargar("content://proveedor/lista.m3u", destino)

            assertEquals(ResultadoDescarga.Fallo("esquema_no_admitido"), resultado)
            assertFalse(destino.exists())
        }

    @Test
    fun `un esquema file se rechaza sin abrir conexion`() =
        runBlocking {
            val destino = destinoTemporal()

            val resultado = DescargadorHttp().descargar("file:///sdcard/lista.m3u", destino)

            assertEquals(ResultadoDescarga.Fallo("esquema_no_admitido"), resultado)
            assertFalse(destino.exists())
        }

    @Test
    fun `un esquema desconocido se rechaza sin abrir conexion`() =
        runBlocking {
            val destino = destinoTemporal()

            val resultado = DescargadorHttp().descargar("ftp://example.com/lista.m3u", destino)

            assertEquals(ResultadoDescarga.Fallo("esquema_no_admitido"), resultado)
            assertFalse(destino.exists())
        }

    private fun destinoTemporal(): File {
        val directorio = Files.createTempDirectory("descargador-test").toFile()
        directorio.deleteOnExit()
        return File(directorio, "descarga.bin")
    }
}
