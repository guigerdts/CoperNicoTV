package com.copernicotv.app.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeoutOrNull
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.net.InetAddress
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

    private suspend fun assertFallo(
        cliente: ClienteHttpOta,
        url: String,
        esperado: FalloHttp,
    ) {
        val resultado = cliente.obtener(url, 1024)
        assertTrue(resultado is ResultadoHttp.Fallo)
        assertEquals(esperado, (resultado as ResultadoHttp.Fallo).tipo)
    }

    private fun clientePruebas(): ClienteHttpOta =
        ClienteHttpOtaUrlConnection(
            contextoPruebas = contextoCliente(),
            hostsPermitidos = setOf("localhost"),
            permitirPuertoDePruebas = true,
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
        while (true) {
            val actual = lector.readLine() ?: break
            if (actual.isEmpty()) break
            val separador = actual.indexOf(':')
            if (separador > 0) {
                cabeceras[actual.substring(0, separador).trim().lowercase()] = actual.substring(separador + 1).trim()
            }
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
    ) {
        fun cabecera(): String {
            val extra = cabeceras.entries.joinToString("") { "${it.key}: ${it.value}\r\n" }
            return "HTTP/1.1 $codigo Respuesta\r\n" +
                "Content-Length: ${cuerpo.size}\r\n" +
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
