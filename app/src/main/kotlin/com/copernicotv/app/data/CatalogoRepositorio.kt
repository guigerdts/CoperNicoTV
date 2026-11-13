package com.copernicotv.app.data

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
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
import com.copernicotv.core.m3u.LecturaM3U
import com.copernicotv.core.m3u.LimitesParserM3U
import com.copernicotv.core.m3u.ResultadoImportacionM3U
import com.copernicotv.core.modelo.FilaCanal
import com.copernicotv.core.modelo.InvariantesCanal
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.IOException
import java.io.InputStream
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
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
    /** Diagnóstico técnico de la descarga y la persistencia, sin datos sensibles. */
    val diagnostico: DiagnosticoImportacion? = null,
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
        private val indice: IndiceHmacRepositorio,
        @ApplicationContext private val contexto: Context,
        private val tiempos: TiemposHttp = TiemposHttp(),
        private val verificaCleartext: VerificacionCleartext = VerificacionCleartext(::politicaCleartextPermitida),
    ) {
        private val io = Dispatchers.IO

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
                val sello =
                    indice.selloHmac() as? SelloIndiceHmac.Disponible
                        ?: return@withContext ResumenImportacion(null, 0, 0, ERROR_INDICE_HMAC_NO_OPERATIVO)
                val importado =
                    ImportadorM3U.importar(
                        texto = texto,
                        claveHmac = sello.clave,
                        generacionHmac = sello.generacion,
                    )
                persistir(nombreFuente, ubicacion, canonica, importado, sello)
            }

        /**
         * Da de alta una Fuente y su Lista desde una URL HTTP/HTTPS.
         *
         * La descarga y el parseo ocurren antes de abrir la transacción: un
         * timeout, un 4xx/5xx o una respuesta vacía devuelven un error tipado
         * sin tocar el catálogo ya persistido.
         *
         * [alFase] informa el progreso con fases reales; [alAvance] publica
         * una foto de avance cada 500 ms (entradas, canales, bytes, tiempo).
         * Ambos se invocan desde el hilo de E/S y nunca llevan datos de la
         * lista ni de la ubicación (E7-T7, S21).
         *
         * La cancelación es cooperativa: cancelar el `Job` del llamador hace
         * que el siguiente tick lance `CancellationException` y aborte la
         * lectura o el procesamiento sin tocar el catálogo previo (S22).
         */
        suspend fun importarDesdeUrl(
            nombreFuente: String,
            url: String,
            alFase: (FaseImportacion) -> Unit = {},
            alAvance: (AvanceVisible) -> Unit = {},
        ): ResumenImportacion =
            withContext(io) {
                val canonica = canonicalizar(url)
                if (canonica == null) {
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_UBICACION_INVALIDA)
                }
                if (canonica.clase != ClaseUrl.HTTP_HTTPS) {
                    return@withContext ResumenImportacion(null, 0, 0, ERROR_ESQUEMA_NO_ADMITIDO)
                }
                val sello =
                    indice.selloHmac() as? SelloIndiceHmac.Disponible
                        ?: return@withContext ResumenImportacion(null, 0, 0, ERROR_INDICE_HMAC_NO_OPERATIVO)
                val avance = AvanceImportacion()
                // El Job activo aquí es el que el usuario cancela: los ticks
                // cooperativos lo consultan para abortar parseo y procesamiento
                // (el trabajo es bloqueante, sin puntos de suspensión).
                val contexto = currentCoroutineContext()
                // Cada transición de fase es un punto de chequeo del vigilante
                // de avance: si pasó el umbral sin progreso, aborta tipado.
                val faseVerificada: (FaseImportacion) -> Unit = { fase ->
                    contexto.ensureActive()
                    avance.verificar()
                    alFase(fase)
                }
                val publicador =
                    launch {
                        while (true) {
                            delay(AvanceImportacion.INTERVALO_PUBLICACION_MS)
                            alAvance(avance.foto())
                        }
                    }
                val activo = { contexto.ensureActive() }
                try {
                    when (val descarga = descargar(canonica.urlCanonica, faseVerificada, avance, activo)) {
                        is Descarga.Ok -> {
                            faseVerificada(FaseImportacion.PROCESANDO)
                            val inicioProceso = System.currentTimeMillis()
                            val importado =
                                ImportadorM3U
                                    .importarDeclaraciones(
                                        descarga.lectura.declaraciones,
                                        sello.clave,
                                        sello.generacion,
                                        // El tick de la agrupación (etapa más larga y
                                        // antes muda, L13) reinicia el vigilante.
                                        alAvanceAgrupacion = { agrupadas ->
                                            contexto.ensureActive()
                                            avance.verificar()
                                            avance.ticAgrupacion(agrupadas)
                                        },
                                    ) { procesados ->
                                        contexto.ensureActive()
                                        avance.verificar()
                                        avance.ticCanal(procesados)
                                    }.copy(diagnosticos = descarga.lectura.diagnosticos)
                            val diagnostico =
                                descarga.diagnostico.copy(
                                    entradasDetectadas = descarga.lectura.entradas,
                                    canalesDetectados = importado.canales.size,
                                    tiempoProcesamientoMs = System.currentTimeMillis() - inicioProceso,
                                    ultimoAvance = avance.texto(),
                                    lineasLeidas = descarga.lectura.lineasLeidas,
                                    extinfEncontrados = descarga.lectura.extinfEncontrados,
                                    urlsAsociadas = descarga.lectura.entradas,
                                    entradasTv = descarga.lectura.entradasTv,
                                    entradasVod = descarga.lectura.entradasVod,
                                    entradasOtro = descarga.lectura.entradasOtro,
                                    descartadosParser =
                                        descarga.lectura.diagnosticos
                                            .associate { it.codigo.codigo to it.conteoTotal },
                                    rechazosCanonizacion =
                                        importado.rechazadas.groupingBy { it.error.codigo.codigo }.eachCount(),
                                    declaracionesCreadas = importado.declaracionesRecibidas,
                                    operacionesCriptograficas = importado.operacionesCriptograficas,
                                    tiempoAgrupacionMs = importado.tiempoAgrupacionMs,
                                    tiempoHmacMs = importado.tiempoHmacMs,
                                )
                            persistir(
                                nombreFuente,
                                url,
                                canonica,
                                importado,
                                sello,
                                diagnostico,
                                faseVerificada,
                                avance,
                            )
                        }
                        is Descarga.Fallo -> {
                            registrarFallo(nombreFuente, descarga.codigo)
                            logDiagnostico(descarga.diagnostico, descarga.codigo)
                            ResumenImportacion(null, 0, 0, descarga.codigo, diagnostico = descarga.diagnostico)
                        }
                    }
                } catch (detencion: ImportacionAvanceDetenido) {
                    val codigo = ERROR_AVANCE_DETENIDO
                    registrarFallo(nombreFuente, codigo)
                    val diagnostico =
                        DiagnosticoImportacion(
                            ultimoAvance = avance.texto(),
                            sinAvanceMs = detencion.msSinAvance,
                            motivo = codigo,
                        )
                    logDiagnostico(diagnostico, codigo)
                    ResumenImportacion(null, 0, 0, codigo, diagnostico = diagnostico)
                } finally {
                    publicador.cancel()
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
                val sello =
                    indice.selloHmac() as? SelloIndiceHmac.Disponible
                        ?: return@withContext ResumenImportacion(null, 0, 0, ERROR_INDICE_HMAC_NO_OPERATIVO)
                val importado =
                    try {
                        contexto.contentResolver.openInputStream(uri)?.use { entrada ->
                            ImportadorM3U.importar(
                                entrada = entrada,
                                claveHmac = sello.clave,
                                generacionHmac = sello.generacion,
                            )
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
                persistir(nombreFuente, uriTexto, canonica, importado, sello)
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
            sello: SelloIndiceHmac.Disponible,
            diagnostico: DiagnosticoImportacion = DiagnosticoImportacion(),
            alFase: (FaseImportacion) -> Unit = {},
            avance: AvanceImportacion? = null,
        ): ResumenImportacion {
            val diagnosticos = importado.diagnosticos.map { it.codigo.codigo }
            if (importado.canales.isEmpty()) {
                val error = importado.errores.firstOrNull()?.codigo?.codigo ?: ERROR_SIN_CANALES
                registrarFallo(nombreFuente, error)
                logDiagnostico(diagnostico.copy(motivo = error), error)
                return ResumenImportacion(
                    null,
                    0,
                    importado.rechazadas.size,
                    error,
                    diagnosticos,
                    diagnostico.copy(motivo = error),
                )
            }
            alFase(FaseImportacion.GUARDANDO)
            val inicioGuardado = System.currentTimeMillis()
            val persistida =
                try {
                    db.withTransaction {
                        val existente = listaExistente(nombreFuente)
                        val ahora = Instant.now()
                        val lista =
                            if (existente != null) {
                                registrarIntento(existente.id, RESULTADO_SIN_CAMBIOS)
                                existente
                            } else {
                                escribirCatalogo(nombreFuente, ubicacion, canonica, importado.canales, sello, avance)
                            }
                        // La Lista importada queda como única activa, también
                        // cuando ya existía: reintentar o repetir la importación
                        // debe mostrar su catálogo (E7-T7, S5/S12).
                        db.listaDao().desactivarTodas()
                        db.listaDao().activar(lista.id, ahora)
                        lista
                    }
                } catch (rechazo: ImportacionRechazada) {
                    registrarFallo(nombreFuente, rechazo.codigo)
                    val codigo = rechazo.codigo
                    logDiagnostico(diagnostico.copy(motivo = codigo), codigo)
                    return ResumenImportacion(
                        null,
                        0,
                        importado.rechazadas.size,
                        codigo,
                        diagnosticos,
                        diagnostico.copy(motivo = codigo),
                    )
                }
            val final =
                diagnostico.copy(
                    canalesPersistidos = persistida.canales,
                    tiempoGuardadoMs = System.currentTimeMillis() - inicioGuardado,
                )
            logDiagnostico(final, RESULTADO_COMPLETADA)
            return ResumenImportacion(
                persistida.id,
                persistida.canales,
                importado.rechazadas.size,
                null,
                diagnosticos,
                final,
            )
        }

        private fun canonicalizar(ubicacion: String): ResultadoCanonizacion.Ok? =
            CanonicalizadorUrl.canonizar(ubicacion, CampoUrl.LISTA_UBICACION) as? ResultadoCanonizacion.Ok

        /**
         * Descarga y parsea en streaming la Lista de [url].
         *
         * Reporta la fase CONECTANDO hasta obtener respuesta y DESCARGANDO
         * mientras fluye el cuerpo: el parseo consume el flujo en caliente, así
         * que la lectura de red domina esa fase. El diagnóstico recoge código
         * HTTP, tipo de contenido, bytes y tiempos, sin datos de la lista.
         *
         * E7-T7: la lectura no retiene las entradas del parser; cada una se
         * convierte a declaración de identidad al completarse y el avance se
         * publica por entrada (S21/S22). El chequeo de detención NO corre en
         * los ticks de entrada: en red lenta pero viva el que manda es el
         * timeout de lectura, y abortar antes sería descartar servidores
         * legítimamente lentos.
         */
        private fun descargar(
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
                            DiagnosticoImportacion(codigoHttp = codigo, motivo = ERROR_SIN_CONEXION),
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

        /** Registro de diagnóstico en Logcat, acotado y sin datos sensibles. */
        private fun logDiagnostico(
            diagnostico: DiagnosticoImportacion?,
            resultado: String,
        ) {
            diagnostico?.let { Log.i(TAG_DIAGNOSTICO, it.aLogcat(resultado)) }
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
            sello: SelloIndiceHmac.Disponible,
            avance: AvanceImportacion? = null,
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
                                    clave = sello.clave,
                                    generacion = sello.generacion,
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
            val entidades =
                prepararEntidadesCanales(canales, fuenteId, ahora, avance)
            val idsCanales = ArrayList<Long>(entidades.size)
            entidades.chunked(LOTE_INSERTAR).forEach { lote ->
                idsCanales += db.canalDao().insertarTodos(lote)
                avance?.ticPreparacion(entidades.size)
            }
            val grupoIdPorNombre = prepararGrupos(canales, idLista)
            val filasApariencias = prepararApariencias(canales, idLista, idsCanales, grupoIdPorNombre, ahora, avance)
            filasApariencias.chunked(LOTE_INSERTAR).forEach { lote ->
                db.aparienciaDao().insertarTodas(lote)
                avance?.ticPreparacion(entidades.size)
            }
            db.estadoActualizacionDao().finalizar(idEstado, Instant.now(), RESULTADO_COMPLETADA)
            return ListaPersistida(idLista, canales.size)
        }

        /**
         * Prepara las entidades de `Canal` con validación previa de todas las
         * filas (E7-T7, S22/S26).
         *
         * La misma semántica de rechazo de `CanalEscritor.insertar`: la
         * primera violación de invariantes aborta con `CanalRechazado` y la
         * transacción externa revierte. El cifrado de URL y logo (AES con
         * clave de Keystore) es la etapa dominante, así que el avance se
         * emite por canal.
         */
        private fun prepararEntidadesCanales(
            canales: List<CanalImportadoM3U>,
            fuenteId: Long,
            ahora: Instant,
            avance: AvanceImportacion?,
        ): List<CanalEntity> {
            val entidades = ArrayList<CanalEntity>(canales.size)
            canales.forEachIndexed { indice, canal ->
                val tvg = canal.tvgId
                // La forma persistida de la clave de unicidad es la única fuente de
                // verdad sobre si el tvg-id habilitó la clave (§6.3.2).
                val tvgUnico = tvg != null && canal.claveUnicidad == ClaveCanal.PREFIJO_TVG + tvg
                val entidad =
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
                    )
                val violaciones =
                    InvariantesCanal.validar(
                        FilaCanal(
                            nombre = entidad.nombre,
                            estado = entidad.estado,
                            claveDerivable = entidad.claveDerivable,
                            claveCanonica = entidad.claveCanonica,
                            claveUnicidad = entidad.claveUnicidad,
                            tvgId = entidad.tvgId,
                            urlStreamCifradaPresente = entidad.urlStreamCifrada.isNotEmpty(),
                            urlStreamHmacPresente = entidad.urlStreamHmac.isNotEmpty(),
                        ),
                        tvgUnico,
                    )
                if (violaciones.isNotEmpty()) throw CanalRechazado(violaciones)
                entidades += entidad
                avance?.ticPreparacion(indice + 1)
            }
            return entidades
        }

        /** Grupos únicos de la importación, insertados por lotes una sola vez. */
        private suspend fun prepararGrupos(
            canales: List<CanalImportadoM3U>,
            idLista: Long,
        ): Map<String, Long> {
            val nombres = LinkedHashSet<String>()
            canales.forEach { canal ->
                canal.apariencias.forEach { apariencia -> apariencia.groupTitle?.let { nombres.add(it) } }
            }
            val ids =
                db.grupoDao().insertarTodos(nombres.map { nombre -> GrupoEntity(listaId = idLista, nombre = nombre) })
            return nombres.zip(ids).toMap()
        }

        /**
         * Filas de `Apariencia` listas para inserción por lotes.
         *
         * La Lista se crea en esta misma transacción, así que no existen filas
         * previas ni pendientes de migración para sus tuplas: el upsert por
         * fila de `AparienciaEscritor` sería siempre una inserción con las
         * repeticiones tal como llegan y sin conflicto. Se conserva el `orden`
         * secuencial idéntico al camino anterior (E7-T7, S26).
         */
        private fun prepararApariencias(
            canales: List<CanalImportadoM3U>,
            idLista: Long,
            idsCanales: List<Long>,
            grupoIdPorNombre: Map<String, Long>,
            ahora: Instant,
            avance: AvanceImportacion?,
        ): List<AparienciaEntity> {
            val filas = ArrayList<AparienciaEntity>()
            var orden = 0
            canales.forEachIndexed { indice, canal ->
                val canalId = idsCanales[indice]
                canal.apariencias.forEach { apariencia ->
                    filas +=
                        AparienciaEntity(
                            listaId = idLista,
                            canalId = canalId,
                            grupoId = apariencia.groupTitle?.let { grupoIdPorNombre[it] },
                            orden = orden++,
                            groupTitle = apariencia.groupTitle,
                            tvgName = apariencia.tvgName,
                            clavePresentacion = apariencia.clavePresentacion,
                            repeticiones = apariencia.repeticiones,
                            creadoEn = ahora,
                            actualizadoEn = ahora,
                        )
                }
                avance?.ticPreparacion(orden)
            }
            return filas
        }

        private fun cifrarLogo(logo: String?): ByteArray? =
            logo?.let {
                CifradoAes.cifrar(
                    claves.claveAes,
                    CifradoAes.aad("Canal", "logo_url_cifrada"),
                    it.toByteArray(Charsets.UTF_8),
                )
            }

        /** Lista persistida y cantidad de Canales que la Fuente ya tiene. */
        private data class ListaPersistida(val id: Long, val canales: Int)

        /** Resultado interno de una descarga HTTP. */
        private sealed interface Descarga {
            data class Ok(
                val lectura: LecturaM3U,
                val diagnostico: DiagnosticoImportacion,
            ) : Descarga

            data class Fallo(
                val codigo: String,
                val diagnostico: DiagnosticoImportacion?,
            ) : Descarga
        }

        private companion object {
            const val FIXTURE = "fixture-canario.m3u"
            const val NOMBRE_FUENTE = "Fixture canario E1"
            const val UBICACION_FIXTURE = "content://com.copernicotv.app.fixture/fixture-canario.m3u"
            const val TAG_DIAGNOSTICO = "ImportacionDiagnostico"

            const val CAUSA_IMPORTACION = "importacion_inicial"
            const val VERSION_IMPORTACION = "m3u:v1"
            const val RESULTADO_COMPLETADA = "completada"
            const val RESULTADO_SIN_CAMBIOS = "sin_cambios"

            const val ERROR_UBICACION_INVALIDA = "ubicacion_invalida"
            const val ERROR_ESQUEMA_NO_ADMITIDO = "esquema_no_admitido"
            const val ERROR_PERMISO_SAF = "permiso_saf_no_persistido"
            const val ERROR_INDICE_HMAC_NO_OPERATIVO = "indice_hmac_no_operativo"
            const val ERROR_ARCHIVO_INACCESIBLE = "archivo_inaccesible"
            const val ERROR_SIN_CONEXION = "sin_conexion"
            const val ERROR_TIMEOUT = "timeout"
            const val ERROR_HTTP_4XX = "http_4xx"
            const val ERROR_DNS = "dns_no_resuelto"
            const val ERROR_CONEXION_RECHAZADA = "conexion_rechazada"
            const val ERROR_BLOQUEO_HTTP = "bloqueo_http"
            const val ERROR_HTTP_5XX = "http_5xx"
            const val ERROR_SIN_CANALES = "sin_canales"
            const val ERROR_AVANCE_DETENIDO = "avance_detenido"

            /** Tamaño de los lotes de inserción por filas (E7-T7, S22). */
            const val LOTE_INSERTAR = 2000
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
 * Flujo que cuenta los bytes leídos, para el diagnóstico de descarga (E7-T7).
 *
 * No retiene el contenido: solo acumula el total observado por `read`.
 */
internal class InputStreamContado(
    private val origen: InputStream,
) : InputStream() {
    var contados: Long = 0
        private set

    override fun read(): Int {
        val leido = origen.read()
        if (leido >= 0) contados++
        return leido
    }

    override fun read(
        destino: ByteArray,
        desde: Int,
        maximo: Int,
    ): Int {
        val leidos = origen.read(destino, desde, maximo)
        if (leidos > 0) contados += leidos
        return leidos
    }

    override fun close() {
        origen.close()
    }
}

/**
 * Tiempos de espera de la descarga HTTP de importación.
 *
 * Es un valor y no dos constantes para que la prueba del camino `timeout`
 * pueda ejercitarlo sin esperar los tiempos de producción.
 *
 * La lectura tolera 120 s porque los paneles Xtream/M3U Plus suelen generar
 * todo el documento antes de empezar a enviarlo: con playlists grandes el
 * primer byte puede tardar más de 30 s aunque el servidor esté sano (E7-T7).
 */
data class TiemposHttp(
    val conexionMs: Int = 15_000,
    val lecturaMs: Int = 120_000,
)
