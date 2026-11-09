package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import com.copernicotv.core.player.ClasificadorStream
import com.copernicotv.core.player.ProtocoloStream
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Datos mínimos para abrir la sesión de Media3 de un Canal (E6-T1).
 *
 * [url] es la URL descifrada y canónica. Vive solo en memoria: no se persiste,
 * no se registra en logs y la UI no la muestra (S57a).
 */
data class StreamResuelto(
    val canalId: Long,
    val nombre: String?,
    val url: String,
    val protocolo: ProtocoloStream,
) {
    /**
     * La URL descifrada nunca debe aparecer en un log accidental. Solo se
     * exponen identificadores y el protocolo (S57a).
     */
    override fun toString(): String = "StreamResuelto(canalId=$canalId, nombre=$nombre, protocolo=$protocolo)"
}

/** Resultado tipado de resolver la URL reproducible de un Canal. */
sealed interface ResultadoStream {
    data class Ok(val stream: StreamResuelto) : ResultadoStream

    /**
     * Error sin datos sensibles. [codigo] es un identificador estable; nunca
     * contiene la URL, su host ni credenciales (S57a).
     */
    data class Error(val codigo: String) : ResultadoStream
}

/**
 * Resuelve la URL reproducible de un Canal para la sesión de Media3 (E6-T1).
 *
 * Reutiliza la canonicalización de `Canal.url_stream` y el sobre AES de
 * `CatalogoRepositorio`: no crea una segunda ruta de acceso a la columna
 * cifrada. RTMP se devuelve como `stream_rtmp_no_soportado` porque su soporte
 * sigue condicionado a validación técnica y no se declara compatible por la
 * existencia del módulo (§5, S16).
 */
@Singleton
class ReproductorRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
    ) {
        suspend fun resolver(canalId: Long): ResultadoStream =
            withContext(Dispatchers.IO) {
                val canal =
                    db.canalDao().porId(canalId)
                        ?: return@withContext ResultadoStream.Error(ERROR_CANAL_NO_EXISTE)
                val url =
                    when (
                        val descifrado =
                            CifradoAes.descifrar(claves.claveAes, AAD_URL_STREAM, canal.urlStreamCifrada)
                    ) {
                        is ResultadoDescifrado.Ok -> String(descifrado.bytes, Charsets.UTF_8)
                        is ResultadoDescifrado.Fallo -> return@withContext ResultadoStream.Error(ERROR_URL_ILEGIBLE)
                    }
                val canonica =
                    when (val resultado = CanonicalizadorUrl.canonizar(url, CampoUrl.CANAL_URL_STREAM)) {
                        is ResultadoCanonizacion.Ok -> resultado
                        is ResultadoCanonizacion.Rechazada ->
                            return@withContext ResultadoStream.Error(resultado.error.codigo.codigo)
                    }
                val protocolo = ClasificadorStream.clasificar(canonica.urlCanonica, canonica.clase)
                if (protocolo == ProtocoloStream.RTMP) {
                    return@withContext ResultadoStream.Error(ERROR_RTMP_NO_SOPORTADO)
                }
                ResultadoStream.Ok(
                    StreamResuelto(
                        canalId = canal.id,
                        nombre = canal.nombre,
                        url = canonica.urlCanonica,
                        protocolo = protocolo,
                    ),
                )
            }

        private companion object {
            val AAD_URL_STREAM: String = CifradoAes.aad("Canal", "url_stream_cifrada")
            const val ERROR_CANAL_NO_EXISTE = "canal_no_existe"
            const val ERROR_URL_ILEGIBLE = "url_ilegible"
            const val ERROR_RTMP_NO_SOPORTADO = "stream_rtmp_no_soportado"
        }
    }
