package com.copernicotv.app.data

import com.copernicotv.core.conexion.ConexionXtream
import com.copernicotv.core.conexion.EstadoConexion
import com.copernicotv.core.conexion.TipoConexion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URI
import java.net.URL
import java.net.UnknownHostException
import javax.inject.Inject
import javax.inject.Singleton

/** Códigos estables de la prueba de conexión; nunca incluyen datos del servidor. */
object CodigosSonda {
    const val CORRECTA: String = "conexion_correcta"
    const val CREDENCIALES_INVALIDAS: String = "conexion_credenciales_invalidas"
    const val NO_AUTORIZADO: String = "conexion_no_autorizado"
    const val NO_ENCONTRADO: String = "conexion_no_encontrado"
    const val LIMITE_PETICIONES: String = "conexion_limite_peticiones"
    const val ERROR_CLIENTE: String = "conexion_error_cliente"
    const val ERROR_SERVIDOR: String = "conexion_error_servidor"
    const val REDIRECCION_INVALIDA: String = "conexion_redireccion_invalida"
    const val DEMASIADAS_REDIRECCIONES: String = "conexion_demasiadas_redirecciones"
    const val RESPUESTA_INESPERADA: String = "conexion_respuesta_inesperada"
    const val TIMEOUT: String = "conexion_timeout"
    const val DNS: String = "conexion_dns"
    const val RECHAZADA: String = "conexion_rechazada"
    const val SIN_RED: String = "conexion_sin_red"
    const val BLOQUEO_HTTP: String = "conexion_bloqueo_http"
}

/** Resultado de probar una conexión, listo para persistir en `Conexion.estado`. */
data class ResultadoSonda(
    val estado: EstadoConexion,
    val codigo: String,
    val codigoHttp: Int? = null,
)

/**
 * Prueba de una conexión sin importar el catálogo (S4).
 *
 * Hace una petición real, con timeouts y sin seguir redirecciones de forma
 * automática: una redirección a otro host se valida antes de seguirla, porque
 * `HttpURLConnection` aceptaría cualquier destino. Del cuerpo solo lee una
 * muestra acotada: alcanza para reconocer la cabecera `#EXTM3U` de una lista o
 * el `"auth":0` de `player_api.php` sin descargar el catálogo completo.
 */
