package com.copernicotv.app.data.cache

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL

/** Resultado de una descarga de transporte. */
sealed interface ResultadoDescarga {
    /** Descarga completa: [bytes] es el tamaño real escrito en el destino. */
    data class Ok(val bytes: Long) : ResultadoDescarga

    /** Fallo tipado; [codigo] nunca contiene la URL ni credenciales. */
    data class Fallo(val codigo: String) : ResultadoDescarga
}

/**
 * Descarga HTTP/HTTPS hacia un archivo temporal.
 *
 * Recibe el destino en lugar de devolver bytes porque una copia XMLTV puede
 * llegar a 96 MiB y el contenido nunca debe quedar completo en memoria. La
 * implementación de producción es [DescargadorHttp]; las pruebas inyectan un
 * contador que no abre sockets.
 */
fun interface Descargador {
    suspend fun descargar(
        url: String,
        destino: File,
    ): ResultadoDescarga
}

/**
 * Descargador HTTP de producción.
 *
 * Usa `HttpURLConnection` como el resto de la importación de E2 y traduce los
 * fallos a los mismos códigos tipados: `sin_conexion`, `timeout`, `http_4xx`,
 * `http_5xx` y `esquema_no_admitido`. La URL solo se usa para abrir la
 * conexión: no se registra ni se incluye en el código devuelto, porque puede
 * contener credenciales (DC-04).
 *
 * Solo se admiten `http` y `https`: la caché nunca toca archivos SAF locales,
 * imágenes/logos ni streams (S51c).
 */
class DescargadorHttp(
    private val timeoutConexionMs: Int = TIMEOUT_CONEXION_MS,
    private val timeoutLecturaMs: Int = TIMEOUT_LECTURA_MS,
) : Descargador {
    override suspend fun descargar(
        url: String,
        destino: File,
    ): ResultadoDescarga =
        withContext(Dispatchers.IO) {
            if (!esquemaAdmitido(url)) return@withContext ResultadoDescarga.Fallo(ERROR_ESQUEMA)
            try {
                conectar(url, destino)
            } catch (_: SocketTimeoutException) {
                ResultadoDescarga.Fallo(ERROR_TIMEOUT)
            } catch (_: IOException) {
                ResultadoDescarga.Fallo(ERROR_SIN_CONEXION)
            }
        }

    private fun conectar(
        url: String,
        destino: File,
    ): ResultadoDescarga {
        val conexion =
            URL(url).openConnection() as? HttpURLConnection
                ?: return ResultadoDescarga.Fallo(ERROR_SIN_CONEXION)
        try {
            conexion.connectTimeout = timeoutConexionMs
            conexion.readTimeout = timeoutLecturaMs
            conexion.requestMethod = "GET"
            return when (conexion.responseCode) {
                in 200..299 -> copiar(conexion, destino)
                in 400..499 -> ResultadoDescarga.Fallo(ERROR_HTTP_4XX)
                in 500..599 -> ResultadoDescarga.Fallo(ERROR_HTTP_5XX)
                else -> ResultadoDescarga.Fallo(ERROR_SIN_CONEXION)
            }
        } finally {
            conexion.disconnect()
        }
    }

    private fun copiar(
        conexion: HttpURLConnection,
        destino: File,
    ): ResultadoDescarga {
        var escritos = 0L
        conexion.inputStream.use { entrada ->
            destino.outputStream().use { salida ->
                escritos = transferir(entrada, salida)
            }
        }
        return ResultadoDescarga.Ok(escritos)
    }

    private fun transferir(
        entrada: InputStream,
        salida: OutputStream,
    ): Long {
        val buffer = ByteArray(TAMANO_BUFFER)
        var escritos = 0L
        while (true) {
            val leidos = entrada.read(buffer)
            if (leidos < 0) break
            salida.write(buffer, 0, leidos)
            escritos += leidos
        }
        return escritos
    }

    private fun esquemaAdmitido(url: String): Boolean =
        url.startsWith("http://", ignoreCase = true) || url.startsWith("https://", ignoreCase = true)

    private companion object {
        const val TIMEOUT_CONEXION_MS = 15_000
        const val TIMEOUT_LECTURA_MS = 30_000
        const val TAMANO_BUFFER = 32 * 1024

        const val ERROR_SIN_CONEXION = "sin_conexion"
        const val ERROR_TIMEOUT = "timeout"
        const val ERROR_HTTP_4XX = "http_4xx"
        const val ERROR_HTTP_5XX = "http_5xx"
        const val ERROR_ESQUEMA = "esquema_no_admitido"
    }
}
