package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.ReconstruccionHmacDao
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.hmac.DecisionReconstruccion
import com.copernicotv.core.hmac.EstadoIndiceHmac
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.HmacPendiente
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.MotivoTransicion
import com.copernicotv.core.hmac.PlanReconstruccionHmac
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.hmac.VersionOrigenHmac
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.time.Instant
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

/**
 * Pasada de reconstrucción del índice HMAC (DC-04 §7, E2-T6).
 *
 * Recorre los valores HMAC persistidos de `Canal` y `Lista`, decide con
 * [PlanReconstruccionHmac] y, cuando corresponde, descifra la URL original, la
 * canonicaliza y recalcula el sobre con la generación objetivo conservando el
 * `cvM` del sobre de origen. Las filas no reconstruibles se registran en
 * `HmacPendiente` con su motivo tipado.
 *
 * La pasada recorre todos los lotes antes de cerrar, para que el usuario vea de
 * una vez todas las filas no reconstruibles. El estado solo se persiste al
 * final: `bloqueado_parcial` si hay pendientes, `operativo` si todas las filas
 * quedaron en la generación objetivo. Es idempotente —un valor ya reconstruido
 * se detecta como [DecisionReconstruccion.YaActualizada] y no se reescribe— y
 * reanudable: una nueva ejecución recalcula desde el principio sin volver a
 * descifrar lo ya actualizado.
 *
 * No borra Canales ni Listas, no fusiona identidades y no compara HMAC entre
 * generaciones ni entre `cvM` distintos.
 */
