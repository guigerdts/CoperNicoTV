package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.ReconstruccionHmacDao
import com.copernicotv.app.data.db.entities.CacheM3UEntity
import com.copernicotv.app.data.db.entities.CacheXmltvEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.ObservacionPendienteImportacionEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.hmac.EstadoIndiceHmac
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.MotivoTransicion
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
import javax.crypto.SecretKey
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado de una pasada de reconstrucción del índice HMAC. */
sealed interface ResultadoReconstruccion {
    /** La pasada terminó; [estado] es el estado persistido al cerrarla. */
    data class Ok(
        val estado: EstadoIndiceHmac,
        val reconstruidas: Long,
        val pendientes: Long,
    ) : ResultadoReconstruccion

    /** La pasada no se ejecutó o una transición la rechazó. */
    data class Rechazada(val motivo: MotivoTransicion) : ResultadoReconstruccion
}

/** Resultado de un reingreso funcional de fila pendiente (DC-04 §7, Q3). */
sealed interface ResultadoReingreso {
    /** El valor reingresado quedó sellado; [estado] es el estado persistido tras resolverlo. */
    data class Ok(val estado: EstadoIndiceHmac) : ResultadoReingreso

    /** El reingreso no procede; la fila pendiente sigue pendiente. */
    data class Rechazada(val motivo: String) : ResultadoReingreso
}

/** Motivos tipados por los que un reingreso no procede. */
enum class MotivoReingreso(val codigo: String) {
    COLUMNA_DESCONOCIDA("columna_desconocida"),
    FASE_INVALIDA("fase_invalida"),
    CANONICA_INVALIDA("canonica_invalida"),
    FILA_DESAPARECIDA("fila_desaparecida"),
}

/**
 * Pasada de reconstrucción del índice HMAC (DC-04 §7, E2-T6).
 *
 * Recorre los valores HMAC persistidos de las ocho columnas contractuales del
 * esquema v1 (DC-04): `Canal` (URL y logo), `Lista` (ubicación),
 * `ObservacionPendienteImportacion` (URL y logo), `CacheM3U`, `CacheXmltv` y
 * `FuenteGuia`; decide con ReconstruccionValorHmac y, cuando corresponde,
 * descifra la URL original, la canonicaliza y recalcula el sobre con la
 * generación objetivo conservando el `cvM` del sobre de origen. Las filas no
 * reconstruibles se registran en `HmacPendiente` con su motivo tipado.
 *
 * Las columnas de `ObservacionPendienteImportacion` se reconstruyen con los
 * campos `CANAL_URL_STREAM`/`CANAL_LOGO_URL`, exactamente como los sella la
 * escritura: el sobre de una observación es un sobre de canal pendiente de
 * promover, y reconstruirlo con otro `campo_id` rompería la promoción,
 * moviendo un valor entre columnas del contrato.
 *
 * La pasada recorre todos los lotes antes de cerrar, para que el usuario vea de
 * una vez todas las filas no reconstruibles. El estado solo se persiste al
 * final: `bloqueado_parcial` si hay pendientes, `operativo` si todas las filas
 * quedaron en la generación objetivo. Es idempotente —un valor ya reconstruido
 * se detecta como ya actualizada y no se reescribe— y
 * reanudable: una nueva ejecución recalcula desde el principio sin volver a
 * descifrar lo ya actualizado.
 *
 * No borra Canales ni Listas, no fusiona identidades y no compara HMAC entre
 * generaciones ni entre `cvM` distintos.
 */
