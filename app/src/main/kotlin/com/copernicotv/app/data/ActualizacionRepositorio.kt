package com.copernicotv.app.data

import android.content.Context
import android.net.Uri
import androidx.room.withTransaction
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesDatos
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.DetalleAusenciaEntity
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import com.copernicotv.app.data.db.entities.EvidenciaColisionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.ObservacionPendienteImportacionEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.m3u.CanalImportadoM3U
import com.copernicotv.core.m3u.CodecAtributosExtra
import com.copernicotv.core.m3u.ImportadorM3U
import com.copernicotv.core.m3u.ResultadoImportacionM3U
import com.copernicotv.core.reconciliacion.DeclaracionNueva
import com.copernicotv.core.reconciliacion.DestinoDeclaracion
import com.copernicotv.core.reconciliacion.FilaHistorica
import com.copernicotv.core.reconciliacion.IdentidadReconciliacion
import com.copernicotv.core.reconciliacion.Reconciliador
import com.copernicotv.core.reconciliacion.ResultadoReconciliacion
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.IOException
import java.net.ConnectException
import java.net.HttpURLConnection
import java.net.SocketTimeoutException
import java.net.URL
import java.net.UnknownHostException
import java.security.MessageDigest
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Resumen de una Actualización de una Lista. */
data class ResumenActualizacion(
    val listaId: Long,
    val resultado: String,
    val nuevas: Int = 0,
    val actualizadas: Int = 0,
    val ausentes: Int = 0,
    val ambiguas: Int = 0,
    val observaciones: Int = 0,
    val error: String? = null,
)

/**
 * Actualización de una Lista ya persistida (E4-T1/T2/T6).
 *
 * La descarga y el parseo ocurren fuera de la transacción: un fallo de red o un
 * documento inaccesible no toca el catálogo. La reconciliación se aplica en una
 * única transacción; cualquier rechazo revierte todo y registra el fallo en un
 * intento separado.
 *
 * Orden contractual A→B: antes de escribir evidencia o claves nuevas se liberan
 * en una sola sentencia las `clave_unicidad` de todas las filas que pasan a B.
 */
