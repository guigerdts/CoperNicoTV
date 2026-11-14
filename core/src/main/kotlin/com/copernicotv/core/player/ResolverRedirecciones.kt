package com.copernicotv.core.player

/**
 * Traza observable de una cadena de redirecciones HTTP de un stream (E7-T8,
 * S5). Alimenta el diagnóstico copiable sin exponer la URL ni credenciales.
 */
data class TrazaRedireccion(
    val codigoInicial: Int?,
    val codigoFinal: Int?,
    val redirecciones: Int,
    val esquemaInicial: String?,
    val esquemaFinal: String?,
    val circular: Boolean,
    val limiteAlcanzado: Boolean,
)

/**
 * Trazador puro de redirecciones HTTP (E7-T8, S5/S6).
 *
 * Reproduce la cadena de saltos de una URL con `followRedirects=false`:
 * registra el código inicial y final, la cantidad de redirecciones, el esquema
 * inicial y final, y detecta ciclos y límite. Solo sigue HTTP(S). Se usa tras
 * un fallo real de reproducción, no en el camino de streaming.
 *
 * La [conexionDe] inyectable permite pruebas con conexiones falsas; el valor
 * por defecto usa el `HttpURLConnection` real de la plataforma.
 */
class ResolverRedirecciones(
    private val maximoSaltos: Int = MAXIMO_SALTOS,
    private val timeoutMs: Int = TIEMPO_ESPERA_MS,
    private val cabeceras: Map<String, String> = emptyMap(),
    private val conexionDe: (java.net.URL) -> java.net.HttpURLConnection = {
        it.openConnection() as java.net.HttpURLConnection
    },
) {
    fun resolver(urlOriginal: String): TrazaRedireccion {
        val primera = urlOriginal.trim().let { texto ->
            runCatching { java.net.URL(texto) }.getOrNull()
        }
        if (primera == null) {
            return TrazaRedireccion(
                codigoInicial = null, codigoFinal = null, redirecciones = 0,
                esquemaInicial = null, esquemaFinal = null,
                circular = false, limiteAlcanzado = false,
            )
        }
        var url: java.net.URL = primera
        var codigoInicial: Int? = null
        var codigoFinal: Int? = null
        var saltos = 0
        var circular = false
        var limiteAlcanzado = false
        val visitadas = mutableSetOf(url.toString())
        while (true) {
            val respuesta = abrir(url)
            if (respuesta.codigo == SIN_RESPUESTA) break
            if (codigoInicial == null) codigoInicial = respuesta.codigo
            codigoFinal = respuesta.codigo
            if (respuesta.codigo !in REDIRECCIONES) break
            val location = respuesta.location ?: break
            val siguiente = runCatching { java.net.URL(url, location) }.getOrNull() ?: break
            if (siguiente.protocol !in ESQUEMAS_SOPORTADOS) {
                // El salto no se sigue (el motor lo rechazaría); el esquema
                // ajeno queda visible en la traza para el diagnóstico.
                url = siguiente
                break
            }
            if (visitadas.contains(siguiente.toString())) {
                url = siguiente
                circular = true
                break
            }
            visitadas.add(siguiente.toString())
            if (saltos >= maximoSaltos) {
                limiteAlcanzado = true
                break
            }
            saltos += 1
            url = siguiente
        }
        return TrazaRedireccion(
            codigoInicial = codigoInicial,
            codigoFinal = codigoFinal,
            redirecciones = saltos,
            esquemaInicial = primera.protocol,
            esquemaFinal = url.protocol,
            circular = circular,
            limiteAlcanzado = limiteAlcanzado,
        )
    }

    /** Respuesta de un salto: código y Location (si es redirección). */
    private data class Respuesta(
        val codigo: Int,
        val location: String?,
    )

    /** Realiza una conexión GET a [url] y lee código y Location de una vez. */
    private fun abrir(url: java.net.URL): Respuesta {
        val conexion =
            try {
                conexionDe(url)
            } catch (excepcion: java.io.IOException) {
                return Respuesta(SIN_RESPUESTA, null)
            }
        try {
            preparar(conexion)
            conexion.connect()
            return Respuesta(
                codigo = conexion.responseCode,
                location = conexion.getHeaderField("Location"),
            )
        } catch (excepcion: java.io.IOException) {
            return Respuesta(SIN_RESPUESTA, null)
        } finally {
            cerrarConexion(conexion)
        }
    }

    private fun preparar(conexion: java.net.HttpURLConnection) {
        conexion.requestMethod = METODO
        conexion.instanceFollowRedirects = false
        conexion.connectTimeout = timeoutMs
        conexion.readTimeout = timeoutMs
        for ((nombre, valor) in cabeceras) {
            conexion.setRequestProperty(nombre, valor)
        }
    }

    private fun cerrarConexion(conexion: java.net.HttpURLConnection) {
        runCatching { conexion.inputStream?.close() }
        runCatching { conexion.errorStream?.close() }
        runCatching { conexion.disconnect() }
    }
}

private const val MAXIMO_SALTOS = 20
private const val TIEMPO_ESPERA_MS = 8000
private const val SIN_RESPUESTA = -1
private const val METODO = "GET"
private val ESQUEMAS_SOPORTADOS = setOf("http", "https")
private val REDIRECCIONES = 300..399
