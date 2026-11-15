package com.copernicotv.core.player

import java.io.ByteArrayInputStream
import java.io.InputStream
import java.net.HttpURLConnection
import java.net.URL
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * La sonda de contenedor (E7-T9, S3/S6) es un replay acotado solo tras un
 * fallo real de reproducción: una petición de rango con servidores
 * controlados que observa MIME y bytes iniciales siguiendo la cadena de
 * redirecciones con las mismas reglas de seguridad del trazador E7-T8 (sin
 * seguir esquemas distintos de HTTP(S), sin reenviar credenciales entre
 * dominios y sin descargar el stream completo).
 */
class SondaContenedorTest {
    /** Conexión falsa con código, Location, MIME y cuerpo controlados. */
    @Suppress("EmptyFunctionBlock") // los no-ops de la conexión falsa son intencionales
    private class ConexionFalsa(
        url: URL,
        private val codigo: Int,
        private val location: String?,
        private val tipoMime: String?,
        private val cuerpo: InputStream,
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
            when {
                nombre.equals("Location", ignoreCase = true) -> location
                nombre.equals("Content-Type", ignoreCase = true) -> tipoMime
                else -> null
            }

        override fun getInputStream(): InputStream = cuerpo
    }

    /** Abre conexiones falsas en el orden en que la cadena las visita. */
    private class AbridorFalso(
        vararg respuestas: Triple<Int, Pair<String?, String?>?, InputStream?>,
    ) : (URL) -> HttpURLConnection {
        private val secuencia =
            respuestas.map { (codigo, par, cuerpo) ->
                Triple(codigo, par?.first, ParRespuesta(par?.second, cuerpo))
            }
        val solicitudes =
            mutableListOf<Pair<URL, Map<String, String>>>()
        private var indice = 0

        override fun invoke(url: URL): HttpURLConnection {
            val (codigo, location, respuesta) = secuencia[indice.coerceAtMost(secuencia.lastIndex)]
            indice++
            return ConexionFalsa(
                url,
                codigo,
                location,
                respuesta.mime,
                respuesta.cuerpo ?: ByteArrayInputStream(ByteArray(0)),
                solicitudes,
            )
        }

        private data class ParRespuesta(
            val mime: String?,
            val cuerpo: InputStream?,
        )
    }

    private fun respuesta(
        codigo: Int,
        location: String? = null,
        mime: String? = null,
        cuerpo: InputStream? = null,
    ): Triple<Int, Pair<String?, String?>?, InputStream?> = Triple(codigo, (location ?: mime)?.let { location to mime }, cuerpo)

    private fun sonda(
        abridor: (URL) -> HttpURLConnection,
        maximoSaltos: Int = 20,
    ): SondaContenedor =
        SondaContenedor(
            maximoSaltos = maximoSaltos,
            cabeceras = mapOf("User-Agent" to "CoperNicoTV-prueba/1"),
            conexionDe = abridor,
        )

    @Test
    fun `una respuesta 200 sin redirect observa el contenedor sin saltos`() {
        val abridor =
            AbridorFalso(
                respuesta(200, mime = "application/octet-stream", cuerpo = ByteArrayInputStream("#EXTM3U\nmaster.m3u8".toByteArray())),
            )
        val sondeo = sonda(abridor).sondear("http://127.0.0.1:9/live/1274")
        assertEquals(200, sondeo.codigoFinal)
        assertEquals(0, sondeo.redirecciones)
        assertEquals("application/octet-stream", sondeo.tipoMime)
        assertEquals(FormatoObservado.HLS, sondeo.observado)
    }

    @Test
    fun `sigue una redireccion con Location relativa y observa la respuesta final`() {
        val abridor =
            AbridorFalso(
                respuesta(302, location = "http://127.0.0.1:9/media/1.ts"),
                respuesta(200, mime = "video/mp2t", cuerpo = ByteArrayInputStream(ByteArray(570) { 0x47 })),
            )
        val sondeo = sonda(abridor).sondear("http://127.0.0.1:9/live/1274")
        assertEquals(302, sondeo.codigoInicial)
        assertEquals(200, sondeo.codigoFinal)
        assertEquals(1, sondeo.redirecciones)
        assertEquals("http", sondeo.esquemaFinal)
        assertEquals(FormatoObservado.TS, sondeo.observado)
    }