@Singleton
class ActualizacionRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
        private val clavesDatos: ClavesDatos,
        private val indice: IndiceHmacRepositorio,
        @ApplicationContext private val contexto: Context,
        private val tiempos: TiemposHttp = TiemposHttp(),
        private val verificaCleartext: VerificacionCleartext = VerificacionCleartext(::politicaCleartextPermitida),
    ) {
        private val io = Dispatchers.IO
        private val escritorCanales = CanalEscritor(db)
        private val escritorApariencias = AparienciaEscritor(db)
        private val escritorLotes = ActualizacionLotesEscritor(db, clavesDatos)

        /**
         * Punto de fallo inyectable para probar el rollback de A→B con N filas.
         * En producción queda en `null`; la prueba lo usa para forzar una
         * excepción después de liberar las claves y comprobar que no queda nada.
         */
        internal var puntoDeFallo: ((EtapaActualizacion) -> Unit)? = null

        /** Actualiza la Lista indicada, con la causa contractual (`manual`/`automatica`). */
        suspend fun actualizarLista(
            listaId: Long,
            causa: String = CAUSA_MANUAL,
        ): ResumenActualizacion =
            withContext(io) {
                val lista =
                    db.listaDao().porId(listaId)
                        ?: return@withContext ResumenActualizacion(listaId, RESULTADO_FALLIDA, error = ERROR_LISTA_INEXISTENTE)
                val fuente =
                    db.fuenteDao().porId(lista.fuenteId)
                        ?: return@withContext ResumenActualizacion(listaId, RESULTADO_FALLIDA, error = ERROR_FUENTE_INEXISTENTE)
                val lectura = leer(lista)
                if (lectura is Lectura.Fallo) {
                    registrarFallo(fuente.id, lista.id, causa, lectura.codigo)
                    return@withContext ResumenActualizacion(lista.id, RESULTADO_FALLIDA, error = lectura.codigo)
                }
                val importado = (lectura as Lectura.Ok).importado
                if (importado.canales.isEmpty()) {
                    val codigo = importado.errores.firstOrNull()?.codigo?.codigo ?: ERROR_SIN_CANALES
                    registrarFallo(fuente.id, lista.id, causa, codigo)
                    return@withContext ResumenActualizacion(lista.id, RESULTADO_FALLIDA, error = codigo)
                }
                aplicar(fuente, lista, importado, causa)
            }

        /**
         * Aplica una importación ya parseada a la Lista indicada.
         *
         * Punto de entrada de la reconciliación, separado de la lectura, para
         * poder probar T1/T2/T6 con el parser real sin depender de SAF ni de la
         * red. La lectura y el parseo de producción siguen ocurriendo fuera de
         * la transacción.
         */
        internal suspend fun actualizarConImportado(
            listaId: Long,
            importado: ResultadoImportacionM3U,
            causa: String = CAUSA_MANUAL,
        ): ResumenActualizacion =
            withContext(io) {
                val lista =
                    db.listaDao().porId(listaId)
                        ?: return@withContext ResumenActualizacion(listaId, RESULTADO_FALLIDA, error = ERROR_LISTA_INEXISTENTE)
                val fuente =
                    db.fuenteDao().porId(lista.fuenteId)
                        ?: return@withContext ResumenActualizacion(listaId, RESULTADO_FALLIDA, error = ERROR_FUENTE_INEXISTENTE)
                if (importado.canales.isEmpty()) {
                    val codigo = importado.errores.firstOrNull()?.codigo?.codigo ?: ERROR_SIN_CANALES
                    registrarFallo(fuente.id, lista.id, causa, codigo)
                    return@withContext ResumenActualizacion(lista.id, RESULTADO_FALLIDA, error = codigo)
                }
                aplicar(fuente, lista, importado, causa)
            }

        /** Actualiza la Lista activa de cada Fuente; devuelve un resumen por Fuente. */
        suspend fun actualizarTodas(causa: String = CAUSA_AUTOMATICA): List<ResumenActualizacion> =
            withContext(io) {
                db.fuenteDao().todas().mapNotNull { fuente ->
                    val lista =
                        db.listaDao().porFuente(fuente.id).let { listas ->
                            listas.firstOrNull { it.estado == ESTADO_ACTIVA } ?: listas.firstOrNull()
                        } ?: return@mapNotNull null
                    actualizarLista(lista.id, causa)
                }
            }

        private suspend fun aplicar(
            fuente: FuenteEntity,
            lista: ListaEntity,
            importado: ResultadoImportacionM3U,
            causa: String,
        ): ResumenActualizacion {
            val ahora = Instant.now()
            return try {
                val resumen =
                    db.withTransaction {
                        val idEstado =
                            db.estadoActualizacionDao().insertar(
                                EstadoActualizacionEntity(
                                    fuenteId = fuente.id,
                                    listaId = lista.id,
                                    causa = causa,
                                    versionImport = VERSION_IMPORTACION,
                                    inicio = ahora,
                                ),
                            )
                        val plan =
                            Reconciliador.reconciliar(
                                historicas = cargarFilasHistoricas(fuente.id),
                                declaraciones =
                                    importado.canales.mapIndexed { indice, canal ->
                                        DeclaracionNueva(indice, identidadDe(canal))
                                    },
                            )
                        liberarClaves(plan, ahora)
                        puntoDeFallo?.invoke(EtapaActualizacion.TRAS_LIBERAR_CLAVES)
                        val grupos = HashMap<String, Long>()
                        val actualizadas =
                            escritorLotes.aplicar(lista.id, plan, importado.canales, ahora, grupos)
                        var nuevas = 0
                        var observaciones = 0
                        for (destino in plan.declaraciones) {
                            when (destino.destino) {
                                DestinoDeclaracion.CANAL_NUEVO -> {
                                    insertarCanal(
                                        fuenteId = fuente.id,
                                        listaId = lista.id,
                                        declaracion = importado.canales[destino.indice],
                                        ahora = ahora,
                                        grupos = grupos,
                                    )
                                    nuevas += 1
                                }
                                DestinoDeclaracion.OBSERVACION -> {
                                    registrarObservacion(
                                        fuenteId = fuente.id,
                                        listaId = lista.id,
                                        indice = destino.indice,
                                        declaracion = importado.canales[destino.indice],
                                        plan = plan,
                                        ahora = ahora,
                                    )
                                    observaciones += 1
                                }
                            }
                        }
                        registrarEvidencias(idEstado, plan, ahora)
                        registrarAusencias(idEstado, plan, ahora)
                        db.estadoActualizacionDao().finalizar(idEstado, Instant.now(), RESULTADO_COMPLETADA)
                        ResumenActualizacion(
                            listaId = lista.id,
                            resultado = RESULTADO_COMPLETADA,
                            nuevas = nuevas,
                            actualizadas = actualizadas,
                            ausentes = plan.ausencias.size,
                            ambiguas = plan.colisiones.size,
                            observaciones = observaciones,
                        )
                    }
                resumen
            } catch (cancelacion: CancellationException) {
                throw cancelacion
            } catch (rechazo: ImportacionRechazada) {
                registrarFallo(fuente.id, lista.id, causa, rechazo.codigo)
                ResumenActualizacion(lista.id, RESULTADO_FALLIDA, error = rechazo.codigo)
            } catch (error: Exception) {
                registrarFallo(fuente.id, lista.id, causa, ERROR_ACTUALIZACION_FALLIDA)
                ResumenActualizacion(lista.id, RESULTADO_FALLIDA, error = ERROR_ACTUALIZACION_FALLIDA)
            }
        }

        /**
         * Carga las identidades históricas de una Fuente en páginas *keyset*.
         *
         * No retiene entidades completas: cada página se convierte a
         * `FilaHistorica` y se descarta. El motor recibe solo las identidades
         * compactas que necesita.
         */
        private suspend fun cargarFilasHistoricas(fuenteId: Long): List<FilaHistorica> {
            val filas = ArrayList<FilaHistorica>()
            var ultimoId = 0L
            while (true) {
                val lote = db.canalDao().identidadesPorFuente(fuenteId, ultimoId, LOTE_LECTURA)
                if (lote.isEmpty()) break
                for (proyeccion in lote) {
                    filas += proyeccion.aFilaHistorica()
                }
                ultimoId = lote.last().id
            }
            return filas
        }

        /**
         * Libera en lotes las claves de las filas que pasan a B.
         *
         * El guard `claveUnicidad IS NOT NULL` de la sentencia hace innecesario
         * conocer la entidad completa: solo las filas que aún tenían clave se
         * actualizan. Todos los lotes se ejecutan antes de escribir evidencia.
         */
        private suspend fun liberarClaves(
            plan: ResultadoReconciliacion,
            ahora: Instant,
        ) {
            val ids =
                plan.filas
                    .asSequence()
                    .filter { it.estado == EstadoCanal.B }
                    .map { it.historicoId }
                    .toList()
            for (lote in ids.chunked(LOTE_ESCRITURA)) {
                db.canalDao().liberarClaves(lote, ahora)
            }
        }

        private suspend fun insertarCanal(
            fuenteId: Long,
            listaId: Long,
            declaracion: CanalImportadoM3U,
            ahora: Instant,
            grupos: MutableMap<String, Long>,
        ): Long {
            val canalId =
                escritorCanales.insertar(
                    CanalEntity(
                        fuenteId = fuenteId,
                        nombre = declaracion.nombre,
                        urlStreamCifrada = cifrar(declaracion.urlOriginal, "Canal", "url_stream_cifrada"),
                        urlStreamHmac = declaracion.urlStreamHmac.toByteArray(Charsets.UTF_8),
                        logoUrlCifrada = cifrarLogo(declaracion.logoOriginal),
                        logoUrlHmac = declaracion.logoHmac?.toByteArray(Charsets.UTF_8),
                        tvgId = declaracion.tvgId,
                        claveCanonica = declaracion.claveCanonica,
                        claveUnicidad = declaracion.claveUnicidad,
                        claveDerivable = declaracion.claveDerivable,
                        atributosExtra = CodecAtributosExtra.codificar(declaracion.atributosExtra),
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                    tvgIdUnico(declaracion),
                )
            var orden = db.aparienciaDao().maxOrden(listaId) + 1
            for (apariencia in declaracion.apariencias) {
                val grupoId = apariencia.groupTitle?.let { insertarGrupo(listaId, it, grupos) }
                val escritura =
                    escritorApariencias.upsert(
                        AparienciaEntity(
                            listaId = listaId,
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
            return canalId
        }

        private suspend fun registrarEvidencias(
            estadoId: Long,
            plan: ResultadoReconciliacion,
            ahora: Instant,
        ) {
            for (colision in plan.colisiones) {
                db.evidenciaColisionDao().insertar(
                    EvidenciaColisionEntity(
                        estadoActualizacionId = estadoId,
                        claveCanonica = colision.clave,
                        insumos =
                            "filas=${colision.filas.joinToString(",")};" +
                                "declaraciones=${colision.declaraciones.joinToString(",")}",
                        fecha = ahora,
                        version = VERSION_EVIDENCIA,
                    ),
                )
            }
        }

        private suspend fun registrarAusencias(
            estadoId: Long,
            plan: ResultadoReconciliacion,
            ahora: Instant,
        ) {
            if (plan.ausencias.isEmpty()) return
            for (lote in plan.ausencias.chunked(LOTE_ESCRITURA)) {
                db.detalleAusenciaDao().insertarTodas(
                    lote.map { canalId ->
                        DetalleAusenciaEntity(
                            estadoActualizacionId = estadoId,
                            canalId = canalId,
                            estado = ESTADO_AUSENTE,
                            texto = TEXTO_AUSENTE,
                            creadoEn = ahora,
                        )
                    },
                )
            }
        }

        /**
         * Registra o deduplica una Observación pendiente.
         *
         * La misma declaración observada en Actualizaciones sucesivas conserva
         * `primeraObservacion` y actualiza `ultimaObservacion` (E4-T3).
         */
        private suspend fun registrarObservacion(
            fuenteId: Long,
            listaId: Long,
            indice: Int,
            declaracion: CanalImportadoM3U,
            plan: ResultadoReconciliacion,
            ahora: Instant,
        ) {
            val deduplicacion =
                sha256Hex(
                    "$fuenteId|${declaracion.claveUnicidad ?: declaracion.claveCanonica ?: declaracion.urlStreamHmac}",
                )
            val existente = db.observacionPendienteDao().porClave(fuenteId, deduplicacion)
            val conjunto =
                plan.colisiones.firstOrNull { indice in it.declaraciones }?.clave
                    ?: declaracion.claveCanonica
            val nueva =
                ObservacionPendienteImportacionEntity(
                    id = existente?.id ?: 0,
                    fuenteId = fuenteId,
                    importOrigen = "lista:$listaId",
                    nombre = declaracion.nombre,
                    urlCifrada = cifrar(declaracion.urlOriginal, "ObservacionPendienteImportacion", "url_cifrada"),
                    urlHmac = declaracion.urlStreamHmac.toByteArray(Charsets.UTF_8),
                    logoUrlCifrada = cifrarLogoObservacion(declaracion.logoOriginal),
                    logoUrlHmac = declaracion.logoHmac?.toByteArray(Charsets.UTF_8),
                    tvgId = declaracion.tvgId,
                    grupo = declaracion.apariencias.firstOrNull()?.groupTitle,
                    claveCanonica = declaracion.claveCanonica,
                    huellaDeclaracion = huellaDe(declaracion),
                    claveDeduplicacion = deduplicacion,
                    primeraObservacion = existente?.primeraObservacion ?: ahora,
                    ultimaObservacion = ahora,
                    conjuntoBc = conjunto,
                )
            if (existente == null) {
                db.observacionPendienteDao().insertar(nueva)
            } else {
                db.observacionPendienteDao().actualizar(nueva)
            }
        }

        private suspend fun registrarFallo(
            fuenteId: Long,
            listaId: Long,
            causa: String,
            codigo: String,
        ) {
            val ahora = Instant.now()
            db.withTransaction {
                val id =
                    db.estadoActualizacionDao().insertar(
                        EstadoActualizacionEntity(
                            fuenteId = fuenteId,
                            listaId = listaId,
                            causa = causa,
                            versionImport = VERSION_IMPORTACION,
                            inicio = ahora,
                        ),
                    )
                db.estadoActualizacionDao().finalizar(id, Instant.now(), codigo)
            }
        }

        private suspend fun leer(lista: ListaEntity): Lectura {
            // DC-04 §7 (Q2/Q3): sin índice operativo no se descarga ni se sella;
            // el bloqueo es real para esta lectura y toda escritura aguas abajo.
            val sello =
                indice.selloHmac() as? SelloIndiceHmac.Disponible
                    ?: return Lectura.Fallo(ERROR_INDICE_HMAC_NO_OPERATIVO)
            val descifrado =
                CifradoAes.descifrarSobre(
                    claves.claveAes,
                    clavesDatos.clave(),
                    CifradoAes.aad("Lista", "ubicacion_cifrada"),
                    lista.ubicacionCifrada,
                )
            val ubicacion =
                (descifrado as? ResultadoDescifrado.Ok)?.bytes?.toString(Charsets.UTF_8)
                    ?: return Lectura.Fallo(ERROR_UBICACION_INVALIDA)
            return when (lista.esquema) {
                "content" -> leerSaf(ubicacion, sello)
                "http", "https" -> leerHttp(ubicacion, sello)
                else -> Lectura.Fallo(ERROR_ESQUEMA_NO_ADMITIDO)
            }
        }

        private fun leerSaf(
            ubicacion: String,
            sello: SelloIndiceHmac.Disponible,
        ): Lectura =
            try {
                contexto.contentResolver.openInputStream(Uri.parse(ubicacion))?.use { entrada ->
                    Lectura.Ok(
                        ImportadorM3U.importar(
                            entrada = entrada,
                            claveHmac = sello.clave,
                            generacionHmac = sello.generacion,
                        ),
                    )
                } ?: Lectura.Fallo(ERROR_ARCHIVO_INACCESIBLE)
            } catch (_: SecurityException) {
                Lectura.Fallo(ERROR_ARCHIVO_INACCESIBLE)
            } catch (_: IOException) {
                Lectura.Fallo(ERROR_ARCHIVO_INACCESIBLE)
            }

        private fun leerHttp(
            ubicacion: String,
            sello: SelloIndiceHmac.Disponible,
        ): Lectura {
            if (ubicacion.startsWith("http://", ignoreCase = true) && !verificaCleartext.permitido(URL(ubicacion).host)) {
                return Lectura.Fallo(ERROR_BLOQUEO_HTTP)
            }
            val conexion =
                URL(ubicacion).openConnection() as? HttpURLConnection
                    ?: return Lectura.Fallo(ERROR_SIN_CONEXION)
            conexion.connectTimeout = tiempos.conexionMs
            conexion.readTimeout = tiempos.lecturaMs
            conexion.requestMethod = "GET"
            return try {
                when (conexion.responseCode) {
                    in 200..299 ->
                        Lectura.Ok(
                            conexion.inputStream.use { entrada ->
                                ImportadorM3U.importar(
                                    entrada = entrada,
                                    claveHmac = sello.clave,
                                    generacionHmac = sello.generacion,
                                )
                            },
                        )
                    in 400..499 -> Lectura.Fallo(ERROR_HTTP_4XX)
                    in 500..599 -> Lectura.Fallo(ERROR_HTTP_5XX)
                    else -> Lectura.Fallo(ERROR_SIN_CONEXION)
                }
            } catch (_: SocketTimeoutException) {
                Lectura.Fallo(ERROR_TIMEOUT)
            } catch (_: UnknownHostException) {
                Lectura.Fallo(ERROR_DNS)
            } catch (_: ConnectException) {
                Lectura.Fallo(ERROR_CONEXION_RECHAZADA)
            } catch (_: IOException) {
                Lectura.Fallo(ERROR_SIN_CONEXION)
            } finally {
                conexion.disconnect()
            }
        }

        private fun identidadDe(canal: CanalImportadoM3U): IdentidadReconciliacion =
            IdentidadReconciliacion(
                nombre = canal.nombre,
                tvgId = canal.tvgId,
                claveCanonica = canal.claveCanonica,
                claveUnicidad = canal.claveUnicidad,
                claveDerivable = canal.claveDerivable,
                urlStreamHmac = canal.urlStreamHmac,
            )

        private fun tvgIdUnico(canal: CanalImportadoM3U?): Boolean {
            val tvg = canal?.tvgId ?: return false
            return canal.claveUnicidad == ClaveCanal.PREFIJO_TVG + tvg
        }

        private fun cifrar(
            valor: String,
            tabla: String,
            columna: String,
        ): ByteArray =
            CifradoAes.cifrarDatos(
                clavesDatos.clave(),
                CifradoAes.aad(tabla, columna),
                valor.toByteArray(Charsets.UTF_8),
            )

        private fun cifrarLogo(logo: String?): ByteArray? = logo?.let { cifrar(it, "Canal", "logo_url_cifrada") }

        private fun cifrarLogoObservacion(logo: String?): ByteArray? =
            logo?.let { cifrar(it, "ObservacionPendienteImportacion", "logo_url_cifrada") }

        private fun huellaDe(canal: CanalImportadoM3U): String =
            sha256Hex(
                "${canal.nombre}|${canal.tvgId}|${canal.claveCanonica}|" +
                    "${canal.claveUnicidad}|${canal.urlStreamHmac}",
            )

        private fun sha256Hex(texto: String): String =
            MessageDigest
                .getInstance("SHA-256")
                .digest(texto.toByteArray(Charsets.UTF_8))
                .joinToString("") { byte -> "%02x".format(byte.toInt() and 0xff) }

        private suspend fun insertarGrupo(
            listaId: Long,
            nombre: String,
            grupos: MutableMap<String, Long>,
        ): Long {
            grupos[nombre]?.let { return it }
            val id =
                db.grupoDao().porNombre(listaId, nombre)?.id
                    ?: db.grupoDao().insertar(GrupoEntity(listaId = listaId, nombre = nombre))
            grupos[nombre] = id
            return id
        }

        /** Etapa observable por la prueba de rollback. */
        internal enum class EtapaActualizacion {
            TRAS_LIBERAR_CLAVES,
        }

        private sealed interface Lectura {
            data class Ok(val importado: ResultadoImportacionM3U) : Lectura

            data class Fallo(val codigo: String) : Lectura
        }

        private companion object {
            const val CAUSA_MANUAL = "manual"
            const val CAUSA_AUTOMATICA = "automatica"
            const val VERSION_IMPORTACION = "m3u:v1"
            const val VERSION_EVIDENCIA = "reconciliacion:v1"
            const val RESULTADO_COMPLETADA = "completada"
            const val RESULTADO_FALLIDA = "fallida"
            const val ESTADO_ACTIVA = "activa"
            const val ESTADO_AUSENTE = "ausente"
            const val TEXTO_AUSENTE = "La Fuente no declaró el Canal en esta Actualización"

            /** Filas por página de la carga *keyset* de identidades históricas. */
            const val LOTE_LECTURA = 500

            /** Filas por lote de escritura y por `IN` de SQLite. */
            const val LOTE_ESCRITURA = 500

            const val ERROR_LISTA_INEXISTENTE = "lista_inexistente"
            const val ERROR_FUENTE_INEXISTENTE = "fuente_inexistente"
            const val ERROR_UBICACION_INVALIDA = "ubicacion_invalida"
            const val ERROR_ESQUEMA_NO_ADMITIDO = "esquema_no_admitido"
            const val ERROR_ARCHIVO_INACCESIBLE = "archivo_inaccesible"
            const val ERROR_SIN_CONEXION = "sin_conexion"
            const val ERROR_TIMEOUT = "timeout"
            const val ERROR_HTTP_4XX = "http_4xx"
            const val ERROR_HTTP_5XX = "http_5xx"
            const val ERROR_DNS = "dns_no_resuelto"
            const val ERROR_CONEXION_RECHAZADA = "conexion_rechazada"
            const val ERROR_BLOQUEO_HTTP = "bloqueo_http"
            const val ERROR_INDICE_HMAC_NO_OPERATIVO = "indice_hmac_no_operativo"
            const val ERROR_SIN_CANALES = "sin_canales"
            const val ERROR_ACTUALIZACION_FALLIDA = "actualizacion_fallida"
        }
    }
