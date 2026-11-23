package com.copernicotv.app.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.nio.file.Files
import java.security.KeyStore
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.KeyManagerFactory
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLServerSocket
import javax.net.ssl.TrustManagerFactory

/**
 * Cliente HTTP del OTA contra un servidor TLS local.
 *
 * Cubre lo que un doble no puede probar: que el esquema y el host se validen
 * antes de abrir el socket, que la redirección no salga de la lista, que el
 * cuerpo acotado corte de verdad y que la reanudación use `Range` y escriba
 * solo el resto.
 */
class ClienteHttpOtaTest {
    @Test
    fun `rechaza http hosts fuera de la lista y credenciales en la url`() =
        runBlocking {
            val cliente = ClienteHttpOtaUrlConnection()

            assertFallo(cliente, "http://github.com/release", FalloHttp.URL_INSEGURA)
            assertFallo(cliente, "https://evil.example/release", FalloHttp.URL_INSEGURA)
            assertFallo(cliente, "https://usuario:clave@github.com/release", FalloHttp.URL_INSEGURA)
            assertFallo(cliente, "https://github.com:8443/release", FalloHttp.URL_INSEGURA)
        }

    @Test
    fun `descarga completa escribe el cuerpo y reporta progreso`() =
        runBlocking {
            val contenido = "APK-de-prueba".toByteArray()
            val destino = destinoTemporal()
            val progreso = mutableListOf<Long>()

            conServidor(listOf({ _ -> Respuesta(200, contenido) })) { url ->
                val resultado = clientePruebas().descargar(url, destino, 0L) { escritos, _ -> progreso += escritos }

                assertTrue(resultado is ResultadoDescargaHttp.Completa)
                assertEquals(contenido.size.toLong(), destino.length())
                assertEquals(contenido.toList(), destino.readBytes().toList())
                assertEquals(contenido.size.toLong(), progreso.last())
            }
        }

    @Test
    fun `reanuda con Range y escribe solo el resto`() =
        runBlocking {
            val contenido = "0123456789".toByteArray()
            val destino = destinoTemporal()
            destino.writeBytes(contenido.copyOfRange(0, 4))
            val rango = AtomicReference<String?>()

            conServidor(
                listOf { peticion ->
                    rango.set(peticion.cabeceras["range"])
                    Respuesta(
                        codigo = 206,
                        cuerpo = contenido.copyOfRange(4, contenido.size),
                        cabeceras = mapOf("Content-Range" to "bytes 4-9/10"),
                    )
                },
            ) { url ->
                val resultado = clientePruebas().descargar(url, destino, 4L) { _, _ -> }

                assertTrue(resultado is ResultadoDescargaHttp.Completa)
                assertEquals("bytes=4-", rango.get())
                assertEquals(contenido.toList(), destino.readBytes().toList())
            }
        }

    @Test
    fun `sigue una redireccion permitida`() =
        runBlocking {
            val contenido = "redirigido".toByteArray()

            conServidor(
                listOf(
                    { _ -> Respuesta(302, ByteArray(0), mapOf("Location" to "/destino")) },
                    { _ -> Respuesta(200, contenido) },
                ),
            ) { url ->
                val resultado = clientePruebas().obtener(url, 1024)

                assertTrue(resultado is ResultadoHttp.Exito)
                assertEquals(contenido.toList(), (resultado as ResultadoHttp.Exito).cuerpo.toList())
            }
        }

    @Test
    fun `rechaza una redireccion fuera de la lista`() =
        runBlocking {
            conServidor(
                listOf { _ -> Respuesta(302, ByteArray(0), mapOf("Location" to "https://evil.example/robo")) },
            ) { url ->
                val resultado = clientePruebas().obtener(url, 1024)

                assertTrue(resultado is ResultadoHttp.Fallo)
                assertEquals(FalloHttp.REDIRECCION_INVALIDA, (resultado as ResultadoHttp.Fallo).tipo)
            }
        }

