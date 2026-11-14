package com.copernicotv.app.data

import com.copernicotv.core.m3u.ImportadorM3U
import com.copernicotv.core.m3u.LimitesParserM3U
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException

/** Códigos de error de la descarga HTTP (compartidos con el diagnóstico). */
internal const val ERROR_SIN_CONEXION = "sin_conexion"
internal const val ERROR_TIMEOUT = "timeout"
internal const val ERROR_HTTP_4XX = "http_4xx"
internal const val ERROR_DNS = "dns_no_resuelto"
internal const val ERROR_CONEXION_RECHAZADA = "conexion_rechazada"
internal const val ERROR_BLOQUEO_HTTP = "bloqueo_http"
internal const val ERROR_HTTP_5XX = "http_5xx"

/**
 * Descarga HTTP de una Lista M3U, extraída de `CatalogoRepositorio` para
 * mantenerlo bajo el límite de detekt (ronda 9). Misma conducta: fases
 * CONECTANDO/DESCARGANDO, timeouts de [TiemposHttp], bloqueo de cleartext y
 * parseo en streaming del cuerpo.
 */
internal class DescargaListaHttp(
    private val tiempos: TiemposHttp = TiemposHttp(),
    private val verificaCleartext: VerificacionCleartext = VerificacionCleartext(::politicaCleartextPermitida),
) {
    fun descargar(
        url: String,
        alFase: (FaseImportacion) -> Unit,
        avance: AvanceImportacion,
        activo: () -> Unit,
    ): Descarga {
        if (url.startsWith("http://", ignoreCase = true) && !verificaCleartext.permitido(URL(url).host)) {
            return Descarga.Fallo(ERROR_BLOQUEO_HTTP, DiagnosticoImportacion(motivo = ERROR_BLOQUEO_HTTP))
        }
        val inicioDescarga = System.currentTimeMillis()
        alFase(FaseImportacion.CONECTANDO)
        val conexion =
            URL(url).openConnection() as? HttpURLConnection
                ?: return Descarga.Fallo(ERROR_SIN_CONEXION, DiagnosticoImportacion(motivo = ERROR_SIN_CONEXION))
        conexion.connectTimeout = tiempos.conexionMs
        conexion.readTimeout = tiempos.lecturaMs
        conexion.requestMethod = "GET"
        return try {
            when (val codigo = conexion.responseCode) {
                in 200..299 -> {
                    alFase(FaseImportacion.DESCARGANDO)
                    val tipoContenido = conexion.contentType
                    conexion.inputStream.use { entrada ->
                        val contada = InputStreamContado(entrada)
                        avance.bytesProveedor = { contada.contados }
                        val lectura =
                            ImportadorM3U.leerDeclaraciones(
                                contada,
                                LimitesParserM3U(),
                            ) { total ->
                                activo()
                                avance.ticEntrada(total)
                            }
                        Descarga.Ok(
                            lectura,
                            DiagnosticoImportacion(
                                codigoHttp = codigo,
                                tipoContenido = tipoContenido,
                                bytesDescargados = contada.contados,
                                tiempoDescargaMs = System.currentTimeMillis() - inicioDescarga,
                            ),
                        )
                    }
                }
                in 400..499 ->
                    Descarga.Fallo(
                        ERROR_HTTP_4XX,
                        DiagnosticoImportacion(codigoHttp = codigo, motivo = ERROR_HTTP_4XX),
                    )
                in 500..599 ->
                    Descarga.Fallo(
                        ERROR_HTTP_5XX,
                        DiagnosticoImportacion(codigoHttp = codigo, motivo = ERROR_HTTP_5XX),
                    )
                else ->
                    Descarga.Fallo(
                        ERROR_SIN_CONEXION,
                        DiagnosticoImportacion(motivo = ERROR_SIN_CONEXION),
                    )
            }
        } catch (error: SocketTimeoutException) {
            Descarga.Fallo(
                ERROR_TIMEOUT,
                DiagnosticoImportacion(
                    tiempoDescargaMs = System.currentTimeMillis() - inicioDescarga,
                    motivo = ERROR_TIMEOUT,
                ),
            )
        } catch (error: UnknownHostException) {
            Descarga.Fallo(ERROR_DNS, DiagnosticoImportacion(motivo = ERROR_DNS))
        } catch (error: ConnectException) {
            Descarga.Fallo(
                ERROR_CONEXION_RECHAZADA,
                DiagnosticoImportacion(motivo = ERROR_CONEXION_RECHAZADA),
            )
        } catch (error: IOException) {
            Descarga.Fallo(ERROR_SIN_CONEXION, DiagnosticoImportacion(motivo = ERROR_SIN_CONEXION))
        } finally {
            conexion.disconnect()
        }
    }
}