@Singleton
internal class ReconstructorIndiceHmac
    @Inject
    constructor(
        private val db: AppDatabase,
        private val indice: IndiceHmacRepositorio,
        private val claves: ClavesApp,
        private val valores: ReconstruccionValorHmac,
    ) {
        /**
         * Ejecuta una pasada completa.
         *
         * Solo procede desde `recuperacion_en_curso`; en cualquier otra fase
         * devuelve [ResultadoReconstruccion.Rechazada] sin escribir nada.
         */
        suspend fun reconstruir(
            ahora: Instant = Instant.now(),
            tamanoLote: Int = TAMANO_LOTE,
        ): ResultadoReconstruccion =
            withContext(Dispatchers.IO) {
                require(tamanoLote > 0) { "tamaño de lote inválido" }
                val estado = indice.estado(ahora)
                if (estado.fase != FaseIndiceHmac.RECUPERACION_EN_CURSO) {
                    return@withContext ResultadoReconstruccion.Rechazada(
                        MotivoTransicion.TRANSICION_NO_PERMITIDA,
                    )
                }
                val generacionObjetivo =
                    requireNotNull(estado.generacionObjetivo) {
                        "la reconstrucción en curso exige generación objetivo"
                    }
                // Incorporación explícita de la clave de la generación objetivo
                // (DC-04 §7, Q2): solo desde `recuperacion_en_curso`, la fase
                // que la transición `iniciarReconstruccion` aprueba. Es
                // idempotente: una pasada reanudada reutiliza la misma clave.
                val claveObjetivo = claves.crearClaveHmac(generacionObjetivo)
                val dao = db.reconstruccionHmacDao()
                val maximoCanal = dao.maximoIdCanal()
                val maximoLista = dao.maximoIdLista()
                val maximoObservacion = dao.maximoIdObservaciones()
                val maximoCacheM3u = dao.maximoIdCacheM3u()
                val maximoCacheXmltv = dao.maximoIdCacheXmltv()
                val maximoFuenteGuia = dao.maximoIdFuentesGuia()
                val contadores =
                    Contadores(
                        total =
                            dao.contarCanales(maximoCanal) +
                                dao.contarCanalesConLogo(maximoCanal) +
                                dao.contarListas(maximoLista) +
                                dao.contarObservaciones(maximoObservacion) +
                                dao.contarObservacionesConLogo(maximoObservacion) +
                                dao.contarCacheM3u(maximoCacheM3u) +
                                dao.contarCacheXmltv(maximoCacheXmltv) +
                                dao.contarFuentesGuia(maximoFuenteGuia),
                    )
                pasadaCanales(dao, maximoCanal, claveObjetivo, generacionObjetivo, ahora, tamanoLote, contadores)
                pasadaListas(dao, maximoLista, claveObjetivo, generacionObjetivo, ahora, tamanoLote, contadores)
                pasadaObservaciones(dao, maximoObservacion, claveObjetivo, generacionObjetivo, ahora, tamanoLote, contadores)
                pasadaCacheM3u(dao, maximoCacheM3u, claveObjetivo, generacionObjetivo, ahora, tamanoLote, contadores)
                pasadaCacheXmltv(dao, maximoCacheXmltv, claveObjetivo, generacionObjetivo, ahora, tamanoLote, contadores)
                pasadaFuentesGuia(dao, maximoFuenteGuia, claveObjetivo, generacionObjetivo, ahora, tamanoLote, contadores)
                cerrar(contadores, ahora)
            }

        /**
         * Reingreso funcional de una fila pendiente (DC-04 §7, Q3).
         *
         * Registra de nuevo el valor original de la URL (reingresado por el
         * usuario fuera de la UI): se canonicaliza con el campo de la columna,
         * se vuelve a cifrar (IV nuevo) y se sella el sobre con la generación
         * objetivo. Solo procede con la reconstrucción abierta
         * (`recuperacion_en_curso` o `bloqueado_parcial`); sin un valor
         * canonicalizable devuelve un rechazo tipado y la fila sigue pendiente.
         *
         * Cuando el reingreso resuelve la última fila pendiente, corrige los
         * conteos y cierra la reconstrucción con `resolverPendientes`.
         */
        suspend fun reingresar(
            entidad: String,
            entidadId: Long,
            columna: String,
            valorOriginal: String,
            ahora: Instant = Instant.now(),
        ): ResultadoReingreso =
            withContext(Dispatchers.IO) {
                val definicion =
                    definiciones(columna).firstOrNull { it.entidad == entidad }
                        ?: return@withContext ResultadoReingreso.Rechazada(MotivoReingreso.COLUMNA_DESCONOCIDA.codigo)
                val estado = indice.estado(ahora)
                if (estado.fase != FaseIndiceHmac.RECUPERACION_EN_CURSO &&
                    estado.fase != FaseIndiceHmac.BLOQUEADO_PARCIAL
                ) {
                    return@withContext ResultadoReingreso.Rechazada(MotivoReingreso.FASE_INVALIDA.codigo)
                }
                val generacionObjetivo =
                    estado.generacionObjetivo
                        ?: return@withContext ResultadoReingreso.Rechazada(MotivoReingreso.FASE_INVALIDA.codigo)
                // Incorporación explícita e idempotente de la clave objetivo;
                // la fase de reconstrucción abierta es la única que la pide.
                val claveObjetivo = claves.crearClaveHmac(generacionObjetivo)
                val canonica =
                    CanonicalizadorUrl.canonizar(valorOriginal, definicion.campo)
                if (canonica !is ResultadoCanonizacion.Ok) {
                    return@withContext ResultadoReingreso.Rechazada(MotivoReingreso.CANONICA_INVALIDA.codigo)
                }
                // El valor reingresado se restaura completo: nuevo cifrado con
                // IV nuevo (misma clave AES, Q1) y sobre nuevo con la clave de
                // la generación objetivo.
                val cifrado =
                    CifradoAes.cifrar(
                        claves.claveAes,
                        definicion.aad,
                        canonica.urlCanonica.toByteArray(Charsets.UTF_8),
                    )
                val sobre =
                    HmacUrl
                        .calcularSobre(
                            clave = claveObjetivo,
                            generacion = generacionObjetivo,
                            campo = definicion.campo,
                            clase = canonica.clase,
                            urlCanonica = canonica.urlCanonica,
                        ).toByteArray(Charsets.UTF_8)
                val escritura = definicion.escritura(db.reconstruccionHmacDao(), entidadId, cifrado, sobre)
                if (escritura == 0) {
                    return@withContext ResultadoReingreso.Rechazada(MotivoReingreso.FILA_DESAPARECIDA.codigo)
                }
                indice.eliminarPendiente(entidad, entidadId, columna)
                val saldoPendientes = indice.contarPendientes()
                val estadoFinal =
                    if (saldoPendientes == 0L && estado.fase == FaseIndiceHmac.BLOQUEADO_PARCIAL) {
                        // Última fila resuelta: los conteos del cierre son el
                        // inventario real persistido; exigir reconstruidas ==
                        // totales con 0 pendientes cierra la reconstrucción.
                        when (val cierre = indice.resolverPendientes(estado.filasTotales, estado.filasTotales, 0L, ahora)) {
                            is ResultadoTransicion.Ok -> cierre.estado
                            is ResultadoTransicion.Rechazada -> estado
                        }
                    } else {
                        estado
                    }
                ResultadoReingreso.Ok(estadoFinal)
            }

        /**
         * Definición de la escritura de reingreso para una columna: el campo
         * con el que se canonicaliza y sella, el AAD del cifrado AES y la
         * escritura DAO que restaura cifrado y sobre juntos.
         */
        private data class DefinicionColumna(
            val entidad: String,
            val campo: CampoUrl,
            val aad: String,
            val escritura: suspend (ReconstruccionHmacDao, Long, ByteArray, ByteArray) -> Int,
        )

        /**
         * Columnas contractuales que admiten reingreso, agrupadas por el
         * nombre de columna persistido en `HmacPendiente`. Las columnas
         * `urlHmac` (cuatro entidades) y `logoUrlHmac` (Canal y observación)
         * son compartidas, por lo que el llamador filtra también por
         * `entidad`.
         */
        private fun definiciones(columna: String): List<DefinicionColumna> =
            when (columna) {
                COLUMNA_URL_STREAM ->
                    listOf(
                        DefinicionColumna(
                            entidad = ENTIDAD_CANAL,
                            campo = CampoUrl.CANAL_URL_STREAM,
                            aad = AAD_URL_STREAM,
                            escritura = { dao, id, cifrado, hmac -> dao.reingresarUrlCanal(id, cifrado, hmac) },
                        ),
                    )

                COLUMNA_LOGO ->
                    listOf(
                        DefinicionColumna(
                            entidad = ENTIDAD_CANAL,
                            campo = CampoUrl.CANAL_LOGO_URL,
                            aad = AAD_LOGO,
                            escritura = { dao, id, cifrado, hmac -> dao.reingresarLogoCanal(id, cifrado, hmac) },
                        ),
                        DefinicionColumna(
                            entidad = ENTIDAD_OBSERVACION,
                            campo = CampoUrl.CANAL_LOGO_URL,
                            aad = AAD_OBSERVACION_LOGO,
                            escritura = { dao, id, cifrado, hmac ->
                                dao.reingresarLogoObservacion(id, cifrado, hmac)
                            },
                        ),
                    )

                COLUMNA_UBICACION ->
                    listOf(
                        DefinicionColumna(
                            entidad = ENTIDAD_LISTA,
                            campo = CampoUrl.LISTA_UBICACION,
                            aad = AAD_UBICACION,
                            escritura = { dao, id, cifrado, hmac -> dao.reingresarUbicacionLista(id, cifrado, hmac) },
                        ),
                    )

                COLUMNA_OBSERVACION_URL ->
                    listOf(
                        DefinicionColumna(
                            entidad = ENTIDAD_OBSERVACION,
                            campo = CampoUrl.CANAL_URL_STREAM,
                            aad = AAD_OBSERVACION_URL,
                            escritura = { dao, id, cifrado, hmac ->
                                dao.reingresarUrlObservacion(id, cifrado, hmac)
                            },
                        ),
                        DefinicionColumna(
                            entidad = ENTIDAD_CACHE_M3U,
                            campo = CampoUrl.CACHE_M3U_URL,
                            aad = AAD_CACHE_M3U,
                            escritura = { dao, id, cifrado, hmac ->
                                dao.reingresarUrlCacheM3u(id, cifrado, hmac)
                            },
                        ),
                        DefinicionColumna(
                            entidad = ENTIDAD_CACHE_XMLTV,
                            campo = CampoUrl.CACHE_XMLTV_URL,
                            aad = AAD_CACHE_XMLTV,
                            escritura = { dao, id, cifrado, hmac ->
                                dao.reingresarUrlCacheXmltv(id, cifrado, hmac)
                            },
                        ),
                        DefinicionColumna(
                            entidad = ENTIDAD_FUENTE_GUIA,
                            campo = CampoUrl.FUENTE_GUIA_URL,
                            aad = AAD_FUENTE_GUIA,
                            escritura = { dao, id, cifrado, hmac ->
                                dao.reingresarUrlFuenteGuia(id, cifrado, hmac)
                            },
                        ),
                    )

                else -> emptyList()
            }

        private suspend fun pasadaCanales(
            dao: ReconstruccionHmacDao,
            maximoId: Long,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            tamanoLote: Int,
            contadores: Contadores,
        ) {
            var ultimoId = 0L
            while (true) {
                val lote = dao.loteCanales(ultimoId, maximoId, tamanoLote)
                if (lote.isEmpty()) return
                for (canal in lote) {
                    procesarCanal(dao, canal, claveObjetivo, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarCanal(
            dao: ReconstruccionHmacDao,
            canal: CanalEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val url =
                valores.procesar(
                    ValorHmac(
                        sobre = canal.urlStreamHmac,
                        cifrado = canal.urlStreamCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_CANAL,
                                entidadId = canal.id,
                                columna = COLUMNA_URL_STREAM,
                                campo = CampoUrl.CANAL_URL_STREAM,
                                aad = AAD_URL_STREAM,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (url is ResultadoValor.Reconstruida) {
                dao.actualizarUrlStreamHmac(canal.id, url.sobre)
            }
            contadores.registrar(url)
            procesarLogo(dao, canal, claveObjetivo, generacionObjetivo, ahora, contadores)
        }

        private suspend fun procesarLogo(
            dao: ReconstruccionHmacDao,
            canal: CanalEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val logoHmac = canal.logoUrlHmac ?: return
            val logo =
                valores.procesar(
                    ValorHmac(
                        sobre = logoHmac,
                        cifrado = canal.logoUrlCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_CANAL,
                                entidadId = canal.id,
                                columna = COLUMNA_LOGO,
                                campo = CampoUrl.CANAL_LOGO_URL,
                                aad = AAD_LOGO,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (logo is ResultadoValor.Reconstruida) {
                dao.actualizarLogoHmac(canal.id, logo.sobre)
            }
            contadores.registrar(logo)
        }

        private suspend fun pasadaListas(
            dao: ReconstruccionHmacDao,
            maximoId: Long,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            tamanoLote: Int,
            contadores: Contadores,
        ) {
            var ultimoId = 0L
            while (true) {
                val lote = dao.loteListas(ultimoId, maximoId, tamanoLote)
                if (lote.isEmpty()) return
                for (lista in lote) {
                    procesarLista(dao, lista, claveObjetivo, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarLista(
            dao: ReconstruccionHmacDao,
            lista: ListaEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val ubicacion =
                valores.procesar(
                    ValorHmac(
                        sobre = lista.ubicacionHmac,
                        cifrado = lista.ubicacionCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_LISTA,
                                entidadId = lista.id,
                                columna = COLUMNA_UBICACION,
                                campo = CampoUrl.LISTA_UBICACION,
                                aad = AAD_UBICACION,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (ubicacion is ResultadoValor.Reconstruida) {
                dao.actualizarUbicacionHmac(lista.id, ubicacion.sobre)
            }
            contadores.registrar(ubicacion)
        }

        /**
         * Pasada de `ObservacionPendienteImportacion`: URL y logo.
         *
         * Los sobres se reconstruyen con el mismo campo con el que se sellaron
         * (`CANAL_URL_STREAM`/`CANAL_LOGO_URL`): son sobres de canal a la
         * espera de promoción, no sobres del campo OBSERVACION_URL.
         */
        private suspend fun pasadaObservaciones(
            dao: ReconstruccionHmacDao,
            maximoId: Long,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            tamanoLote: Int,
            contadores: Contadores,
        ) {
            var ultimoId = 0L
            while (true) {
                val lote = dao.loteObservaciones(ultimoId, maximoId, tamanoLote)
                if (lote.isEmpty()) return
                for (observacion in lote) {
                    procesarObservacionUrl(dao, observacion, claveObjetivo, generacionObjetivo, ahora, contadores)
                    procesarObservacionLogo(dao, observacion, claveObjetivo, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarObservacionUrl(
            dao: ReconstruccionHmacDao,
            observacion: ObservacionPendienteImportacionEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val urlHmac = observacion.urlHmac ?: return
            val resultado =
                valores.procesar(
                    ValorHmac(
                        sobre = urlHmac,
                        cifrado = observacion.urlCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_OBSERVACION,
                                entidadId = observacion.id,
                                columna = COLUMNA_OBSERVACION_URL,
                                campo = CampoUrl.CANAL_URL_STREAM,
                                aad = AAD_OBSERVACION_URL,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (resultado is ResultadoValor.Reconstruida) {
                dao.actualizarUrlObservacion(observacion.id, resultado.sobre)
            }
            contadores.registrar(resultado)
        }

        private suspend fun procesarObservacionLogo(
            dao: ReconstruccionHmacDao,
            observacion: ObservacionPendienteImportacionEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val logoHmac = observacion.logoUrlHmac ?: return
            val resultado =
                valores.procesar(
                    ValorHmac(
                        sobre = logoHmac,
                        cifrado = observacion.logoUrlCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_OBSERVACION,
                                entidadId = observacion.id,
                                columna = COLUMNA_LOGO,
                                campo = CampoUrl.CANAL_LOGO_URL,
                                aad = AAD_OBSERVACION_LOGO,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (resultado is ResultadoValor.Reconstruida) {
                dao.actualizarLogoObservacion(observacion.id, resultado.sobre)
            }
            contadores.registrar(resultado)
        }

        private suspend fun pasadaCacheM3u(
            dao: ReconstruccionHmacDao,
            maximoId: Long,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            tamanoLote: Int,
            contadores: Contadores,
        ) {
            var ultimoId = 0L
            while (true) {
                val lote = dao.loteCacheM3u(ultimoId, maximoId, tamanoLote)
                if (lote.isEmpty()) return
                for (fila in lote) {
                    procesarUrlCacheM3u(dao, fila, claveObjetivo, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarUrlCacheM3u(
            dao: ReconstruccionHmacDao,
            fila: CacheM3UEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val resultado =
                valores.procesar(
                    ValorHmac(
                        sobre = fila.urlHmac,
                        cifrado = fila.urlCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_CACHE_M3U,
                                entidadId = fila.id,
                                columna = COLUMNA_URL,
                                campo = CampoUrl.CACHE_M3U_URL,
                                aad = AAD_CACHE_M3U,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (resultado is ResultadoValor.Reconstruida) {
                dao.actualizarUrlCacheM3u(fila.id, resultado.sobre)
            }
            contadores.registrar(resultado)
        }

        private suspend fun pasadaCacheXmltv(
            dao: ReconstruccionHmacDao,
            maximoId: Long,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            tamanoLote: Int,
            contadores: Contadores,
        ) {
            var ultimoId = 0L
            while (true) {
                val lote = dao.loteCacheXmltv(ultimoId, maximoId, tamanoLote)
                if (lote.isEmpty()) return
                for (fila in lote) {
                    procesarUrlCacheXmltv(dao, fila, claveObjetivo, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarUrlCacheXmltv(
            dao: ReconstruccionHmacDao,
            fila: CacheXmltvEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val resultado =
                valores.procesar(
                    ValorHmac(
                        sobre = fila.urlHmac,
                        cifrado = fila.urlCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_CACHE_XMLTV,
                                entidadId = fila.id,
                                columna = COLUMNA_URL,
                                campo = CampoUrl.CACHE_XMLTV_URL,
                                aad = AAD_CACHE_XMLTV,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (resultado is ResultadoValor.Reconstruida) {
                dao.actualizarUrlCacheXmltv(fila.id, resultado.sobre)
            }
            contadores.registrar(resultado)
        }

        private suspend fun pasadaFuentesGuia(
            dao: ReconstruccionHmacDao,
            maximoId: Long,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            tamanoLote: Int,
            contadores: Contadores,
        ) {
            var ultimoId = 0L
            while (true) {
                val lote = dao.loteFuentesGuia(ultimoId, maximoId, tamanoLote)
                if (lote.isEmpty()) return
                for (fila in lote) {
                    procesarUrlFuenteGuia(dao, fila, claveObjetivo, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarUrlFuenteGuia(
            dao: ReconstruccionHmacDao,
            fila: FuenteGuiaEntity,
            claveObjetivo: SecretKey,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val urlHmac = fila.urlHmac ?: return
            val resultado =
                valores.procesar(
                    ValorHmac(
                        sobre = urlHmac,
                        cifrado = fila.urlCifrada,
                        identidad =
                            IdentidadHmac(
                                entidad = ENTIDAD_FUENTE_GUIA,
                                entidadId = fila.id,
                                columna = COLUMNA_URL,
                                campo = CampoUrl.FUENTE_GUIA_URL,
                                aad = AAD_FUENTE_GUIA,
                            ),
                    ),
                    claveObjetivo,
                    generacionObjetivo,
                    ahora,
                )
            if (resultado is ResultadoValor.Reconstruida) {
                dao.actualizarUrlFuenteGuia(fila.id, resultado.sobre)
            }
            contadores.registrar(resultado)
        }

        private suspend fun cerrar(
            contadores: Contadores,
            ahora: Instant,
        ): ResultadoReconstruccion {
            val avance =
                indice.registrarAvance(
                    contadores.total,
                    contadores.reconstruidas,
                    contadores.pendientes,
                    ahora,
                )
            return when (avance) {
                is ResultadoTransicion.Ok ->
                    ResultadoReconstruccion.Ok(
                        avance.estado,
                        contadores.reconstruidas,
                        contadores.pendientes,
                    )

                is ResultadoTransicion.Rechazada -> ResultadoReconstruccion.Rechazada(avance.motivo)
            }
        }

        /** Contadores acumulados de la pasada. */
        private class Contadores(
            val total: Long,
        ) {
            var reconstruidas: Long = 0
            var pendientes: Long = 0

            fun registrar(resultado: ResultadoValor) {
                when (resultado) {
                    is ResultadoValor.Reconstruida, ResultadoValor.YaActualizada -> reconstruidas++
                    ResultadoValor.Pendiente -> pendientes++
                    ResultadoValor.SinValor -> Unit
                }
            }
        }

        private companion object {
            const val TAMANO_LOTE = 200
            const val ENTIDAD_CANAL = "Canal"
            const val ENTIDAD_LISTA = "Lista"
            const val ENTIDAD_OBSERVACION = "ObservacionPendienteImportacion"
            const val ENTIDAD_CACHE_M3U = "CacheM3U"
            const val ENTIDAD_CACHE_XMLTV = "CacheXmltv"
            const val ENTIDAD_FUENTE_GUIA = "FuenteGuia"
            const val COLUMNA_URL_STREAM = "urlStreamHmac"
            const val COLUMNA_LOGO = "logoUrlHmac"
            const val COLUMNA_UBICACION = "ubicacionHmac"
            const val COLUMNA_OBSERVACION_URL = "urlHmac"
            const val COLUMNA_URL = "urlHmac"
            const val AAD_URL_STREAM = "Canal.url_stream_cifrada"
            const val AAD_LOGO = "Canal.logo_url_cifrada"
            const val AAD_UBICACION = "Lista.ubicacion_cifrada"
            const val AAD_OBSERVACION_URL = "ObservacionPendienteImportacion.url_cifrada"
            const val AAD_OBSERVACION_LOGO = "ObservacionPendienteImportacion.logo_url_cifrada"
            const val AAD_CACHE_M3U = "CacheM3U.url_cifrada"
            const val AAD_CACHE_XMLTV = "CacheXmltv.url_cifrada"
            const val AAD_FUENTE_GUIA = "FuenteGuia.url_cifrada"
        }
    }
