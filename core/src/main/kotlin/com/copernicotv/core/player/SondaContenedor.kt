package com.copernicotv.core.player

import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL

/**
 * Evidencia observada por [SondaContenedor]: MIME y bytes iniciales de la
 * respuesta final junto con la traza de la cadena de saltos. No es un data
 * class porque el contenido son bytes y la igualdad estructural no aplica.
 */
class SondeoContenedor(
    val codigoInicial: Int?,
    val codigoFinal: Int?,
    val redirecciones: Int,
    val esquemaInicial: String?,
    val esquemaFinal: String?,
    val circular: Boolean,
    val limiteAlcanzado: Boolean,
    val tipoMime: String?,
    val bytes: ByteArray,
) {
    /** Formato observado por el detector a partir de MIME y bytes. */
    val observado: FormatoObservado = DetectarContenedor.observar(tipoMime, bytes)
}

/**
 * Sonda acotada de contenedor (E7-T9, S3/S4/S6).
 *
 * Es el replay de observación que E7-T8 ya estableció: SOLO se usa tras un
 * fallo real de reproducción, nunca en el camino de streaming. Realiza una
 * petición GET con rango limitado (`Range: bytes=0-N-1`), sigue la cadena de
 * redirecciones con las MISMAS reglas de seguridad del trazador E7-T8
 * (`instanceFollowRedirects=false`, solo esquemas HTTP(S), sin ciclos, sin
 * reenviar cabeceras entre dominios) y lee como máximo [limiteBytes] del
 * cuerpo final, aunque el servidor ignore el rango y el stream sea
 * interminable. De la respuesta final captura `Content-Type` y los bytes
 * iniciales; el detector clasifica el formato observado.
 *
 * Un fallo de conexión produce un sondeo vacío (sin código, sin bytes); una
 * URL no interpretable no abre conexiones.
 *
 * La [conexionDe] inyectable permite pruebas con conexiones falsas; el valor
 * por defecto usa el `HttpURLConnection` real de la plataforma.
 */