    @Test
    fun `una redireccion de http a https se sigue (matriz F)`() {
        val abridor =
            AbridorFalso(
                respuesta(302, location = "https://127.0.0.1:9/media/1.ts"),
                respuesta(200, mime = "video/mp2t", cuerpo = ByteArrayInputStream(ByteArray(570) { 0x47 })),
            )
        val sondeo = sonda(abridor).sondear("http://127.0.0.1:9/live/1274")
        assertEquals(200, sondeo.codigoFinal)
        assertEquals(1, sondeo.redirecciones)
        assertEquals("https", sondeo.esquemaFinal)
        assertEquals(FormatoObservado.TS, sondeo.observado)
    }

    @Test
    fun `la peticion lleva rango acotado y solo la cabecera segura en todos los saltos`() {
        val abridor =
            AbridorFalso(
                respuesta(302, location = "http://otro.invalid/media/1.ts"),
                respuesta(200, mime = "video/mp2t", cuerpo = ByteArrayInputStream(ByteArray(300) { 0x47 })),
            )
        val sondeo = sonda(abridor).sondear("http://origen.invalid/live/1274")
        assertTrue(sondeo.redirecciones >= 1)
        for ((_, enviadas) in abridor.solicitudes) {
            assertEquals("bytes=0-2047", enviadas["Range"])
            assertEquals("CoperNicoTV-prueba/1", enviadas["User-Agent"])
            assertTrue(enviadas.keys.none { it.equals("Cookie", ignoreCase = true) || it.equals("Authorization", ignoreCase = true) })
        }
    }

    @Test
    fun `un esquema ajeno no se sigue y la traza muestra el esquema final`() {
        val abridor =
            AbridorFalso(
                respuesta(302, location = "ftp://otro.invalid/media/1.ts"),
                respuesta(200, mime = "video/mp2t", cuerpo = ByteArrayInputStream(ByteArray(300) { 0x47 })),
            )
        val sondeo = sonda(abridor).sondear("http://origen.invalid/live/1274")
        assertEquals(1, sondeo.redirecciones)
        assertEquals("ftp", sondeo.esquemaFinal)
        assertEquals(FormatoObservado.DESCONOCIDO, sondeo.observado)
    }

    @Test
    fun `un ciclo se detecta y detiene el sondeo`() {
        val abridor =
            AbridorFalso(
                respuesta(302, location = "http://origen.invalid/b"),
                respuesta(302, location = "http://origen.invalid/a"),
            )
        val sondeo = sonda(abridor).sondear("http://origen.invalid/a")
        assertTrue(sondeo.circular)
        assertTrue(sondeo.redirecciones in 1..2)
    }

    @Test
    fun `superar el maximo de saltos detiene el sondeo con el aviso`() {
        val abridor = AbridorFalso(*Array(30) { n -> respuesta(302, location = "http://origen.invalid/salto$n") })
        val sondeo = sonda(abridor, maximoSaltos = 5).sondear("http://origen.invalid/inicio")
        assertTrue(sondeo.limiteAlcanzado)
        assertEquals(5, sondeo.redirecciones)
    }

    @Test
    fun `un fallo de conexion deja un sondeo vacio sin peticiones extra`() {
        val sondeo = sonda({ throw java.net.ConnectException("sin ruta") }).sondear("http://origen.invalid/live/1274")
        assertEquals(null, sondeo.codigoFinal)
        assertEquals(null, sondeo.tipoMime)
        assertEquals(0, sondeo.bytes.size)
        assertEquals(FormatoObservado.DESCONOCIDO, sondeo.observado)
    }

    @Test
    fun `una respuesta 403 con pagina HTML se observa como HTML`() {
        val abridor =
            AbridorFalso(
                respuesta(403, mime = "text/html", cuerpo = ByteArrayInputStream("<html><body>bloqueado</body></html>".toByteArray())),
            )
        val sondeo = sonda(abridor).sondear("http://origen.invalid/live/1274")
        assertEquals(403, sondeo.codigoFinal)
        assertEquals(FormatoObservado.HTML, sondeo.observado)
    }

