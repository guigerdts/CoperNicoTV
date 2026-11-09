package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.SembrarEstadoIndiceHmac
import com.copernicotv.app.data.db.entities.EstadoIndiceHmacEntity
import com.copernicotv.app.data.db.entities.HmacPendienteEntity
import com.copernicotv.core.hmac.EstadoIndiceHmac
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.HmacPendiente
import com.copernicotv.core.hmac.MaquinaIndiceHmac
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.PlanReconstruccionHmac
import com.copernicotv.core.hmac.RechazoIndiceHmac
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.hmac.VersionOrigenHmac
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Persistencia del estado del índice HMAC (DC-04 §7, E2-T6).
 *
 * Toda transición se delega en [MaquinaIndiceHmac]: el repositorio solo lee la
 * fila singleton, aplica la función pura y persiste el estado aceptado. Una
 * transición rechazada no escribe nada, así que repetirla es seguro y el
 * resultado es determinista.
 *
 * `EstadoIndiceHmac.iniciadoEn` es nullable en `:core` porque solo existe
 * durante una reconstrucción; la columna de Room es NOT NULL. Al volver a
 * `operativo` se conserva el último inicio conocido, que es el dato
 * históricamente útil y no altera la semántica de la fase.
 */
@Singleton
class IndiceHmacRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
        private val claves: ClavesApp,
    ) {
        /**
         * Serializa las operaciones que leen, validan y escriben la fila de
         * estado: dos transiciones concurrentes no pueden leer el mismo estado
         * anterior y pisarse con escrituras ciegas (lost update).
         *
         * Es un `Mutex` de corrutinas, no un monitor de JVM, porque la sección
         * crítica incluye suspensión de Room. La garantía es de proceso: la app
         * es de un solo proceso y este repositorio es `@Singleton`, así que no
         * existe un segundo escritor que la necesite. No cubre el caso de un
         * proceso externo editando Room, que está fuera del contrato.
         *
         * Orden de cerrojos: primero este `candado`, después el monitor HMAC de
         * `ClavesKeystore`. Ningún camino toma el monitor HMAC y luego este
         * `candado`, así que no hay interbloqueo.
         */
        private val candado = Mutex()

        /** Estado persistido, creando la fila singleton con `hmac:v1` si falta. */
        suspend fun estado(ahora: Instant = Instant.now()): EstadoIndiceHmac = candado.withLock { aModelo(asegurarBloqueado(ahora)) }

        /**
         * Estado persistido sin inicializar la fila: `null` si nunca existió.
         *
         * Permite al guardia de sello y a la guardia de arranque distinguir la
         * primera inicialización legítima de la pérdida de una clave usada.
         */
        suspend fun estadoSinInicializar(): EstadoIndiceHmac? = candado.withLock { db.estadoIndiceHmacDao().actual()?.let(::aModelo) }

        /** ¿Está en la bóveda la clave de la generación [generacion]? */
        suspend fun clavePresente(generacion: Int): Boolean = claves.contieneClaveHmac(generacion)

        /**
         * Primera inicialización legítima (instalación virgen: sin fila de
         * estado ni sobres). Incorpora la clave de la generación inicial de
         * forma explícita e idempotente, sin regenerar nada si ya existe, y
         * persiste la fila de estado operativo.
         */
        suspend fun inicializarLegitimamente(ahora: Instant = Instant.now()): EstadoIndiceHmac =
            candado.withLock {
                claves.crearClaveHmac(GENERACION_INICIAL)
                aModelo(asegurarBloqueado(ahora))
            }

        /**
         * Guardia de sello (DC-04 §7, Q2): entrega la clave de la generación
         * activa para producir un sobre, o bloquea con `indice_hmac_no_operativo`.
         *
         * Nunca genera una clave sustituta. Dos caminos para no quedar
         * operativo: el índice ya está en recuperación o bloqueado, o la clave
         * de la generación activa desapareció en una escritura real, en cuyo
         * caso se transiciona a `recuperacion_pendiente` y se bloquea. La
         * primera inicialización legítima —ni fila de estado ni sobres
         * persistidos— sí incorpora su clave `v1` de forma explícita.
         */
        suspend fun selloHmac(ahora: Instant = Instant.now()): SelloIndiceHmac =
            candado.withLock {
                val anterior = db.estadoIndiceHmacDao().actual()
                val filaExistia = anterior != null
                val base = anterior ?: asegurarBloqueado(ahora)
                val estado = aModelo(base)
                if (!estado.esOperativo) {
                    return@withLock SelloIndiceHmac.Bloqueada(RechazoIndiceHmac())
                }
                val claveActiva = claves.claveHmac(estado.generacionActiva)
                if (claveActiva != null) {
                    return@withLock SelloIndiceHmac.Disponible(claveActiva, estado.generacionActiva)
                }
                val inicializacionLegitima = !filaExistia && !haySobres()
                if (inicializacionLegitima) {
                    return@withLock SelloIndiceHmac.Disponible(
                        claves.crearClaveHmac(estado.generacionActiva),
                        estado.generacionActiva,
                    )
                }
                // La transición de pérdida se aplica aquí, no con
                // `detectarPerdidaClave()`, porque el `Mutex` no es reentrante
                // y esa llamada volvería a tomarlo: la máquina pura es la misma.
                when (val resultado = MaquinaIndiceHmac.detectarPerdidaClave(estado, ahora)) {
                    is ResultadoTransicion.Ok ->
                        db.estadoIndiceHmacDao().guardar(aEntidad(resultado.estado, base))

                    is ResultadoTransicion.Rechazada -> Unit
                }
                SelloIndiceHmac.Bloqueada(RechazoIndiceHmac())
            }

        /** ¿Hay algún sobre `hmac:vN:...` persistido en las tablas con HMAC? */
        suspend fun haySobres(): Boolean =
            with(db.reconstruccionHmacDao()) {
                contarSobresCanal() + contarSobresLogo() + contarSobresLista() +
                    contarSobresObservacion() + contarSobresObservacionLogo() +
                    contarSobresCacheM3u() + contarSobresCacheXmltv() + contarSobresFuenteGuia() > 0L
            }

        /** `operativo → recuperacion_pendiente` al detectar la pérdida de la clave HMAC. */
        suspend fun detectarPerdidaClave(ahora: Instant = Instant.now()): ResultadoTransicion =
            transicion(ahora) { actual -> MaquinaIndiceHmac.detectarPerdidaClave(actual, ahora) }

        /** `recuperacion_pendiente → recuperacion_en_curso` con una generación objetivo nueva. */
        suspend fun iniciarReconstruccion(
            generacionObjetivo: Int,
            ahora: Instant = Instant.now(),
        ): ResultadoTransicion =
            transicion(ahora) { actual ->
                MaquinaIndiceHmac.iniciarReconstruccion(actual, generacionObjetivo, ahora)
            }

        /** Registra el avance de un lote de reconstrucción. */
        suspend fun registrarAvance(
            filasTotales: Long,
            filasReconstruidas: Long,
            filasPendientes: Long,
            ahora: Instant = Instant.now(),
        ): ResultadoTransicion =
            transicion(ahora) { actual ->
                MaquinaIndiceHmac.registrarAvance(
                    actual,
                    filasTotales,
                    filasReconstruidas,
                    filasPendientes,
                    ahora,
                )
            }

        /** Resuelve las filas pendientes y reanuda o cierra la reconstrucción. */
        suspend fun resolverPendientes(
            filasTotales: Long,
            filasReconstruidas: Long,
            filasPendientes: Long,
            ahora: Instant = Instant.now(),
        ): ResultadoTransicion =
            transicion(ahora) { actual ->
                MaquinaIndiceHmac.resolverPendientes(
                    actual,
                    filasTotales,
                    filasReconstruidas,
                    filasPendientes,
                    ahora,
                )
            }

        /** La clave HMAC original reaparece intacta y ninguna fila quedó fuera. */
        suspend fun claveOriginalRecuperada(
            filasFueraDeGeneracionActiva: Long,
            ahora: Instant = Instant.now(),
        ): ResultadoTransicion =
            transicion(ahora) { actual ->
                MaquinaIndiceHmac.claveOriginalRecuperada(actual, filasFueraDeGeneracionActiva, ahora)
            }

        /**
         * Bloqueo tipado de las operaciones de igualdad HMAC.
         *
         * Es `null` solo en `operativo`; en cualquier otra fase devuelve
         * [RechazoIndiceHmac] con `indice_hmac_no_operativo`.
         */
        suspend fun bloqueoIgualdad(ahora: Instant = Instant.now()): RechazoIndiceHmac? =
            candado.withLock {
                PlanReconstruccionHmac.bloqueoIgualdad(aModelo(asegurarBloqueado(ahora)))
            }

        /** Registra o actualiza una fila no reconstruible por su clave única. */
        suspend fun registrarPendiente(
            pendiente: HmacPendiente,
            ahora: Instant = Instant.now(),
        ) {
            candado.withLock {
                val dao = db.hmacPendienteDao()
                val existente = dao.porClave(pendiente.entidad, pendiente.entidadId, pendiente.columna)
                if (existente == null) {
                    dao.insertar(
                        HmacPendienteEntity(
                            entidad = pendiente.entidad,
                            entidadId = pendiente.entidadId,
                            columna = pendiente.columna,
                            versionOrigen = pendiente.versionOrigen.texto,
                            motivo = pendiente.motivo.codigo,
                            creadoEn = ahora,
                            actualizadoEn = ahora,
                        ),
                    )
                } else {
                    dao.actualizar(
                        existente.id,
                        pendiente.versionOrigen.texto,
                        pendiente.motivo.codigo,
                        ahora,
                    )
                }
            }
        }

        /** Filas pendientes ordenadas por su clave única. */
        suspend fun pendientes(): List<HmacPendiente> = db.hmacPendienteDao().todas().map(::aPendiente)

        /** Cantidad de filas pendientes. */
        suspend fun contarPendientes(): Long = db.hmacPendienteDao().contar()

        /** Elimina la fila pendiente de una columna concreta. */
        suspend fun eliminarPendiente(
            entidad: String,
            entidadId: Long,
            columna: String,
        ): Int = db.hmacPendienteDao().eliminar(entidad, entidadId, columna)

        /** Elimina todas las filas pendientes (solo tras resolverlas por reingreso o borrado). */
        suspend fun eliminarPendientes() = db.hmacPendienteDao().eliminarTodas()

        private suspend fun transicion(
            ahora: Instant,
            bloque: (EstadoIndiceHmac) -> ResultadoTransicion,
        ): ResultadoTransicion =
            candado.withLock {
                val anterior = asegurarBloqueado(ahora)
                when (val resultado = bloque(aModelo(anterior))) {
                    is ResultadoTransicion.Ok -> {
                        db.estadoIndiceHmacDao().guardar(aEntidad(resultado.estado, anterior))
                        resultado
                    }

                    is ResultadoTransicion.Rechazada -> resultado
                }
            }

        /**
         * Fila de estado vigente, creándola si falta. Requiere que el llamador
         * ya tenga [candado]: el `Mutex` no es reentrante y tomarlo aquí
         * rompería las transiciones atómicas.
         */
        private suspend fun asegurarBloqueado(ahora: Instant): EstadoIndiceHmacEntity {
            val dao = db.estadoIndiceHmacDao()
            return dao.actual() ?: filaInicial(ahora).also { dao.guardar(it) }
        }

        private fun filaInicial(ahora: Instant): EstadoIndiceHmacEntity = SembrarEstadoIndiceHmac.fila(ahora)

        private fun aModelo(fila: EstadoIndiceHmacEntity): EstadoIndiceHmac =
            EstadoIndiceHmac(
                fase = requireNotNull(FaseIndiceHmac.desde(fila.estado)) { "estado HMAC desconocido" },
                versionActiva = fila.versionActiva,
                versionObjetivo = fila.versionObjetivo,
                iniciadoEn = fila.iniciadoEn,
                actualizadoEn = fila.actualizadoEn,
                filasTotales = fila.filasTotales,
                filasReconstruidas = fila.filasReconstruidas,
                filasPendientes = fila.filasPendientes,
                ultimoErrorTipo = fila.ultimoErrorTipo,
            )

        private fun aEntidad(
            estado: EstadoIndiceHmac,
            anterior: EstadoIndiceHmacEntity,
        ): EstadoIndiceHmacEntity =
            EstadoIndiceHmacEntity(
                id = ID_SINGLETON,
                estado = estado.fase.codigo,
                versionActiva = estado.versionActiva,
                versionObjetivo = estado.versionObjetivo,
                iniciadoEn = estado.iniciadoEn ?: anterior.iniciadoEn,
                actualizadoEn = estado.actualizadoEn,
                filasTotales = estado.filasTotales,
                filasReconstruidas = estado.filasReconstruidas,
                filasPendientes = estado.filasPendientes,
                ultimoErrorTipo = estado.ultimoErrorTipo,
            )

        private fun aPendiente(fila: HmacPendienteEntity): HmacPendiente =
            HmacPendiente(
                entidad = fila.entidad,
                entidadId = fila.entidadId,
                columna = fila.columna,
                versionOrigen =
                    requireNotNull(VersionOrigenHmac.parsear(fila.versionOrigen)) {
                        "versionOrigen HMAC inválida"
                    },
                motivo =
                    requireNotNull(MotivoHmacPendiente.desde(fila.motivo)) {
                        "motivo HMAC pendiente inválido"
                    },
            )

        private companion object {
            const val ID_SINGLETON = SembrarEstadoIndiceHmac.ID_SINGLETON

            /** Generación inicial del contrato: `hmac:v1`. */
            const val GENERACION_INICIAL = SembrarEstadoIndiceHmac.GENERACION_INICIAL
        }
    }