class SondaContenedor(
    private val maximoSaltos: Int = 20,
    private val limiteBytes: Int = DetectarContenedor.LONGITUD_MUESTRA,
    private val timeoutMs: Int = TIEMPO_ESPERA_MS,
    private val cabeceras: Map<String, String> = emptyMap(),
    private val conexionDe: (URL) -> HttpURLConnection = {
        it.openConnection() as HttpURLConnection
    },
) {
    fun sondear(urlOriginal: String): SondeoContenedor {
        val primera = parsear(urlOriginal) ?: return sondeoVacio
        val recorrido = observar(primera)
        return SondeoContenedor(
            codigoInicial = recorrido.codigoInicial,
            codigoFinal = recorrido.codigoFinal,
            redirecciones = recorrido.redirecciones,
            esquemaInicial = primera.protocol,
            esquemaFinal = recorrido.urlFinal?.protocol,
            circular = recorrido.circular,
            limiteAlcanzado = recorrido.limiteAlcanzado,
            tipoMime = recorrido.tipoMime,
            bytes = recorrido.bytes,
        )
    }

    private val sondeoVacio =
        SondeoContenedor(
            codigoInicial = null,
            codigoFinal = null,
            redirecciones = 0,
            esquemaInicial = null,
            esquemaFinal = null,
            circular = false,
            limiteAlcanzado = false,
            tipoMime = null,
            bytes = ByteArray(0),
        )

    /** Resultado interno: hasta dónde llegó la cadena y qué observó. */
    private data class Recorrido(
        val urlFinal: URL?,
        val codigoInicial: Int?,
        val codigoFinal: Int?,
        val redirecciones: Int,
        val circular: Boolean,
        val limiteAlcanzado: Boolean,
        val tipoMime: String?,
        val bytes: ByteArray,
    )

    /** Construye el recorrido cerrando la lectura del cuerpo de la respuesta. */
    private fun recorrido(
        url: URL?,
        codigoInicial: Int?,
        codigoFinal: Int?,
        redirecciones: Int,
        circular: Boolean,
        limiteAlcanzado: Boolean,
        respuesta: Respuesta,
    ): Recorrido =
        Recorrido(
            url,
            codigoInicial,
            codigoFinal,
            redirecciones,
            circular,
            limiteAlcanzado,
            respuesta.tipoMime,
            leer(respuesta.cuerpo),
        )

    /** Recorre los saltos con `followRedirects=false` y observa el final. */
    private fun observar(primera: URL): Recorrido {
        var url: URL = primera
        var codigoInicial: Int? = null
        var codigoFinal: Int? = null
        var redirecciones = 0
        val visitadas = mutableSetOf(url.toString())
        while (true) {
            val respuesta = abrir(url)
            if (respuesta.codigo == SIN_RESPUESTA) {
                return Recorrido(null, null, null, redirecciones, false, false, null, ByteArray(0))
            }
            if (codigoInicial == null) codigoInicial = respuesta.codigo
            codigoFinal = respuesta.codigo
            if (respuesta.codigo !in REDIRECCIONES) {
                return recorrido(url, codigoInicial, codigoFinal, redirecciones, false, false, respuesta)
            }
            redirecciones += 1
            val siguiente =
                respuesta.location
                    ?.let { location -> runCatching { URL(url, location) }.getOrNull() }
                    ?: return recorrido(url, codigoInicial, codigoFinal, redirecciones, false, false, respuesta)
            if (siguiente.protocol !in ESQUEMAS_SOPORTADOS) {
                // El salto no se sigue (el motor lo rechazaría); el esquema
                // ajeno queda visible en la traza para el diagnóstico.
                return recorrido(siguiente, codigoInicial, codigoFinal, redirecciones, false, false, respuesta)
            }
            if (visitadas.contains(siguiente.toString())) {
                return recorrido(siguiente, codigoInicial, codigoFinal, redirecciones, true, false, respuesta)
            }
            visitadas.add(siguiente.toString())
            if (redirecciones >= maximoSaltos) {
                return recorrido(url, codigoInicial, codigoFinal, redirecciones, false, true, respuesta)
            }
            url = siguiente
        }
    }

    /** Lee como máximo [limiteBytes] del cuerpo; un cuerpo interminable no se descarga completo. */
    private fun leer(cuerpo: java.io.InputStream?): ByteArray {
        if (cuerpo == null) return ByteArray(0)
        val acumulado = java.io.ByteArrayOutputStream(limiteBytes)
        val bufer = ByteArray(1024)
        try {
            var total = 0
            while (total < limiteBytes) {
                val leidos = cuerpo.read(bufer, 0, minOf(bufer.size, limiteBytes - total))
                if (leidos < 0) break
                acumulado.write(bufer, 0, leidos)
                total += leidos
            }
        } catch (excepcion: Exception) {
            // La observación es mejor esfuerzo: lo leído hasta el fallo es la evidencia.
        } finally {
            runCatching { cuerpo.close() }
        }
        return acumulado.toByteArray()
    }

    /** Interpreta la URL original; nulo si no es una URL válida. */
    private fun parsear(texto: String): URL? = runCatching { URL(texto.trim()) }.getOrNull()

    /** Respuesta de un salto: código, Location, MIME y cuerpo en streaming. */
    private data class Respuesta(
        val codigo: Int,
        val location: String?,
        val tipoMime: String?,
        val cuerpo: java.io.InputStream?,
    )

    /** Realiza una conexión GET a [url] y captura código, Location, MIME y cuerpo. */
    private fun abrir(url: URL): Respuesta {
        val conexion =
            try {
                conexionDe(url)
            } catch (excepcion: IOException) {
                return Respuesta(SIN_RESPUESTA, null, null, null)
            }
        try {
            preparar(conexion)
            conexion.connect()
            val codigo = conexion.responseCode
            val cuerpo =
                try {
                    conexion.inputStream
                } catch (excepcion: IOException) {
                    conexion.errorStream
                }
            return Respuesta(
                codigo = codigo,
                location = conexion.getHeaderField("Location"),
                tipoMime = conexion.getHeaderField("Content-Type"),
                cuerpo = cuerpo,
            )
        } catch (excepcion: IOException) {
            return Respuesta(SIN_RESPUESTA, null, null, null)
        } finally {
            cerrarConexion(conexion)
        }
    }

    private fun preparar(conexion: HttpURLConnection) {
        conexion.requestMethod = METODO
        conexion.instanceFollowRedirects = false
        conexion.connectTimeout = timeoutMs
        conexion.readTimeout = timeoutMs
        for ((nombre, valor) in cabeceras) {
            conexion.setRequestProperty(nombre, valor)
        }
        conexion.setRequestProperty("Range", "bytes=0-${limiteBytes - 1}")
    }

    private fun cerrarConexion(conexion: HttpURLConnection) {
        runCatching { conexion.errorStream?.close() }
        runCatching { conexion.disconnect() }
    }
}

private const val TIEMPO_ESPERA_MS = 8000
private const val SIN_RESPUESTA = -1
private const val METODO = "GET"
private val ESQUEMAS_SOPORTADOS = setOf("http", "https")
private val REDIRECCIONES = 300..399
