package com.copernicotv.core.player

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * El trazador de redirecciones HTTP (E7-T8, S5/S6) debe reconstruir la cadena
 * completa de saltos de un stream con servidores controlados: código HTTP
 * inicial y final, cantidad de redirecciones, esquema inicial y final,
 * detección de ciclos y de límite. Sin seguir esquemas distintos de HTTP(S)
 * y sin enviar cabeceras de credenciales a ningún dominio.
 */
class ResolverRedireccionesTest {
    /** Conexión falsa que responde [codigo] y Location [location] sin red. */
    private class ConexionFalsa(
        url: URL,
        private val codigo: Int,
        private val location: String?,
        private val solicitudes: MutableList<Pair<URL, Map<String, String>>>,
    ) : HttpURLConnection(url) {
        override fun connect() {
            val enviadas =
                requestProperties.entries.associate { (nombre, valores) ->
                    nombre to (valores.firstOrNull() ?: "")
                }
            connected = true
            solicitudes.add(url to enviadas)
        }

        override fun disconnect() {}
        override fun usingProxy(): Boolean = false
        override fun getResponseCode(): Int = codigo
        override fun getHeaderField(nombre: String): String? =
            if (nombre.equals("Location", ignoreCase = true)) location else null

        override fun getInputStream(): InputStream = ByteArrayInputStream(ByteArray(0))
    }

    /** Abre conexiones falsas en el orden en que la cadena las visita. */
    private class AbridorFalso(
        vararg respuestas: Pair<Int, String?>,
    ) : (URL) -> HttpURLConnection {
        private val secuencia = respuestas.toList()
        val solicitudes = mutableListOf<Pair<URL, Map<String, String>>>()
        private var indice = 0

        override fun invoke(url: URL): HttpURLConnection {
            val (codigo, location) = secuencia[indice.coerceAtMost(secuencia.lastIndex)]
            indice++
            return ConexionFalsa(url, codigo, location, solicitudes)
        }
    }

    private fun trazador(
        abridor: (URL) -> HttpURLConnection,
        maximoSaltos: Int = 20,
    ): ResolverRedirecciones =
        ResolverRedirecciones(
            maximoSaltos = maximoSaltos,
            cabeceras = mapOf("User-Agent" to "CoperNicoTV-prueba/1"),
            conexionDe = abridor,
        )

    @Test
    fun `una respuesta 200 progresiva sin redirect deja traza sin saltos`() {
        val abridor = AbridorFalso(200 to null)
        val traza = trazador(abridor).resolver("http://127.0.0.1:9/live/1.ts")
        assertEquals(200, traza.codigoInicial)
        assertEquals(200, traza.codigoFinal)
        assertEquals(0, traza.redirecciones)
        assertEquals("http", traza.esquemaInicial)
        assertEquals("http", traza.esquemaFinal)
        assertFalse(traza.circular)
        assertFalse(traza.limiteAlcanzado)
        assertEquals(1, abridor.solicitudes.size)
    }

    @Test
    fun `una respuesta 200 HLS sin redirect deja traza sin saltos`() {
        val abridor = AbridorFalso(200 to null)
        val traza = trazador(abridor).resolver("http://127.0.0.1:9/live/playlist.m3u8")
        assertEquals(200, traza.codigoInicial)
        assertEquals(200, traza.codigoFinal)
        assertEquals(0, traza.redirecciones)
    }

    @Test
    fun `un 302 dentro del mismo protocolo se sigue y queda trazado`() {
        val abridor = AbridorFalso(302 to "/final.ts", 200 to null)
        val traza = trazador(abridor).resolver("http://127.0.0.1:9/inicial.ts")
        assertEquals(302, traza.codigoInicial)
        assertEquals(200, traza.codigoFinal)
        assertEquals(1, traza.redirecciones)
        assertEquals("http", traza.esquemaInicial)
        assertEquals("http", traza.esquemaFinal)
        assertEquals(2, abridor.solicitudes.size)
    }

