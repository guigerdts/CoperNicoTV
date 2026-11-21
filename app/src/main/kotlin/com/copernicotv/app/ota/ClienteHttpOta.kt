package com.copernicotv.app.ota

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.net.ssl.HttpsURLConnection
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLException

/** Motivo tipado de un fallo de red del cliente OTA. */
enum class FalloHttp {
    URL_INSEGURA,
    SIN_RED,
    TIMEOUT,
    TLS,
    ESTADO_HTTP,
    RESPUESTA_DEMASIADO_GRANDE,
    REDIRECCION_INVALIDA,
    ESCRITURA,
    IO,
}

/** Resultado de una descarga acotada (manifiestos, firma, lista de releases). */
sealed interface ResultadoHttp {
    data class Exito(val cuerpo: ByteArray, val urlFinal: String) : ResultadoHttp

    data class Fallo(val tipo: FalloHttp, val detalle: String? = null) : ResultadoHttp
}

/** Resultado de descargar el APK a un archivo. */
sealed interface ResultadoDescargaHttp {
    data class Completa(val bytesEscritos: Long, val bytesTotales: Long?) : ResultadoDescargaHttp

    data class Fallo(
        val tipo: FalloHttp,
        val detalle: String? = null,
        val reintentable: Boolean = false,
    ) : ResultadoDescargaHttp
}

/**
 * Cliente HTTP del sistema de actualizaciones.
 *
 * Vive en `:app` porque usa `HttpURLConnection` y TLS del sistema, pero no
 * depende de Android: se puede probar en JVM. `obtener` está acotado para
 * documentos pequeños; `descargar` escribe por bloques y soporta reanudación
 * con `Range`.
 */
interface ClienteHttpOta {
    suspend fun obtener(
        url: String,
        maxBytes: Int,
    ): ResultadoHttp

    suspend fun descargar(
        url: String,
        destino: File,
        desdeBytes: Long,
        alProgresar: (escritos: Long, totales: Long?) -> Unit,
    ): ResultadoDescargaHttp
}

/**
 * Implementación con `HttpURLConnection`.
 *
 * Cierra el acceso a cualquier host fuera de la lista de GitHub: el manifiesto
 * ya valida sus URLs, pero el cliente no confía en quien lo llame. Sigue las
 * redirecciones a mano para que ninguna caiga fuera de HTTPS ni de la lista.
 *
 * [contextoPruebas] existe solo para los tests JVM: permite un servidor TLS
 * local con certificado propio. En producción queda en `null` y se usa el
 * almacén de confianza del sistema.
 */