    @Test
    fun `corta un cuerpo mayor al tope`() =
        runBlocking {
            val enorme = ByteArray(8 * 1024) { 7 }

            conServidor(listOf({ _ -> Respuesta(200, enorme) })) { url ->
                val resultado = clientePruebas().obtener(url, 256)

                assertTrue(resultado is ResultadoHttp.Fallo)
                assertEquals(FalloHttp.RESPUESTA_DEMASIADO_GRANDE, (resultado as ResultadoHttp.Fallo).tipo)
            }
        }

    @Test
    fun `un host que no resuelve se informa como falta de red`() =
        runBlocking {
            val cliente =
                ClienteHttpOtaUrlConnection(
                    contextoPruebas = contextoCliente(),
                    hostsPermitidos = setOf("sin-dns.invalid"),
                    permitirPuertoDePruebas = true,
                )

            assertFallo(cliente, "https://sin-dns.invalid/recurso", FalloHttp.SIN_RED)
        }

    @Test
    fun `una conexion rechazada se informa como falta de red`() =
        runBlocking {
            val puerto = ServerSocket(0).use { it.localPort }

            assertFallo(clientePruebas(), "https://localhost:$puerto/recurso", FalloHttp.SIN_RED)
        }

    @Test
    fun `un servidor que no responde se informa como timeout`() =
        runBlocking {
            val servidor = servidorTls()
            val atencion =
                launch(Dispatchers.IO) {
                    runCatching { servidor.accept().use { delay(5_000) } }
                }
            try {
                assertFallo(
                    clientePruebas(lecturaMs = 500),
                    "https://localhost:${servidor.localPort}/recurso",
                    FalloHttp.TIMEOUT,
                )
            } finally {
                servidor.close()
                atencion.cancel()
            }
        }

    @Test
    fun `un cuerpo mas corto que el declarado se informa como descarga incompleta`() =
        runBlocking {
            val completo = "0123456789".toByteArray()

            conServidor(
                listOf { _ -> Respuesta(200, completo.copyOfRange(0, 4), largoDeclarado = completo.size) },
            ) { url ->
                val resultado = clientePruebas().obtener(url, 1024)

                assertTrue(resultado is ResultadoHttp.Fallo)
                assertEquals(FalloHttp.IO, (resultado as ResultadoHttp.Fallo).tipo)
            }
        }

    @Test
    fun `un http 404 se informa como estado http`() =
        runBlocking {
            conServidor(listOf({ _ -> Respuesta(404, "no existe".toByteArray()) })) { url ->
                assertFallo(clientePruebas(), url, FalloHttp.ESTADO_HTTP)
            }
        }

    @Test
    fun `un http 500 al descargar se informa como estado http reintentable`() =
        runBlocking {
            conServidor(listOf({ _ -> Respuesta(500, "fallo".toByteArray()) })) { url ->
                val resultado = clientePruebas().descargar(url, destinoTemporal(), 0L) { _, _ -> }

                assertTrue(resultado is ResultadoDescargaHttp.Fallo)
                assertEquals(FalloHttp.ESTADO_HTTP, (resultado as ResultadoDescargaHttp.Fallo).tipo)
                assertTrue(resultado.reintentable)
            }
        }

    private suspend fun assertFallo(
        cliente: ClienteHttpOta,
        url: String,
        esperado: FalloHttp,
    ) {
        val resultado = cliente.obtener(url, 1024)
        assertTrue("se esperaba un fallo para $url, se obtuvo $resultado", resultado is ResultadoHttp.Fallo)
        val fallo = resultado as ResultadoHttp.Fallo
        assertEquals("tipo para $url (detalle: ${fallo.detalle})", esperado, fallo.tipo)
    }

