package com.copernicotv.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import androidx.room.withTransaction
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ClaseUrl
import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.ResultadoCanonizacion
import com.copernicotv.core.m3u.CanalImportadoM3U
import com.copernicotv.core.m3u.CodecAtributosExtra
import com.copernicotv.core.m3u.ImportadorM3U
import com.copernicotv.core.m3u.ResultadoImportacionM3U
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Resumen de una importación. */
data class ResumenImportacion(
    val listaId: Long?,
    val canales: Int,
    val rechazadas: Int,
    val error: String? = null,
    /** Códigos de diagnóstico del parser, agregados y sin datos de la lista. */
    val diagnosticos: List<String> = emptyList(),
)

/**
 * Rechazo del escritor durante una importación; revierte la transacción completa
 * y se informa como [ResumenImportacion.error] con su código contractual.
 */
open class ImportacionRechazada(val codigo: String) : Exception(codigo)

/**
 * Persistencia del catálogo importado (E2-T2/T3/T4).
 *
 * La importación es transaccional: o queda la Fuente, la Lista, los Canales,
 * los Grupos, las Apariencias y el Estado de Actualización completos, o no
 * queda nada. El catálogo previo nunca se modifica antes de que la descarga y
 * el parseo terminen: un timeout, un 4xx/5xx o una lista inválida no lo tocan.
 *
 * La reconciliación de actualizaciones pertenece a E4.
 */