@Singleton
class SondaConexion
    @Inject
    constructor(
        private val tiempos: TiemposHttp = TiemposHttp(),
        private val verificaCleartext: VerificacionCleartext = VerificacionCleartext(::politicaCleartextPermitida),
    ) {
        suspend fun probar(
            url: String,
            tipo: TipoConexion,
        ): ResultadoSonda =
            withContext(Dispatchers.IO) {
                var destino = url
                repeat(MAX_REDIRECCIONES + 1) { salto ->
                    when (val paso = solicitar(destino, tipo)) {
                        is Paso.Seguir -> {
                            if (salto == MAX_REDIRECCIONES) {
                                return@withContext fallo(CodigosSonda.DEMASIADAS_REDIRECCIONES)
                            }
                            destino = paso.destino
                        }
                        is Paso.Final -> return@withContext paso.resultado
                    }
                }
                fallo(CodigosSonda.DEMASIADAS_REDIRECCIONES)
            }

        private fun solicitar(
            url: String,
            tipo: TipoConexion,
        ): Paso {
            if (bloqueadaPorCleartext(url)) return Paso.Final(fallo(CodigosSonda.BLOQUEO_HTTP))
            val conexion = abrir(url) ?: return Paso.Final(fallo(CodigosSonda.SIN_RED))
            return try {
                clasificar(conexion, url, tipo)
            } catch (_: SocketTimeoutException) {
                Paso.Final(fallo(CodigosSonda.TIMEOUT))
            } catch (_: UnknownHostException) {
                Paso.Final(fallo(CodigosSonda.DNS))
            } catch (_: ConnectException) {
                Paso.Final(fallo(CodigosSonda.RECHAZADA))
            } catch (_: IOException) {
                Paso.Final(fallo(CodigosSonda.SIN_RED))
            } finally {
                conexion.disconnect()
            }
        }

        private fun bloqueadaPorCleartext(url: String): Boolean {
            if (!url.startsWith("http://", ignoreCase = true)) return false
            val host =
                try {
                    URL(url).host
                } catch (_: IOException) {
                    return false
                }
            return !verificaCleartext.permitido(host)
        }

        private fun abrir(url: String): HttpURLConnection? {
            val analizada =
                try {
                    URL(url)
                } catch (_: IOException) {
                    return null
                }
            val conexion = analizada.openConnection() as? HttpURLConnection ?: return null
            conexion.connectTimeout = tiempos.conexionMs
            conexion.readTimeout = tiempos.lecturaMs
            conexion.instanceFollowRedirects = false
            conexion.requestMethod = "GET"
            return conexion
        }

        private fun clasificar(
            conexion: HttpURLConnection,
            url: String,
            tipo: TipoConexion,
        ): Paso {
            val codigo = conexion.responseCode
            return when {
                codigo in 200..299 -> Paso.Final(analizar(conexion, tipo, codigo))
                codigo in 300..399 -> redireccion(conexion, url, codigo)
                codigo == 401 || codigo == 403 -> Paso.Final(fallo(CodigosSonda.NO_AUTORIZADO, codigo))
                codigo == 404 -> Paso.Final(fallo(CodigosSonda.NO_ENCONTRADO, codigo))
                codigo == 429 -> Paso.Final(fallo(CodigosSonda.LIMITE_PETICIONES, codigo))
                codigo in 400..499 -> Paso.Final(fallo(CodigosSonda.ERROR_CLIENTE, codigo))
                else -> Paso.Final(fallo(CodigosSonda.ERROR_SERVIDOR, codigo))
            }
        }

        private fun redireccion(
            conexion: HttpURLConnection,
            url: String,
            codigo: Int,
        ): Paso {
            val ubicacion = conexion.getHeaderField("Location") ?: return Paso.Final(fallo(CodigosSonda.REDIRECCION_INVALIDA, codigo))
            val resuelta =
                try {
                    URI(url).resolve(ubicacion).toString()
                } catch (_: IllegalArgumentException) {
                    return Paso.Final(fallo(CodigosSonda.REDIRECCION_INVALIDA, codigo))
                }
            val validada =
                ConexionXtream.validarDestinoHttp(resuelta)
                    ?: return Paso.Final(fallo(CodigosSonda.REDIRECCION_INVALIDA, codigo))
            return Paso.Seguir(validada)
        }

        private fun analizar(
            conexion: HttpURLConnection,
            tipo: TipoConexion,
            codigo: Int,
        ): ResultadoSonda {
            val muestra = leerMuestra(conexion)
            if (tipo == TipoConexion.XTREAM) {
                if (Regex("\"auth\"\\s*:\\s*0(?![0-9])").containsMatchIn(muestra)) {
                    return fallo(CodigosSonda.CREDENCIALES_INVALIDAS, codigo)
                }
                return if (muestra.contains("\"user_info\"")) {
                    ResultadoSonda(EstadoConexion.CORRECTA, CodigosSonda.CORRECTA, codigo)
                } else {
                    fallo(CodigosSonda.RESPUESTA_INESPERADA, codigo)
                }
            }
            val cabecera = muestra.trimStart('\uFEFF', ' ', '\t', '\r', '\n')
            return if (cabecera.startsWith("#EXTM3U")) {
                ResultadoSonda(EstadoConexion.CORRECTA, CodigosSonda.CORRECTA, codigo)
            } else {
                fallo(CodigosSonda.RESPUESTA_INESPERADA, codigo)
            }
        }

        private fun leerMuestra(conexion: HttpURLConnection): String =
            try {
                conexion.inputStream.use { entrada ->
                    val buffer = ByteArray(MAX_MUESTRA)
                    val leidos = entrada.read(buffer)
                    if (leidos <= 0) "" else String(buffer, 0, leidos, Charsets.UTF_8)
                }
            } catch (_: IOException) {
                ""
            }

        private fun fallo(
            codigo: String,
            codigoHttp: Int? = null,
        ): ResultadoSonda = ResultadoSonda(EstadoConexion.ERROR, codigo, codigoHttp)

        /** Paso del seguimiento de redirecciones. */
        private sealed interface Paso {
            data class Seguir(val destino: String) : Paso

            data class Final(val resultado: ResultadoSonda) : Paso
        }

        private companion object {
            const val MAX_REDIRECCIONES = 3
            const val MAX_MUESTRA = 8_192
        }
    }