    private fun clientePruebas(
        conexionMs: Int = 15_000,
        lecturaMs: Int = 30_000,
    ): ClienteHttpOta =
        ClienteHttpOtaUrlConnection(
            contextoPruebas = contextoCliente(),
            hostsPermitidos = setOf("localhost"),
            permitirPuertoDePruebas = true,
            tiempoConexionMs = conexionMs,
            tiempoLecturaMs = lecturaMs,
        )

    private suspend fun conServidor(
        atendidos: List<(Peticion) -> Respuesta>,
        bloque: suspend (String) -> Unit,
    ) {
        val servidor = servidorTls()
        coroutineScope {
            val atencion =
                launch(Dispatchers.IO) {
                    runCatching {
                        atendidos.forEach { responder ->
                            servidor.accept().use { socket -> atender(socket, responder) }
                        }
                    }
                }
            try {
                bloque("https://localhost:${servidor.localPort}/recurso")
            } finally {
                servidor.close()
                withTimeoutOrNull(TIEMPO_ESPERA_MS) { atencion.join() }
            }
        }
    }

    private fun atender(
        socket: Socket,
        responder: (Peticion) -> Respuesta,
    ) {
        val lector = socket.getInputStream().bufferedReader()
        val linea = lector.readLine() ?: return
        val cabeceras = mutableMapOf<String, String>()
        var actual = lector.readLine()
        while (!actual.isNullOrEmpty()) {
            val separador = actual.indexOf(':')
            if (separador > 0) {
                cabeceras[actual.substring(0, separador).trim().lowercase()] = actual.substring(separador + 1).trim()
            }
            actual = lector.readLine()
        }
        val respuesta = responder(Peticion(linea, cabeceras))
        val salida = socket.getOutputStream()
        salida.write(respuesta.cabecera().toByteArray())
        salida.write(respuesta.cuerpo)
        salida.flush()
    }

    private fun servidorTls(): SSLServerSocket {
        val fabricaClaves = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm())
        fabricaClaves.init(keystorePrueba(), CLAVE_ALMACEN.toCharArray())
        val contexto = SSLContext.getInstance("TLS")
        contexto.init(fabricaClaves.keyManagers, null, null)
        return contexto.serverSocketFactory.createServerSocket(0, 50, InetAddress.getByName("localhost"))
            as SSLServerSocket
    }

    private fun contextoCliente(): SSLContext {
        val fabricaConfianza = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
        fabricaConfianza.init(keystorePrueba())
        val contexto = SSLContext.getInstance("TLS")
        contexto.init(null, fabricaConfianza.trustManagers, null)
        return contexto
    }

    private fun keystorePrueba(): KeyStore =
        KeyStore.getInstance("PKCS12").also { almacen ->
            val entrada: InputStream =
                requireNotNull(javaClass.getResourceAsStream(RUTA_ALMACEN)) { "falta el fixture $RUTA_ALMACEN" }
            entrada.use { almacen.load(it, CLAVE_ALMACEN.toCharArray()) }
        }

    private fun destinoTemporal(): File {
        val directorio = Files.createTempDirectory("ota-cliente-test").toFile()
        directorio.deleteOnExit()
        return File(directorio, "ota.apk.part")
    }

    private data class Peticion(
        val linea: String,
        val cabeceras: Map<String, String>,
    )

    private data class Respuesta(
        val codigo: Int,
        val cuerpo: ByteArray,
        val cabeceras: Map<String, String> = emptyMap(),
        val largoDeclarado: Int? = null,
    ) {
        fun cabecera(): String {
            val extra = cabeceras.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            return "HTTP/1.1 $codigo Respuesta\r\n" +
                "Content-Length: ${largoDeclarado ?: cuerpo.size}\r\n" +
                extra +
                "Connection: close\r\n\r\n"
        }
    }

    private companion object {
        const val TIEMPO_ESPERA_MS = 10_000L
        const val CLAVE_ALMACEN = "prueba-local"
        const val RUTA_ALMACEN = "/certificados/localhost.p12"
    }
}
