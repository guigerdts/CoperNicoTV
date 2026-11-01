package com.copernicotv.app.data

import android.database.sqlite.SQLiteConstraintException
import androidx.room.withTransaction
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ObservacionPendienteImportacionEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.ResultadoDescifrado
import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.ClavePresentacion
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.identity.ResultadoTvgId
import com.copernicotv.core.identity.ValidadorTvgId
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Resultado de resolver una Observación pendiente de importación (E4-T3). */
sealed interface ResultadoResolucionObservacion {
    /** La declaración se confirmó como Canal nuevo. */
    data class CanalPromovido(
        val canalId: Long,
    ) : ResultadoResolucionObservacion

    /** La declaración se asignó como evidencia a una fila existente. */
    data object Asignada : ResultadoResolucionObservacion

    /** La Observación se descartó por decisión explícita. */
    data object Descartada : ResultadoResolucionObservacion

    /** La resolución no se aplicó; la Observación se conserva. */
    data class Rechazada(
        val codigo: String,
    ) : ResultadoResolucionObservacion
}

/**
 * Ciclo de vida de las Observaciones pendientes de importación (E4-T3, m6/m7).
 *
 * Una Observación no es un Canal: existe antes de toda identidad confirmada y
 * solo se resuelve por acción explícita del usuario. Las tres resoluciones son
 * (a) asignarla como evidencia a una fila, (b) confirmarla como Canal nuevo y
 * (c) descartarla. Ninguna se ejecuta de forma implícita.
 *
 * Cada resolución corre en una única transacción. Si la clave final choca con
 * una fila ajena, la transacción se revierte y la Observación se conserva.
 *
 * Las columnas URL de la Observación se cifran con su propio AAD (DC-04). Al
 * promover o asignar, el valor se descifra y se vuelve a cifrar con el AAD de
 * `Canal`, porque el AAD impide mover un sobre entre columnas. Si el sobre no
 * se puede descifrar, la resolución se rechaza y nada cambia.
 */