    @Test
    fun `no descarga mas del limite de bytes aunque el stream sea interminable`() {
        val interminable =
            object : InputStream() {
                override fun read(): Int {
                    error("la sonda debio detenerse en el limite de bytes")
                }
            }
        val abridor =
            AbridorFalso(
                respuesta(200, mime = "application/octet-stream", cuerpo = interminable),
            )
        val sondeo = sonda(abridor).sondear("http://origen.invalid/live/1274")
        assertEquals(0, sondeo.bytes.size)
    }

    @Test
    fun `el MIME observado es el de la respuesta final, no el del primer salto`() {
        val abridor =
            AbridorFalso(
                respuesta(302, location = "http://otro.invalid/media/1.ts", mime = "text/html"),
                respuesta(200, mime = "video/mp2t", cuerpo = ByteArrayInputStream(ByteArray(300) { 0x47 })),
            )
        val sondeo = sonda(abridor).sondear("http://origen.invalid/live/1274")
        assertEquals("video/mp2t", sondeo.tipoMime)
        assertEquals(FormatoObservado.TS, sondeo.observado)
    }

    @Test
    fun `una url no interpretable deja un sondeo vacio`() {
        val abridor = AbridorFalso()
        val sondeo = sonda(abridor).sondear("no-es-una-url")
        assertEquals(null, sondeo.codigoFinal)
        assertEquals(0, sondeo.redirecciones)
        assertTrue(abridor.solicitudes.isEmpty())
    }

    /**
     * Conexión falsa que reproduce la semántica real de la plataforma: la
     * desconexión cierra el stream subyacente, así que un cuerpo no leído
     * antes del `disconnect` se pierde (en Android, `HttpURLConnection`
     * sobre OkHttp aborta la lectura). Es el escenario que la sonda vivió
     * en el emulador con CI 43744031037: cabeceras leídas, cuerpo perdido
     * tras el cierre, `DESCONOCIDO` en la evidencia de la re-selección.
     */
    @Suppress("EmptyFunctionBlock") // los no-ops de la conexión falsa son intencionales
    private class ConexionQueSeInvalida(
        url: URL,
        private val codigo: Int,
        private val tipoMime: String?,
        contenido: ByteArray,
    ) : HttpURLConnection(url) {
        private var desconectada = false
        private val cuerpo = ByteArrayInputStream(contenido)

        override fun connect() {
            connected = true
        }

        override fun disconnect() {
            desconectada = true
        }

        private fun cuerpoInvalidado(): InputStream =
            object : InputStream() {
                override fun read(): Int =
                    if (desconectada) {
                        throw java.io.IOException("conexión cerrada antes de leer el cuerpo")
                    } else {
                        this@ConexionQueSeInvalida.cuerpo.read()
                    }
            }

        override fun usingProxy(): Boolean = false

        override fun getResponseCode(): Int = codigo

        override fun getHeaderField(nombre: String): String? = if (nombre.equals("Content-Type", ignoreCase = true)) tipoMime else null

        override fun getInputStream(): InputStream = cuerpoInvalidado()
    }

    @Test
    fun `el cuerpo de la respuesta final se lee antes de cerrar la conexion`() {
        // El `disconnect` debe llegar DESPUÉS de la lectura del cuerpo: si
        // llega antes, la evidencia del contenedor se pierde y la re-selección
        // (S4/S5) ve un sondeo vacío en lugar del contenido real.
        val conexion =
            ConexionQueSeInvalida(
                URL("http://origen.invalid/live/1274"),
                200,
                "application/octet-stream",
                "#EXTM3U\nmaster.m3u8".toByteArray(),
            )
        val sondeo = sonda({ _ -> conexion }).sondear("http://origen.invalid/live/1274")
        assertEquals("#EXTM3U\nmaster.m3u8", String(sondeo.bytes, Charsets.UTF_8))
        assertEquals(FormatoObservado.HLS, sondeo.observado)
    }
}