class ClienteHttpOtaUrlConnection(
    private val contextoPruebas: SSLContext? = null,
    private val hostsPermitidos: Set<String> = HOSTS_OTA,
    private val permitirPuertoDePruebas: Boolean = false,
) : ClienteHttpOta {
    override suspend fun obtener(
        url: String,
        maxBytes: Int,
    ): ResultadoHttp =
        withContext(Dispatchers.IO) {
            try {
                when (val conexion = conectar(url)) {
                    is Conexion.Fallida -> ResultadoHttp.Fallo(conexion.tipo, conexion.detalle)
                    is Conexion.Abierta -> {
                        val abierta = conexion.conexion
                        try {
                            val cuerpo = leerAcotado(abierta, maxBytes)
                            if (cuerpo == null) {
                                ResultadoHttp.Fallo(FalloHttp.RESPUESTA_DEMASIADO_GRANDE)
                            } else {
                                ResultadoHttp.Exito(cuerpo, abierta.url.toString())
                            }
                        } finally {
                            abierta.disconnect()
                        }
                    }
                }
            } catch (cancelacion: kotlinx.coroutines.CancellationException) {
                throw cancelacion
            } catch (error: Exception) {
                ResultadoHttp.Fallo(tipoDeError(error), error.message)
            }
        }

    override suspend fun descargar(
        url: String,
        destino: File,
        desdeBytes: Long,
        alProgresar: (escritos: Long, totales: Long?) -> Unit,
    ): ResultadoDescargaHttp =
        withContext(Dispatchers.IO) {
            try {
                val cabeceras = mutableMapOf<String, String>()
                if (desdeBytes > 0L) cabeceras["Range"] = "bytes=$desdeBytes-"
                when (val conexion = conectar(url, cabeceras)) {
                    is Conexion.Fallida ->
                        ResultadoDescargaHttp.Fallo(conexion.tipo, conexion.detalle, conexion.reintentable)
                    is Conexion.Abierta -> escribir(conexion, destino, desdeBytes, alProgresar)
                }
            } catch (cancelacion: kotlinx.coroutines.CancellationException) {
                throw cancelacion
            } catch (error: Exception) {
                val tipo = tipoDeError(error)
                ResultadoDescargaHttp.Fallo(tipo, error.message, tipo != FalloHttp.TLS)
            }
        }

    private suspend fun escribir(
        conexion: Conexion.Abierta,
        destino: File,
        desdeBytes: Long,
        alProgresar: (Long, Long?) -> Unit,
    ): ResultadoDescargaHttp {
        val http = conexion.conexion
        try {
            val reanuda = conexion.codigo == HttpURLConnection.HTTP_PARTIAL
            if (!reanuda && desdeBytes > 0L && destino.exists()) destino.delete()
            val total = totalEsperado(http)
            destino.parentFile?.mkdirs()
            val salida = FileOutputStream(destino, reanuda && destino.exists())
            try {
                var escritos = if (reanuda) desdeBytes else 0L
                http.inputStream.use { entrada ->
                    copiar(entrada, salida, escritos, total, alProgresar) { nuevos ->
                        escritos = nuevos
                    }
                }
                salida.flush()
                salida.fd.sync()
                return ResultadoDescargaHttp.Completa(escritos, total)
            } catch (cancelacion: kotlinx.coroutines.CancellationException) {
                throw cancelacion
            } catch (error: IOException) {
                val tipo = tipoDeError(error)
                return ResultadoDescargaHttp.Fallo(tipo, error.message, tipo != FalloHttp.TLS)
            } finally {
                salida.close()
            }
        } finally {
            http.disconnect()
        }
    }

    private suspend fun copiar(
        entrada: InputStream,
        salida: FileOutputStream,
        inicial: Long,
        total: Long?,
        alProgresar: (Long, Long?) -> Unit,
        actualizar: (Long) -> Unit,
    ) {
        var escritos = inicial
        alProgresar(escritos, total)
        val buffer = ByteArray(TAMANO_BLOQUE)
        var leidos = entrada.read(buffer)
        while (leidos >= 0) {
            currentCoroutineContext().ensureActive()
            if (leidos > 0) {
                salida.write(buffer, 0, leidos)
                escritos += leidos
                actualizar(escritos)
                alProgresar(escritos, total)
            }
            leidos = entrada.read(buffer)
        }
    }

    private fun totalEsperado(conexion: HttpURLConnection): Long? {
        conexion.getHeaderField("Content-Range")?.substringAfter('/', "")?.toLongOrNull()?.let { return it }
        val largo = conexion.contentLengthLong
        return if (largo > 0L) largo else null
    }

    private fun leerAcotado(
        conexion: HttpURLConnection,
        maxBytes: Int,
    ): ByteArray? = conexion.inputStream.use { entrada -> leerHasta(entrada, maxBytes) }

    /** Acumula el stream completo y devuelve `null` si supera [maxBytes]. */
    private fun leerHasta(
        entrada: InputStream,
        maxBytes: Int,
    ): ByteArray? {
        val salida = ByteArrayOutputStream()
        val buffer = ByteArray(TAMANO_BLOQUE)
        var restante = maxBytes
        var leidos = entrada.read(buffer)
        while (leidos >= 0) {
            if (leidos > restante) return null
            if (leidos > 0) {
                salida.write(buffer, 0, leidos)
                restante -= leidos
            }
            leidos = entrada.read(buffer)
        }
        return salida.toByteArray()
    }

    private fun conectar(
        urlTexto: String,
        cabeceras: Map<String, String> = emptyMap(),
    ): Conexion {
        var url = urlDe(urlTexto) ?: return Conexion.Fallida(FalloHttp.URL_INSEGURA, urlTexto)
        repeat(MAX_REDIRECCIONES + 1) {
            val conexion = abrir(url, cabeceras)
            val codigo = conexion.responseCode
            when {
                codigo in 200..299 -> return Conexion.Abierta(conexion, codigo)
                codigo in 300..399 -> {
                    val ubicacion = conexion.getHeaderField("Location")
                    conexion.disconnect()
                    if (ubicacion == null) {
                        return Conexion.Fallida(FalloHttp.REDIRECCION_INVALIDA, "sin Location")
                    }
                    url =
                        resolverUbicacion(url, ubicacion)
                            ?: return Conexion.Fallida(FalloHttp.REDIRECCION_INVALIDA, ubicacion)
                }
                else -> {
                    conexion.disconnect()
                    return Conexion.Fallida(FalloHttp.ESTADO_HTTP, "HTTP $codigo", codigo >= 500)
                }
            }
        }
        return Conexion.Fallida(FalloHttp.REDIRECCION_INVALIDA, "demasiadas redirecciones")
    }

    private fun abrir(
        url: URL,
        cabeceras: Map<String, String>,
    ): HttpURLConnection {
        val conexion = url.openConnection() as HttpURLConnection
        conexion.instanceFollowRedirects = false
        conexion.connectTimeout = TIEMPO_CONEXION_MS
        conexion.readTimeout = TIEMPO_LECTURA_MS
        conexion.setRequestProperty("Accept-Encoding", "identity")
        conexion.setRequestProperty("User-Agent", AGENTE)
        cabeceras.forEach { (nombre, valor) -> conexion.setRequestProperty(nombre, valor) }
        if (conexion is HttpsURLConnection && contextoPruebas != null) {
            conexion.sslSocketFactory = contextoPruebas.socketFactory
            conexion.hostnameVerifier = { _, _ -> true }
        }
        return conexion
    }

    private fun resolverUbicacion(
        base: URL,
        ubicacion: String,
    ): URL? =
        try {
            val destino = URI(base.toURI().resolve(ubicacion)).toURL()
            if (esPermitida(destino)) destino else null
        } catch (_: Exception) {
            null
        }

    private fun urlDe(texto: String): URL? {
        if (texto.length > MAX_URL) return null
        return try {
            val url = URI(texto).toURL()
            if (esPermitida(url)) url else null
        } catch (_: Exception) {
            null
        }
    }

    private fun esPermitida(url: URL): Boolean {
        if (!url.protocol.equals("https", ignoreCase = true)) return false
        if (!url.userInfo.isNullOrEmpty()) return false
        val puerto = url.port
        if (!permitirPuertoDePruebas && puerto != -1 && puerto != 443) return false
        val host = url.host?.lowercase() ?: return false
        return host in hostsPermitidos
    }

    private fun tipoDeError(error: Exception): FalloHttp =
        when (error) {
            is SocketTimeoutException -> FalloHttp.TIMEOUT
            is UnknownHostException, is ConnectException -> FalloHttp.SIN_RED
            is SSLException -> FalloHttp.TLS
            is IOException -> FalloHttp.IO
            else -> FalloHttp.IO
        }

    private sealed interface Conexion {
        class Abierta(val conexion: HttpURLConnection, val codigo: Int) : Conexion

        class Fallida(
            val tipo: FalloHttp,
            val detalle: String? = null,
            val reintentable: Boolean = false,
        ) : Conexion
    }

    private companion object {
        const val TIEMPO_CONEXION_MS = 15_000
        const val TIEMPO_LECTURA_MS = 30_000
        const val MAX_REDIRECCIONES = 3
        const val MAX_URL = 2048
        const val TAMANO_BLOQUE = 64 * 1024
        const val AGENTE = "CoperNicoTV-OTA"
        val HOSTS_OTA =
            setOf(
                "github.com",
                "api.github.com",
                "objects.githubusercontent.com",
                "release-assets.githubusercontent.com",
                "codeload.github.com",
            )
    }
}