    @Test
    fun `una redireccion https hacia http se sigue y muestra ambos esquemas`() {
        val abridor = AbridorFalso(302 to "http://otro.invalid/final.ts", 200 to null)
        val traza = trazador(abridor).resolver("https://origen.invalid/inicial.ts")
        assertEquals(302, traza.codigoInicial)
        assertEquals(200, traza.codigoFinal)
        assertEquals(1, traza.redirecciones)
        assertEquals("https", traza.esquemaInicial)
        assertEquals("http", traza.esquemaFinal)
    }

    @Test
    fun `una redireccion http hacia https se sigue y muestra ambos esquemas`() {
        val abridor = AbridorFalso(302 to "https://seguro.invalid/final.ts", 200 to null)
        val traza = trazador(abridor).resolver("http://origen.invalid/inicial.ts")
        assertEquals("http", traza.esquemaInicial)
        assertEquals("https", traza.esquemaFinal)
        assertEquals(200, traza.codigoFinal)
    }

    @Test
    fun `un ciclo de redirecciones se detecta y detiene la cadena`() {
        val abridor = AbridorFalso(302 to "/b.ts", 302 to "/a.ts")
        val traza = trazador(abridor).resolver("http://origen.invalid/a.ts")
        assertTrue(traza.circular)
        assertEquals(1, traza.redirecciones)
        assertEquals(302, traza.codigoFinal)
        assertEquals("http", traza.esquemaFinal)
    }

    @Test
    fun `los codigos 401 y 403 quedan trazados sin saltos`() {
        for (codigo in listOf(401, 403)) {
            val abridor = AbridorFalso(codigo to null)
            val traza = trazador(abridor).resolver("http://origen.invalid/canal.ts")
            assertEquals(codigo, traza.codigoInicial)
            assertEquals(codigo, traza.codigoFinal)
            assertEquals(0, traza.redirecciones)
        }
    }

    @Test
    fun `el codigo 404 queda trazado sin saltos`() {
        val abridor = AbridorFalso(404 to null)
        val traza = trazador(abridor).resolver("http://origen.invalid/faltante.ts")
        assertEquals(404, traza.codigoInicial)
        assertEquals(404, traza.codigoFinal)
        assertEquals(0, traza.redirecciones)
    }

    @Test
    fun `una respuesta html 200 se traza como el contenido que el servidor envio`() {
        // La detección de «HTML en lugar de contenido» vive en el MIME que
        // captura el fallo del motor; el trazador solo registra la respuesta.
        val abridor = AbridorFalso(200 to null)
        val traza = trazador(abridor).resolver("http://origen.invalid/portal")
        assertEquals(200, traza.codigoFinal)
        assertEquals(0, traza.redirecciones)
    }

    @Test
    fun `la Location se usa tal cual y no hereda la query de la URL original`() {
        val abridor = AbridorFalso(302 to "http://destino.invalid/final.ts", 200 to null)
        trazador(abridor).resolver("http://origen.invalid/live?user=ana&token=secreto")
        val segunda = abridor.solicitudes[1].first
        assertEquals("http://destino.invalid/final.ts", segunda.toString())
    }

    @Test
    fun `la cabecera configurada llega a todos los saltos de la cadena`() {
        val abridor = AbridorFalso(302 to "http://destino.invalid/final.ts", 200 to null)
        trazador(abridor).resolver("http://origen.invalid/inicial.ts")
        for ((_, cabeceras) in abridor.solicitudes) {
            assertEquals("CoperNicoTV-prueba/1", cabeceras["User-Agent"])
        }
    }

    @Test
    fun `nunca se envian Cookie ni Authorization a ningun dominio`() {
        val abridor = AbridorFalso(302 to "http://destino.invalid/final.ts", 200 to null)
        trazador(abridor).resolver("http://origen.invalid/inicial.ts?user=ana&pass=secreta")
        for ((_, cabeceras) in abridor.solicitudes) {
            assertFalse(cabeceras.keys.any { it.equals("Cookie", true) })
            assertFalse(cabeceras.keys.any { it.equals("Authorization", true) })
        }
    }