@Singleton
class ReconstructorIndiceHmac
    @Inject
    constructor(
        private val db: AppDatabase,
        private val indice: IndiceHmacRepositorio,
        private val claves: ClavesApp,
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
                val dao = db.reconstruccionHmacDao()
                val maximoCanal = dao.maximoIdCanal()
                val maximoLista = dao.maximoIdLista()
                val contadores =
                    Contadores(
                        total =
                            dao.contarCanales(maximoCanal) +
                                dao.contarCanalesConLogo(maximoCanal) +
                                dao.contarListas(maximoLista),
                    )
                pasadaCanales(dao, maximoCanal, generacionObjetivo, ahora, tamanoLote, contadores)
                pasadaListas(dao, maximoLista, generacionObjetivo, ahora, tamanoLote, contadores)
                cerrar(contadores, ahora)
            }

        private suspend fun pasadaCanales(
            dao: ReconstruccionHmacDao,
            maximoId: Long,
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
                    procesarCanal(dao, canal, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarCanal(
            dao: ReconstruccionHmacDao,
            canal: CanalEntity,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val url =
                procesar(
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
                    generacionObjetivo,
                    ahora,
                )
            if (url is ResultadoValor.Reconstruida) {
                dao.actualizarUrlStreamHmac(canal.id, url.sobre)
            }
            contadores.registrar(url)
            procesarLogo(dao, canal, generacionObjetivo, ahora, contadores)
        }

        private suspend fun procesarLogo(
            dao: ReconstruccionHmacDao,
            canal: CanalEntity,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val logoHmac = canal.logoUrlHmac ?: return
            val logo =
                procesar(
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
                    procesarLista(dao, lista, generacionObjetivo, ahora, contadores)
                }
                ultimoId = lote.last().id
                if (lote.size < tamanoLote) return
            }
        }

        private suspend fun procesarLista(
            dao: ReconstruccionHmacDao,
            lista: ListaEntity,
            generacionObjetivo: Int,
            ahora: Instant,
            contadores: Contadores,
        ) {
            val ubicacion =
                procesar(
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
                    generacionObjetivo,
                    ahora,
                )
            if (ubicacion is ResultadoValor.Reconstruida) {
                dao.actualizarUbicacionHmac(lista.id, ubicacion.sobre)
            }
            contadores.registrar(ubicacion)
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

        /**
         * Decide y, si procede, reconstruye un valor HMAC.
         *
         * Devuelve el sobre nuevo cuando hay que escribirlo; no toca la base.
         */
        private suspend fun procesar(
            valor: ValorHmac,
            generacionObjetivo: Int,
            ahora: Instant,
        ): ResultadoValor {
            val texto = valor.sobre.toString(Charsets.UTF_8)
            return when (val decision = PlanReconstruccionHmac.decidir(texto, generacionObjetivo)) {
                DecisionReconstruccion.SinValor -> ResultadoValor.SinValor
                DecisionReconstruccion.YaActualizada -> {
                    indice.eliminarPendiente(
                        valor.identidad.entidad,
                        valor.identidad.entidadId,
                        valor.identidad.columna,
                    )
                    ResultadoValor.YaActualizada
                }

                is DecisionReconstruccion.Pendiente -> {
                    registrarPendiente(valor.identidad, decision.versionOrigen, decision.motivo, ahora)
                    ResultadoValor.Pendiente
                }

                is DecisionReconstruccion.Reconstruir ->
                    reconstruir(valor, decision.versionCanonica, generacionObjetivo, ahora)
            }
        }

        private suspend fun reconstruir(
            valor: ValorHmac,
            versionCanonica: Int,
            generacionObjetivo: Int,
            ahora: Instant,
        ): ResultadoValor {
            val cifrado = valor.cifrado
            if (cifrado == null) {
                registrarPendiente(
                    valor.identidad,
                    VersionOrigenHmac.Cv(versionCanonica),
                    MotivoHmacPendiente.VALOR_ORIGINAL_AUSENTE,
                    ahora,
                )
                return ResultadoValor.Pendiente
            }
            val descifrado = CifradoAes.descifrar(claves.claveAes, valor.identidad.aad, cifrado)
            if (descifrado is ResultadoDescifrado.Fallo) {
                val motivo =
                    PlanReconstruccionHmac.motivoPorDescifrado(descifrado)
                        ?: MotivoHmacPendiente.CIPHERTEXT_CORRUPTO
                registrarPendiente(valor.identidad, VersionOrigenHmac.Cv(versionCanonica), motivo, ahora)
                return ResultadoValor.Pendiente
            }
            val original = String((descifrado as ResultadoDescifrado.Ok).bytes, Charsets.UTF_8)
            val canonica = CanonicalizadorUrl.canonizar(original, valor.identidad.campo)
            if (canonica !is ResultadoCanonizacion.Ok) {
                registrarPendiente(
                    valor.identidad,
                    VersionOrigenHmac.Cv(versionCanonica),
                    MotivoHmacPendiente.CANONICALIZACION_FALLIDA,
                    ahora,
                )
                return ResultadoValor.Pendiente
            }
            val sobre =
                HmacUrl
                    .calcularSobre(
                        clave = claves.claveHmac,
                        generacion = generacionObjetivo,
                        campo = valor.identidad.campo,
                        clase = canonica.clase,
                        urlCanonica = canonica.urlCanonica,
                        versionCanonica = versionCanonica,
                    ).toByteArray(Charsets.UTF_8)
            indice.eliminarPendiente(
                valor.identidad.entidad,
                valor.identidad.entidadId,
                valor.identidad.columna,
            )
            return ResultadoValor.Reconstruida(sobre)
        }

        private suspend fun registrarPendiente(
            identidad: IdentidadHmac,
            versionOrigen: VersionOrigenHmac,
            motivo: MotivoHmacPendiente,
            ahora: Instant,
        ) {
            indice.registrarPendiente(
                HmacPendiente(
                    entidad = identidad.entidad,
                    entidadId = identidad.entidadId,
                    columna = identidad.columna,
                    versionOrigen = versionOrigen,
                    motivo = motivo,
                ),
                ahora,
            )
        }

        /** Identidad de una columna HMAC persistida. */
        private data class IdentidadHmac(
            val entidad: String,
            val entidadId: Long,
            val columna: String,
            val campo: CampoUrl,
            val aad: String,
        )

        /** Valor persistido que se está evaluando. */
        private data class ValorHmac(
            val sobre: ByteArray,
            val cifrado: ByteArray?,
            val identidad: IdentidadHmac,
        )

        /** Resultado por valor evaluado. */
        private sealed interface ResultadoValor {
            /** Hay que escribir el sobre nuevo. */
            data class Reconstruida(val sobre: ByteArray) : ResultadoValor

            /** Ya estaba en la generación objetivo. */
            data object YaActualizada : ResultadoValor

            /** Quedó registrado en `HmacPendiente`. */
            data object Pendiente : ResultadoValor

            /** No había valor persistido. */
            data object SinValor : ResultadoValor
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
            const val COLUMNA_URL_STREAM = "urlStreamHmac"
            const val COLUMNA_LOGO = "logoUrlHmac"
            const val COLUMNA_UBICACION = "ubicacionHmac"
            const val AAD_URL_STREAM = "Canal.url_stream_cifrada"
            const val AAD_LOGO = "Canal.logo_url_cifrada"
            const val AAD_UBICACION = "Lista.ubicacion_cifrada"
        }
    }
