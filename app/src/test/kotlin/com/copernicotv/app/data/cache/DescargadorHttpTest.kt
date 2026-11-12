package com.copernicotv.app.data.cache

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.net.InetAddress
import java.net.InetSocketAddress
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

    /**
     * D5 (E7-T6): un fallo HTTP con credenciales en la URL no las filtra. El
     * código del fallo es tipado y nunca incluye la URL ni sus credenciales;
     * es el mismo valor que termina en la causa visible de Fuentes.
     */
    @Test
    fun `un fallo http con credenciales en la url no las filtra`() {
        val servidor = com.sun.net.httpserver.HttpServer.create(InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0)
        servidor.createContext("/lista.m3u") { intercambio ->
            intercambio.sendResponseHeaders(401, -1)
            intercambio.close()
        }
        servidor.start()
        try {
            val puerto = servidor.address.port
            val url = "http://usuario:secreto@127.0.0.1:$puerto/lista.m3u"
            val destino = destinoTemporal()

            val resultado = runBlocking { DescargadorHttp().descargar(url, destino) }

            assertEquals(ResultadoDescarga.Fallo("http_4xx"), resultado)
            assertFalse(
                "El código del fallo filtró credenciales: $resultado",
                resultado.toString().contains("usuario") || resultado.toString().contains("secreto"),
            )
            assertFalse(destino.exists())
        } finally {
            servidor.stop(0)
        }
    }

    private fun destinoTemporal(): File {
        val directorio = Files.createTempDirectory("descargador-test").toFile()
        directorio.deleteOnExit()
        return File(directorio, "descarga.bin")
    }
}