@Singleton
class CatalogoRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
        @ApplicationContext private val contexto: Context,
        private val tiempos: TiemposHttp = TiemposHttp(),
    ) {
        private val io = Dispatchers.IO
        private val escritorApariencias = AparienciaEscritor(db)
        private val escritorCanales = CanalEscritor(db)

        fun canalesDeLista(listaId: Long): Flow<List<String?>> = db.aparienciaDao().nombresDeLista(listaId)

        /**
         * Catálogo de la Lista activa más reciente.
         *
         * La Lista se resuelve en SQL y no a partir de un identificador en
         * memoria: un proceso nuevo no recuerda ninguna importación previa y
         * aun así debe mostrar el catálogo ya persistido.
         */
        fun canalesDeListaActiva(): Flow<List<String?>> = db.aparienciaDao().nombresDeListaActiva()

        /** Importa el fixture sintético incluido en assets. */
        suspend fun importarFixture(): ResumenImportacion {
            val texto =
                withContext(io) {
                    contexto.assets.open(FIXTURE).bufferedReader(Charsets.UTF_8).use { it.readText() }
                }
            return importar(texto, NOMBRE_FUENTE, UBICACION_FIXTURE)
        }

        /**
         * Importa un texto M3U en una Fuente y una Lista nuevas.
         *
         * [ubicacion] es la ubicación original exacta de la lista; se cifra y
         * se indexa por HMAC sobre su forma canónica, nunca se guarda en claro.
         *
         * Si la Fuente ya existe con su Lista, la importación no escribe
         * catálogo y devuelve la Lista persistida: repetir el disparador no
         * debe duplicar ni borrar el catálogo, y menos aún fallar por una
         * clave única.
         */
        suspend fun importar(
            texto: String,
            nombreFuente: String,
            ubicacion: String,
        ): ResumenImportacion =
            withContext(io) {
                val canonica = canonicalizar(ubicacion)
                if (canonica == null) {
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_UBICACION_INVALIDA)
                }
                val importado = ImportadorM3U.importar(texto, claves.claveHmac)
                persistir(nombreFuente, ubicacion, canonica, importado)
            }

        /**
         * Da de alta una Fuente y su Lista desde una URL HTTP/HTTPS.
         *
         * La descarga y el parseo ocurren antes de abrir la transacción: un
         * timeout, un 4xx/5xx o una respuesta vacía devuelven un error tipado
         * sin tocar el catálogo ya persistido.
         */
        suspend fun importarDesdeUrl(
            nombreFuente: String,
            url: String,
        ): ResumenImportacion =
            withContext(io) {
                val canonica = canonicalizar(url)
                if (canonica == null) {
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_UBICACION_INVALIDA)
                }
                if (canonica.clase != ClaseUrl.HTTP_HTTPS) {
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_ESQUEMA_NO_ADMITIDO)
                }
                when (val descarga = descargar(canonica.urlCanonica)) {
                    is Descarga.Ok -> persistir(nombreFuente, url, canonica, descarga.importado)
                    is Descarga.Fallo -> {
                        registrarFallo(nombreFuente, descarga.codigo)
                        ResumenImportacion(null, 0, 0, descarga.codigo)
                    }
                }
            }

        /**
         * Da de alta una Fuente y su Lista desde un documento SAF.
         *
         * Exige un `content://` con permiso de lectura persistible: el permiso
         * se toma antes de leer para que la Lista siga siendo accesible tras
         * reiniciar el proceso. Un permiso no persistible es un error tipado.
         */
        suspend fun importarDesdeSaf(
            nombreFuente: String,
            uriTexto: String,
        ): ResumenImportacion =
            withContext(io) {
                val uri = Uri.parse(uriTexto)
                val canonica = canonicalizar(uriTexto)
                if (canonica == null) {
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_UBICACION_INVALIDA)
                }
                if (canonica.clase != ClaseUrl.SAF || uri.scheme != "content") {
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_ESQUEMA_NO_ADMITIDO)
                }
                val permisoTomado =
                    try {
                        contexto.contentResolver.takePersistableUriPermission(
                            uri,
                            Intent.FLAG_GRANT_READ_URI_PERMISSION,
                        )
                        // Tomar el permiso sin excepción no basta: se comprueba
                        // que el grant esté realmente en la tabla persistida del
                        // sistema, que es lo que sobrevive al reinicio.
                        contexto.contentResolver.persistedUriPermissions.any {
                            it.uri == uri && it.isReadPermission
                        }
                    } catch (error: SecurityException) {
                        false
                    }
                if (!permisoTomado) {
                    registrarFallo(nombreFuente, ERROR_PERMISO_SAF)
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_PERMISO_SAF)
                }
                val importado =
                    try {
                        contexto.contentResolver.openInputStream(uri)?.use { entrada ->
                            ImportadorM3U.importar(entrada, claves.claveHmac)
                        }
                    } catch (error: SecurityException) {
                        null
                    } catch (error: IOException) {
                        null
                    }
                if (importado == null) {
                    registrarFallo(nombreFuente, ERROR_ARCHIVO_INACCESIBLE)
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_ARCHIVO_INACCESIBLE)
                }
                persistir(nombreFuente, uriTexto, canonica, importado)
            }

        /**
         * Nombre de Fuente para un documento SAF.
         *
         * Usa el nombre visible que expone el proveedor de documentos, que es
         * el que el usuario reconoce, y cae al último segmento de la URI o a un
         * valor estable. Se resuelve fuera del hilo principal y nunca registra
         * la URI: puede contener rutas del dispositivo.
         */
        suspend fun nombreDeDocumento(uriTexto: String): String =
            withContext(io) {
                val uri = Uri.parse(uriTexto)
                val visible =
                    runCatching {
                        contexto.contentResolver
                            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                            ?.use { cursor ->
                                val indice = cursor.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                                if (indice >= 0 && cursor.moveToFirst()) cursor.getString(indice) else null
                            }
                    }.getOrNull()
                nombreDeDocumento(visible, uriTexto)
            }

        private suspend fun persistir(
            nombreFuente: String,
            ubicacion: String,
            canonica: ResultadoCanonizacion.Ok,
            importado: ResultadoImportacionM3U,
        ): ResumenImportacion {
            val diagnosticos = importado.diagnosticos.map { it.codigo.codigo }
            if (importado.canales.isEmpty()) {
                val error = importado.errores.firstOrNull()?.codigo?.codigo ?: ERROR_SIN_CANALES
                registrarFallo(nombreFuente, error)
                return ResumenImportacion(
                    null,
                    0,
                    importado.rechazadas.size,
                    error,
                    diagnosticos,
                )
            }
            val persistida =
                try {
                    db.withTransaction {
                        val existente = listaExistente(nombreFuente)
                        if (existente != null) {
                            registrarIntento(existente.id, RESULTADO_SIN_CAMBIOS)
                            existente
                        } else {
                            escribirCatalogo(nombreFuente, ubicacion, canonica, importado.canales)
                        }
                    }
                } catch (rechazo: ImportacionRechazada) {
                    registrarFallo(nombreFuente, rechazo.codigo)
                    return ResumenImportacion(
                        null,
                        0,
                        importado.rechazadas.size,
                        rechazo.codigo,
                        diagnosticos,
                    )
                }
            return ResumenImportacion(
                persistida.id,
                persistida.canales,
                importado.rechazadas.size,
                null,
                diagnosticos,
            )
        }

        private fun canonicalizar(ubicacion: String): ResultadoCanonizacion.Ok? =
            CanonicalizadorUrl.canonizar(ubicacion, CampoUrl.LISTA_UBICACION) as? ResultadoCanonizacion.Ok

        private fun descargar(url: String): Descarga {
            val conexion = URL(url).openConnection() as? HttpURLConnection ?: return Descarga.Fallo(ERROR_SIN_CONEXION)
            conexion.connectTimeout = tiempos.conexionMs
            conexion.readTimeout = tiempos.lecturaMs
            conexion.requestMethod = "GET"
            return try {
                when (conexion.responseCode) {
                    in 200..299 -> {
                        val importado =
                            conexion.inputStream.use { entrada ->
                                ImportadorM3U.importar(entrada, claves.claveHmac)
                            }
                        Descarga.Ok(importado)
                    }
                    in 400..499 -> Descarga.Fallo(ERROR_HTTP_4XX)
                    in 500..599 -> Descarga.Fallo(ERROR_HTTP_5XX)
                    else -> Descarga.Fallo(ERROR_SIN_CONEXION)
                }
            } catch (error: SocketTimeoutException) {
                Descarga.Fallo(ERROR_TIMEOUT)
            } catch (error: IOException) {
                Descarga.Fallo(ERROR_SIN_CONEXION)
            } finally {
                conexion.disconnect()
            }
        }

        /** Lista ya persistida para [nombreFuente], si la Fuente existe con una Lista. */
        private suspend fun listaExistente(nombreFuente: String): ListaPersistida? {
            val fuente = db.fuenteDao().porNombre(nombreFuente) ?: return null
            val lista = db.listaDao().porFuente(fuente.id).firstOrNull() ?: return null
            return ListaPersistida(lista.id, db.canalDao().contarPorFuente(fuente.id))
        }

        /**
         * Registra un intento completo de actualización dentro de la
         * transacción vigente.
         */
        private suspend fun registrarIntento(
            listaId: Long,
            resultado: String,
        ) {
            val ahora = Instant.now()
            val id =
                db.estadoActualizacionDao().insertar(
                    EstadoActualizacionEntity(
                        listaId = listaId,
                        causa = CAUSA_IMPORTACION,
                        versionImport = VERSION_IMPORTACION,
                        inicio = ahora,
                    ),
                )
            db.estadoActualizacionDao().finalizar(id, Instant.now(), resultado)
        }

        /**
         * Registra el fallo de un intento de importación sobre una Lista ya
         * existente. Un alta nueva sin Fuente persistida no tiene a qué
         * asociar el intento y no escribe nada.
         */
        private suspend fun registrarFallo(
            nombreFuente: String,
            codigo: String,
        ) {
            val fuente = db.fuenteDao().porNombre(nombreFuente) ?: return
            val lista = db.listaDao().porFuente(fuente.id).firstOrNull() ?: return
            db.withTransaction {
                registrarIntento(lista.id, codigo)
            }
        }

        private suspend fun escribirCatalogo(
            nombreFuente: String,
            ubicacion: String,
            canonica: ResultadoCanonizacion.Ok,
            canales: List<CanalImportadoM3U>,
        ): ListaPersistida {
            val ahora = Instant.now()
            val fuenteId =
                db.fuenteDao().porNombre(nombreFuente)?.id
                    ?: db.fuenteDao().insertar(
                        FuenteEntity(
                            nombre = nombreFuente,
                            estado = "activa",
                            usarIntervaloGlobal = true,
                            creadoEn = ahora,
                            actualizadoEn = ahora,
                        ),
                    )
            val idLista =
                db.listaDao().insertar(
                    ListaEntity(
                        fuenteId = fuenteId,
                        nombre = nombreFuente,
                        ubicacionCifrada =
                            CifradoAes.cifrar(
                                claves.claveAes,
                                CifradoAes.aad("Lista", "ubicacion_cifrada"),
                                ubicacion.toByteArray(Charsets.UTF_8),
                            ),
                        ubicacionHmac =
                            HmacUrl
                                .calcularSobre(
                                    clave = claves.claveHmac,
                                    generacion = ImportadorM3U.GENERACION_HMAC,
                                    campo = CampoUrl.LISTA_UBICACION,
                                    clase = canonica.clase,
                                    urlCanonica = canonica.urlCanonica,
                                ).toByteArray(Charsets.UTF_8),
                        esquema = canonica.urlCanonica.substringBefore(':'),
                        orden = 0,
                        estado = "activa",
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
            val idEstado =
                db.estadoActualizacionDao().insertar(
                    EstadoActualizacionEntity(
                        fuenteId = fuenteId,
                        listaId = idLista,
                        causa = CAUSA_IMPORTACION,
                        versionImport = VERSION_IMPORTACION,
                        inicio = ahora,
                    ),
                )
            var orden = 0
            for (canal in canales) {
                val tvg = canal.tvgId
                // La forma persistida de la clave de unicidad es la única fuente de
                // verdad sobre si el tvg-id habilitó la clave (§6.3.2).
                val tvgUnico = tvg != null && canal.claveUnicidad == ClaveCanal.PREFIJO_TVG + tvg
                val canalId =
                    escritorCanales.insertar(
                        CanalEntity(
                            fuenteId = fuenteId,
                            nombre = canal.nombre,
                            urlStreamCifrada =
                                CifradoAes.cifrar(
                                    claves.claveAes,
                                    CifradoAes.aad("Canal", "url_stream_cifrada"),
                                    canal.urlOriginal.toByteArray(Charsets.UTF_8),
                                ),
                            urlStreamHmac = canal.urlStreamHmac.toByteArray(Charsets.UTF_8),
                            logoUrlCifrada = cifrarLogo(canal.logoOriginal),
                            logoUrlHmac = canal.logoHmac?.toByteArray(Charsets.UTF_8),
                            tvgId = canal.tvgId,
                            claveCanonica = canal.claveCanonica,
                            claveUnicidad = canal.claveUnicidad,
                            claveDerivable = canal.claveDerivable,
                            atributosExtra = CodecAtributosExtra.codificar(canal.atributosExtra),
                            creadoEn = ahora,
                            actualizadoEn = ahora,
                        ),
                        tvgIdUnicoEnFuente = tvgUnico,
                    )
                for (apariencia in canal.apariencias) {
                    val grupoId = apariencia.groupTitle?.let { insertarGrupo(idLista, it) }
                    val escritura =
                        escritorApariencias.upsert(
                            AparienciaEntity(
                                listaId = idLista,
                                canalId = canalId,
                                grupoId = grupoId,
                                orden = orden++,
                                groupTitle = apariencia.groupTitle,
                                tvgName = apariencia.tvgName,
                                clavePresentacion = apariencia.clavePresentacion,
                                repeticiones = apariencia.repeticiones,
                                creadoEn = ahora,
                                actualizadoEn = ahora,
                            ),
                            ahora,
                        )
                    if (escritura is ResultadoUpsertApariencia.Rechazada) {
                        throw ImportacionRechazada(escritura.codigo)
                    }
                }
            }
            db.estadoActualizacionDao().finalizar(idEstado, Instant.now(), RESULTADO_COMPLETADA)
            return ListaPersistida(idLista, canales.size)
        }

        private fun cifrarLogo(logo: String?): ByteArray? =
            logo?.let {
                CifradoAes.cifrar(
                    claves.claveAes,
                    CifradoAes.aad("Canal", "logo_url_cifrada"),
                    it.toByteArray(Charsets.UTF_8),
                )
            }

        private suspend fun insertarGrupo(
            listaId: Long,
            nombre: String,
        ): Long =
            db.grupoDao().porNombre(listaId, nombre)?.id
                ?: db.grupoDao().insertar(GrupoEntity(listaId = listaId, nombre = nombre))

        /** Lista persistida y cantidad de Canales que la Fuente ya tiene. */
        private data class ListaPersistida(val id: Long, val canales: Int)

        /** Resultado interno de una descarga HTTP. */
        private sealed interface Descarga {
            data class Ok(val importado: ResultadoImportacionM3U) : Descarga

            data class Fallo(val codigo: String) : Descarga
        }

        private companion object {
            const val FIXTURE = "fixture-canario.m3u"
            const val NOMBRE_FUENTE = "Fixture canario E1"
            const val UBICACION_FIXTURE = "content://com.copernicotv.app.fixture/fixture-canario.m3u"

            const val CAUSA_IMPORTACION = "importacion_inicial"
            const val VERSION_IMPORTACION = "m3u:v1"
            const val RESULTADO_COMPLETADA = "completada"
            const val RESULTADO_SIN_CAMBIOS = "sin_cambios"

            const val ERROR_UBICACION_INVALIDA = "ubicacion_invalida"
            const val ERROR_ESQUEMA_NO_ADMITIDO = "esquema_no_admitido"
            const val ERROR_PERMISO_SAF = "permiso_saf_no_persistido"
            const val ERROR_ARCHIVO_INACCESIBLE = "archivo_inaccesible"
            const val ERROR_SIN_CONEXION = "sin_conexion"
            const val ERROR_TIMEOUT = "timeout"
            const val ERROR_HTTP_4XX = "http_4xx"
            const val ERROR_HTTP_5XX = "http_5xx"
            const val ERROR_SIN_CANALES = "sin_canales"
        }
    }

/**
 * Nombre de Fuente para un documento SAF a partir de su nombre visible.
 *
 * Función pura para poder probar la selección sin un `ContentResolver`: el
 * nombre visible manda; si falta, se usa el último segmento de la URI; si
 * tampoco hay, un valor estable. Nunca se registra la URI.
 */
internal fun nombreDeDocumento(
    nombreVisible: String?,
    uriTexto: String,
): String =
    nombreVisible?.trim()?.takeIf { it.isNotEmpty() }
        ?: uriTexto.substringAfterLast('/').substringBefore('?').trim().takeIf { it.isNotEmpty() }
        ?: NOMBRE_DOCUMENTO_POR_DEFECTO

private const val NOMBRE_DOCUMENTO_POR_DEFECTO = "Lista importada"

/**
 * Tiempos de espera de la descarga HTTP de importación.
 *
 * Es un valor y no dos constantes para que la prueba del camino `timeout`
 * pueda ejercitarlo sin esperar los 30 s de producción.
 */
data class TiemposHttp(
    val conexionMs: Int = 15_000,
    val lecturaMs: Int = 30_000,
)