    @Test
    fun `un esquema ajeno a http https detiene la cadena y queda visible`() {
        val abridor = AbridorFalso(302 to "ftp://archivos.invalid/final.ts")
        val traza = trazador(abridor).resolver("http://origen.invalid/inicial.ts")
        assertEquals(1, abridor.solicitudes.size)
        assertEquals(0, traza.redirecciones)
        assertEquals("ftp", traza.esquemaFinal)
        assertEquals(302, traza.codigoFinal)
    }

    @Test
    fun `superar el maximo de saltos detiene la cadena y se informa`() {
        val abridor =
            AbridorFalso(
                302 to "/s1.ts", 302 to "/s2.ts", 302 to "/s3.ts",
                302 to "/s4.ts", 302 to "/s5.ts", 200 to null,
            )
        val traza = trazador(abridor, maximoSaltos = 2).resolver("http://origen.invalid/a.ts")
        assertTrue(traza.limiteAlcanzado)
        assertEquals(2, traza.redirecciones)
    }

    @Test
    fun `un fallo de conexion deja traza vacia sin inventar codigos`() {
        val traza = trazador(abridor = { throw java.net.ConnectException("rechazada") }).resolver("http://origen.invalid/a.ts")
        assertEquals(null, traza.codigoInicial)
        assertEquals(null, traza.codigoFinal)
        assertEquals(0, traza.redirecciones)
        assertEquals("http", traza.esquemaInicial)
        assertEquals("http", traza.esquemaFinal)
    }

    @Test
    fun `una URL no interpretable deja traza vacia`() {
        val abridor = AbridorFalso(200 to null)
        val traza = trazador(abridor).resolver("no-es-una-url")
        assertEquals(null, traza.codigoInicial)
        assertEquals(0, abridor.solicitudes.size)
    }

    @Test
    fun `integracion real con HttpURLConnection sigue un 302 relativo hasta el 200`() =
        runBlocking {
            val servidor = java.net.ServerSocket(0)
            val atencion =
                launch(Dispatchers.IO) {
                    runCatching {
                        servidor.soTimeout = 10_000
                        servidor.accept().use { socket ->
                            responder(
                                socket,
                                "HTTP/1.1 302 Found\r\nLocation: /final.ts\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                            )
                        }
                        servidor.accept().use { socket ->
                            responder(
                                socket,
                                "HTTP/1.1 200 OK\r\nContent-Type: video/mp2t\r\nContent-Length: 4\r\nConnection: close\r\n\r\nTSBS",
                            )
                        }
                    }
                }
            try {
                val traza =
                    ResolverRedirecciones(cabeceras = mapOf("User-Agent" to "CoperNicoTV-prueba/1"))
                        .resolver("http://127.0.0.1:${servidor.localPort}/inicial.ts")
                assertEquals(302, traza.codigoInicial)
                assertEquals(200, traza.codigoFinal)
                assertEquals(1, traza.redirecciones)
                assertEquals("http", traza.esquemaInicial)
                assertEquals("http", traza.esquemaFinal)
                assertFalse(traza.circular)
            } finally {
                servidor.close()
                atencion.cancel()
            }
        }

    /** Atiende una petición HTTP cruda y responde [respuesta] textual. */
    private fun responder(
        socket: java.net.Socket,
        respuesta: String,
    ) {
        socket.soTimeout = 10_000
        val lector = socket.getInputStream().bufferedReader(Charsets.ISO_8859_1)
        var linea = lector.readLine()
        while (linea != null && linea.isNotEmpty()) {
            linea = lector.readLine()
        }
        socket.getOutputStream().write(respuesta.toByteArray(Charsets.ISO_8859_1))
        socket.getOutputStream().flush()
        socket.close()
    }
}
