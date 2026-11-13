package com.copernicotv.app.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Descargador HTTP de la caché de transporte (E2-T7, S51c).
 *
 * Solo se admiten `http` y `https`: un archivo SAF local, una imagen/logo o un
 * stream nunca entran a la caché porque el descargador rechaza el esquema antes
 * de abrir cualquier conexión.
 *
 * E7-T7 añade los caminos de diagnóstico del transporte: éxito HTTP local,
 * 5xx, timeout, conexión rechazada, DNS, bloqueo de la política de texto
 * claro y TLS (un certificado de confianza importa; uno autofirmado se
 * rechaza sin aceptarlo). El bloqueo de la política se verifica con un
 * predicado inyectado porque los unit tests corren en JVM pura, sin
 * `NetworkSecurityPolicy`.
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
     *
     * El servidor 401 es un `ServerSocket` puro: `com.sun.net.httpserver`
     * pertenece al módulo `jdk.httpserver`, que no está en el classpath de
     * compilación de los unit tests.
     */
    @Test
    fun `un fallo http con credenciales en la url no las filtra`() =
        runBlocking {
            conServidor(401, "") { url ->
                val destino = destinoTemporal()
                val urlConCredenciales =
                    url.replace("127.0.0.1", "usuario:secreto@127.0.0.1")

                val resultado = DescargadorHttp { true }.descargar(urlConCredenciales, destino)

                assertEquals(ResultadoDescarga.Fallo("http_4xx"), resultado)
                assertFalse(
                    "El código del fallo filtró credenciales: $resultado",
                    resultado.toString().contains("usuario") || resultado.toString().contains("secreto"),
                )
                assertFalse(destino.exists())
            }
        }

    /** E7-T7: la importación HTTP de un proveedor local controlado funciona. */
    @Test
    fun `una url http local importa el cuerpo`() =
        runBlocking {
            conServidor(200, CUERPO_M3U) { url ->
                val destino = destinoTemporal()

                val resultado = DescargadorHttp { true }.descargar(url, destino)

                assertEquals(
                    ResultadoDescarga.Ok(CUERPO_M3U.toByteArray(Charsets.UTF_8).size.toLong()),
                    resultado,
                )
                assertEquals(CUERPO_M3U, destino.readText(Charsets.UTF_8))
            }
        }

    @Test
    fun `un 5xx devuelve http_5xx sin escribir el destino`() =
        runBlocking {
            conServidor(500, "") { url ->
                val destino = destinoTemporal()

                val resultado = DescargadorHttp { true }.descargar(url, destino)

                assertEquals(ResultadoDescarga.Fallo("http_5xx"), resultado)
                assertFalse(destino.exists())
            }
        }

    @Test(timeout = 30_000)
    fun `un servidor que no responde devuelve timeout`() =
        runBlocking {
            conServidorMudo { url ->
                val destino = destinoTemporal()
                val rapido = DescargadorHttp(timeoutConexionMs = 1000, timeoutLecturaMs = 300) { true }

                val resultado = rapido.descargar(url, destino)

                assertEquals(ResultadoDescarga.Fallo("timeout"), resultado)
                assertFalse(destino.exists())
            }
        }

    @Test
    fun `una conexion rechazada se informa tipada`() =
        runBlocking {
            val servidor = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            val puerto = servidor.localPort
            servidor.close()
            val destino = destinoTemporal()

            val resultado = DescargadorHttp { true }.descargar("http://127.0.0.1:$puerto/lista.m3u", destino)

            assertEquals(ResultadoDescarga.Fallo("conexion_rechazada"), resultado)
            assertFalse(destino.exists())
        }

    /** `.invalid` es un TLD reservado (RFC 6761): nunca resuelve, con o sin red. */
    @Test(timeout = 30_000)
    fun `un host que no resuelve se informa tipado`() =
        runBlocking {
            val destino = destinoTemporal()

            val resultado = DescargadorHttp { true }.descargar("http://lista.prueba.invalid/lista.m3u", destino)

            assertEquals(ResultadoDescarga.Fallo("dns_no_resuelto"), resultado)
            assertFalse(destino.exists())
        }

    /** Un socket en escucha y sin atender: si el descargador conectara, colgaría. */
    @Test
    fun `una politica que bloquea el texto claro se informa sin conectar`() =
        runBlocking {
            val servidor = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
            try {
                val destino = destinoTemporal()

                val resultado =
                    DescargadorHttp { false }.descargar("http://127.0.0.1:${servidor.localPort}/lista.m3u", destino)

                assertEquals(ResultadoDescarga.Fallo("bloqueo_http"), resultado)
                assertFalse(destino.exists())
            } finally {
                servidor.close()
            }
        }

    /**
     * La validación TLS se mantiene: el servidor de prueba usa un certificado
     * autofirmado (`localhost.p12`, fixture) que el trust store del cliente no
     * reconoce, y el descargador no debe aceptarlo jamás.
     */
    @Test(timeout = 30_000)
    fun `un certificado tls invalido se rechaza sin importarlo`() =
        runBlocking {
            conServidorTls { url ->
                val destino = destinoTemporal()

                val resultado = DescargadorHttp { true }.descargar(url, destino)

                assertEquals(ResultadoDescarga.Fallo("sin_conexion"), resultado)
                assertFalse(destino.exists())
            }
        }

    /**
     * Con el certificado del servidor local como ancla de confianza (el mismo
     * keystore del fixture, vía `TrustManagerFactory`), la importación HTTPS
     * funciona: TLS sigue validando, solo cambia el ancla en este test.
     */
    @Test(timeout = 30_000)
    fun `un servidor https local de confianza importa el cuerpo`() =
        runBlocking {
            conServidorTlsDeConfianza { url ->
                val destino = destinoTemporal()

                val resultado = DescargadorHttp { true }.descargar(url, destino)

                assertEquals(
                    ResultadoDescarga.Ok(CUERPO_M3U.toByteArray(Charsets.UTF_8).size.toLong()),
                    resultado,
                )
                assertEquals(CUERPO_M3U, destino.readText(Charsets.UTF_8))
            }
        }

    /** Servidor HTTP de un solo uso que responde [codigo] con [cuerpo] y cierra. */
    private suspend fun conServidor(
        codigo: Int,
        cuerpo: String,
        bloque: suspend (String) -> Unit,
    ) {
        val servidor = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        coroutineScope {
            val respuesta =
                async(Dispatchers.IO) {
                    runCatching {
                        servidor.use { it.accept().use { socket -> responder(socket, codigo, cuerpo) } }
                    }
                }
            try {
                bloque("http://127.0.0.1:${servidor.localPort}/lista.m3u")
            } finally {
                servidor.close()
                withTimeoutOrNull(TIEMPO_ESPERA_MS) { respuesta.await() }
            }
        }
    }

    /** Servidor HTTP que acepta la conexión y nunca responde. */
    private suspend fun conServidorMudo(bloque: suspend (String) -> Unit) {
        val servidor = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        coroutineScope {
            val respuesta =
                async(Dispatchers.IO) {
                    runCatching {
                        servidor
                            .accept()
                            .use { socket -> socket.getInputStream().bufferedReader().readLine() }
                    }
                }
            try {
                bloque("http://127.0.0.1:${servidor.localPort}/lista.m3u")
            } finally {
                servidor.close()
                withTimeoutOrNull(TIEMPO_ESPERA_MS) { respuesta.await() }
            }
        }
    }

    /** Servidor HTTPS con el certificado del fixture; el cliente no lo confía. */
    private suspend fun conServidorTls(bloque: suspend (String) -> Unit) {
        val servidor = servidorTls()
        coroutineScope {
            val respuesta =
                async(Dispatchers.IO) {
                    runCatching {
                        servidor.use { it.accept().use { socket -> responder(socket, 200, CUERPO_M3U) } }
                    }
                }
            try {
                // El host de la URL debe coincidir con el certificado del fixture
                // (`CN=localhost`); con una IP la verificación de hostname fallaría.
                bloque("https://localhost:${servidor.localPort}/lista.m3u")
            } finally {
                servidor.close()
                withTimeoutOrNull(TIEMPO_ESPERA_MS) { respuesta.await() }
            }
        }
    }

    /**
     * Igual que [conServidorTls] pero el cliente confía en el certificado del
     * fixture durante el test y restaura el ancla por defecto al salir.
     *
     * El ancla se instala con `HttpsURLConnection.setDefaultSSLSocketFactory`
     * y no con `SSLContext.setDefault`: la fábrica por defecto de
     * `SSLSocketFactory` se cachea la primera vez que alguien la consulta y
     * `SSLContext.setDefault` no la reemplaza, así que el ancla no se aplicaría
     * si otro test ya abrió una conexión TLS antes.
     */
    private suspend fun conServidorTlsDeConfianza(bloque: suspend (String) -> Unit) {
        val servidor = servidorTls()
        val fabricaConfianza = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        fabricaConfianza.init(keystorePrueba())
        val contextoCliente = SSLContext.getInstance("TLS")
        contextoCliente.init(null, fabricaConfianza.trustManagers, null)
        val anterior = HttpsURLConnection.getDefaultSSLSocketFactory()
        HttpsURLConnection.setDefaultSSLSocketFactory(contextoCliente.socketFactory)
        try {
            coroutineScope {
                val respuesta =
                    async(Dispatchers.IO) {
                        runCatching {
                            servidor.use { it.accept().use { socket -> responder(socket, 200, CUERPO_M3U) } }
                        }
                    }
                try {
                    // Igual que en [conServidorTls]: el host de la URL debe
                    // coincidir con el certificado del fixture.
                    bloque("https://localhost:${servidor.localPort}/lista.m3u")
                } finally {
                    servidor.close()
                    withTimeoutOrNull(TIEMPO_ESPERA_MS) { respuesta.await() }
                }
            }
        } finally {
            HttpsURLConnection.setDefaultSSLSocketFactory(anterior)
        }
    }

    private fun servidorTls(): SSLServerSocket {
        val fabricaClaves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        fabricaClaves.init(keystorePrueba(), CLAVE_ALMACEN.toCharArray())
        val contexto = SSLContext.getInstance("TLS")
        contexto.init(fabricaClaves.keyManagers, null, null)
        // Backlog real: con cola 0, un SYN que llega antes de que la corrutina
        // de accept() arranque es rechazado por el kernel y el cliente ve
        // `conexion_rechazada`. El mismo backlog por defecto (50) usan los
        // helpers de `ServerSocket` de esta clase. El bind es `localhost` y no
        // `127.0.0.1`: el certificado del fixture es `CN=localhost` y la
        // verificación de hostname de TLS compara el host de la URL; para una
        // IP el JDK no usa el CN como fallback, así que con `127.0.0.1` el
        // handshake siempre fallaría.
        return contexto.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("localhost"))
            as SSLServerSocket
    }

    private fun keystorePrueba(): KeyStore =
        KeyStore.getInstance("PKCS12").also { almacen ->
            val entrada: InputStream =
                requireNotNull(javaClass.getResourceAsStream(RUTA_ALMACEN)) { "falta el fixture $RUTA_ALMACEN" }
            entrada.use { almacen.load(it, CLAVE_ALMACEN.toCharArray()) }
        }

    /**
     * Escribe una respuesta HTTP/1.1 de una sola respuesta y cierra el socket.
     * Consume las cabeceras de la petición antes de responder.
     */
    private fun responder(
        socket: Socket,
        codigo: Int,
        cuerpo: String,
    ) {
        socket.use { abierta ->
            val lector = abierta.getInputStream().bufferedReader()
            while (lector.readLine()?.isNotEmpty() == true) {
                // Consume la línea de petición y sus cabeceras.
            }
            val salida: OutputStream = abierta.getOutputStream()
            val bytes = cuerpo.toByteArray(Charsets.UTF_8)
            val frase =
                when (codigo) {
                    401 -> "Unauthorized"
                    500 -> "Internal Server Error"
                    else -> "OK"
                }
            salida.write(
                (
                    "HTTP/1.1 $codigo $frase\r\n" +
                        "Content-Type: text/plain\r\n" +
                        "Content-Length: ${bytes.size}\r\n" +
                        "Connection: close\r\n\r\n"
                ).toByteArray(),
            )
            salida.write(bytes)
            salida.flush()
        }
    }

    private fun destinoTemporal(): File {
        val directorio = Files.createTempDirectory("descargador-test").toFile()
        directorio.deleteOnExit()
        return File(directorio, "descarga.bin")
    }

    private companion object {
        const val TIEMPO_ESPERA_MS = 10_000L
        const val CLAVE_ALMACEN = "prueba-local"
        const val RUTA_ALMACEN = "/certificados/localhost.p12"
        val CUERPO_M3U =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="uno" group-title="Noticias",Uno
            https://example.com/uno
            """.trimIndent() + "\n"
    }
}