@Singleton
class ObservacionRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
    ) {
        private val escritorCanales = CanalEscritor(db)
        private val escritorApariencias = AparienciaEscritor(db)

        /** Observaciones pendientes de una Fuente, en orden de primera observación. */
        suspend fun listar(fuenteId: Long): List<ObservacionPendienteImportacionEntity> = db.observacionPendienteDao().porFuente(fuenteId)

        /** Descarta una Observación por decisión explícita. */
        suspend fun descartar(observacionId: Long): ResultadoResolucionObservacion =
            db.withTransaction {
                val eliminadas = db.observacionPendienteDao().eliminar(observacionId)
                if (eliminadas > 0) {
                    ResultadoResolucionObservacion.Descartada
                } else {
                    ResultadoResolucionObservacion.Rechazada(CODIGO_OBSERVACION_NO_EXISTE)
                }
            }

        /**
         * Asigna la declaración observada como evidencia de [canalId].
         *
         * La fila adopta la identidad de la declaración y vuelve a A. Las demás
         * filas del conjunto conservan su estado. La Observación se elimina
         * únicamente si la transacción completa tiene éxito.
         */
        suspend fun asignarComoEvidencia(
            observacionId: Long,
            canalId: Long,
        ): ResultadoResolucionObservacion =
            resolverConConflicto {
                db.withTransaction {
                    val observacion =
                        db.observacionPendienteDao().porId(observacionId)
                            ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(
                                CODIGO_OBSERVACION_NO_EXISTE,
                            )
                    val canal =
                        db.canalDao().porId(canalId)
                            ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(
                                CODIGO_CANAL_NO_EXISTE,
                            )
                    if (canal.fuenteId != observacion.fuenteId) {
                        return@withTransaction ResultadoResolucionObservacion.Rechazada(CODIGO_FUENTE_DISTINTA)
                    }
                    val ahora = Instant.now()
                    val clave = claveDe(observacion, canal.fuenteId)
                    val urlNueva =
                        if (observacion.urlCifrada == null) {
                            canal.urlStreamCifrada
                        } else {
                            reCifrar(observacion.urlCifrada, AAD_OBSERVACION_URL, AAD_CANAL_URL)
                                ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(CODIGO_DESCIFRADO_FALLIDO)
                        }
                    val logoNuevo =
                        if (observacion.logoUrlCifrada == null) {
                            canal.logoUrlCifrada
                        } else {
                            reCifrar(observacion.logoUrlCifrada, AAD_OBSERVACION_LOGO, AAD_CANAL_LOGO)
                                ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(CODIGO_DESCIFRADO_FALLIDO)
                        }
                    val actualizada =
                        canal.copy(
                            nombre = observacion.nombre,
                            tvgId = observacion.tvgId,
                            claveCanonica = observacion.claveCanonica,
                            claveUnicidad = clave,
                            claveDerivable = observacion.claveCanonica != null,
                            estado = EstadoCanal.A,
                            urlStreamCifrada = urlNueva,
                            urlStreamHmac = observacion.urlHmac ?: canal.urlStreamHmac,
                            logoUrlCifrada = logoNuevo,
                            logoUrlHmac = observacion.logoUrlHmac ?: canal.logoUrlHmac,
                            evidencia = evidenciaDe(observacionId),
                            decision = DECISION_ASIGNADA,
                            fechaDecision = ahora,
                            actualizadoEn = ahora,
                        )
                    escritorCanales.actualizar(actualizada, tvgUnico(observacion, clave))
                    db.observacionPendienteDao().eliminar(observacionId)
                    ResultadoResolucionObservacion.Asignada
                }
            }

        /**
         * Confirma la declaración observada como Canal nuevo.
         *
         * Es la única vía por la que una Observación crea un Canal. Se crea
         * también su Apariencia en [listaId] para que el Canal sea visible; el
         * `tvg_name` no se conserva en la Observación y se deja nulo.
         */
        suspend fun promoverACanalNuevo(
            observacionId: Long,
            listaId: Long,
        ): ResultadoResolucionObservacion =
            resolverConConflicto {
                db.withTransaction {
                    val observacion =
                        db.observacionPendienteDao().porId(observacionId)
                            ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(
                                CODIGO_OBSERVACION_NO_EXISTE,
                            )
                    val lista =
                        db.listaDao().porId(listaId)
                            ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(
                                CODIGO_LISTA_NO_EXISTE,
                            )
                    if (lista.fuenteId != observacion.fuenteId) {
                        return@withTransaction ResultadoResolucionObservacion.Rechazada(CODIGO_FUENTE_DISTINTA)
                    }
                    val urlCifrada = observacion.urlCifrada
                    val urlHmac = observacion.urlHmac
                    if (urlCifrada == null || urlHmac == null) {
                        return@withTransaction ResultadoResolucionObservacion.Rechazada(CODIGO_URL_AUSENTE)
                    }
                    val ahora = Instant.now()
                    val clave = claveDe(observacion, observacion.fuenteId)
                    val urlCanal =
                        reCifrar(urlCifrada, AAD_OBSERVACION_URL, AAD_CANAL_URL)
                            ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(CODIGO_DESCIFRADO_FALLIDO)
                    val logoCanal =
                        if (observacion.logoUrlCifrada == null) {
                            null
                        } else {
                            reCifrar(observacion.logoUrlCifrada, AAD_OBSERVACION_LOGO, AAD_CANAL_LOGO)
                                ?: return@withTransaction ResultadoResolucionObservacion.Rechazada(CODIGO_DESCIFRADO_FALLIDO)
                        }
                    val canalId =
                        escritorCanales.insertar(
                            CanalEntity(
                                fuenteId = observacion.fuenteId,
                                nombre = observacion.nombre,
                                urlStreamCifrada = urlCanal,
                                urlStreamHmac = urlHmac,
                                logoUrlCifrada = logoCanal,
                                logoUrlHmac = observacion.logoUrlHmac,
                                tvgId = observacion.tvgId,
                                claveCanonica = observacion.claveCanonica,
                                claveUnicidad = clave,
                                claveDerivable = observacion.claveCanonica != null,
                                estado = EstadoCanal.A,
                                creadoEn = ahora,
                                actualizadoEn = ahora,
                            ),
                            tvgUnico(observacion, clave),
                        )
                    crearApariencia(listaId, canalId, observacion, ahora)
                    db.observacionPendienteDao().eliminar(observacionId)
                    ResultadoResolucionObservacion.CanalPromovido(canalId)
                }
            }

        private suspend fun crearApariencia(
            listaId: Long,
            canalId: Long,
            observacion: ObservacionPendienteImportacionEntity,
            ahora: Instant,
        ) {
            val grupo =
                observacion.grupo?.let { nombre ->
                    db.grupoDao().porNombre(listaId, nombre)
                        ?: GrupoEntity(listaId = listaId, nombre = nombre).let { nuevo ->
                            nuevo.copy(id = db.grupoDao().insertar(nuevo))
                        }
                }
            val apariencia =
                AparienciaEntity(
                    listaId = listaId,
                    canalId = canalId,
                    grupoId = grupo?.id,
                    orden = db.aparienciaDao().maxOrden(listaId) + 1,
                    groupTitle = observacion.grupo,
                    tvgName = null,
                    clavePresentacion = ClavePresentacion.clave(observacion.grupo, null),
                    repeticiones = 1,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                )
            when (val resultado = escritorApariencias.upsert(apariencia, ahora)) {
                is ResultadoUpsertApariencia.Escrita -> Unit
                is ResultadoUpsertApariencia.Rechazada ->
                    throw ImportacionRechazada(resultado.codigo)
            }
        }

        /**
         * Clave final de la declaración observada.
         *
         * Usa `tvg:v1:<tvg>` solo si el `tvg-id` es válido y no está tomado en
         * la Fuente; si no, usa la clave canónica. Si tampoco existe, la fila
         * queda sin clave y `claveDerivable = false`.
         */
        private suspend fun claveDe(
            observacion: ObservacionPendienteImportacionEntity,
            fuenteId: Long,
        ): String? {
            val tvg = observacion.tvgId
            if (tvg != null && ValidadorTvgId.validar(tvg) is ResultadoTvgId.Valido) {
                val candidata = ClaveCanal.PREFIJO_TVG + tvg
                if (db.canalDao().porClaveUnicidad(fuenteId, candidata) == null) return candidata
            }
            return observacion.claveCanonica
        }

        private fun tvgUnico(
            observacion: ObservacionPendienteImportacionEntity,
            clave: String?,
        ): Boolean = observacion.tvgId != null && clave == ClaveCanal.PREFIJO_TVG + observacion.tvgId

        private fun evidenciaDe(observacionId: Long): String = "observacion:v1;id=$observacionId"

        /**
         * Descifra un sobre de la Observación y lo vuelve a cifrar para `Canal`.
         *
         * Devuelve `null` si el sobre no se puede descifrar con la clave y el AAD
         * de origen; el llamador debe rechazar la resolución sin escribir nada.
         */
        private fun reCifrar(
            sobre: ByteArray,
            aadOrigen: String,
            aadDestino: String,
        ): ByteArray? {
            val claro =
                (CifradoAes.descifrar(claves.claveAes, aadOrigen, sobre) as? ResultadoDescifrado.Ok)?.bytes
                    ?: return null
            return CifradoAes.cifrar(claves.claveAes, aadDestino, claro)
        }

        private suspend fun resolverConConflicto(bloque: suspend () -> ResultadoResolucionObservacion): ResultadoResolucionObservacion =
            try {
                bloque()
            } catch (conflicto: SQLiteConstraintException) {
                ResultadoResolucionObservacion.Rechazada(CODIGO_CLAVE_EN_CONFLICTO)
            } catch (rechazo: ImportacionRechazada) {
                ResultadoResolucionObservacion.Rechazada(rechazo.codigo)
            }

        companion object {
            const val CODIGO_OBSERVACION_NO_EXISTE: String = "observacion_no_existe"
            const val CODIGO_CANAL_NO_EXISTE: String = "canal_no_existe"
            const val CODIGO_LISTA_NO_EXISTE: String = "lista_no_existe"
            const val CODIGO_FUENTE_DISTINTA: String = "fuente_distinta"
            const val CODIGO_URL_AUSENTE: String = "url_ausente"
            const val CODIGO_CLAVE_EN_CONFLICTO: String = "clave_en_conflicto"
            const val CODIGO_DESCIFRADO_FALLIDO: String = "descifrado_fallido"
            const val DECISION_ASIGNADA: String = "observacion_asignada"

            private val AAD_OBSERVACION_URL: String =
                CifradoAes.aad("ObservacionPendienteImportacion", "url_cifrada")
            private val AAD_OBSERVACION_LOGO: String =
                CifradoAes.aad("ObservacionPendienteImportacion", "logo_url_cifrada")
            private val AAD_CANAL_URL: String = CifradoAes.aad("Canal", "url_stream_cifrada")
            private val AAD_CANAL_LOGO: String = CifradoAes.aad("Canal", "logo_url_cifrada")
        }
    }
