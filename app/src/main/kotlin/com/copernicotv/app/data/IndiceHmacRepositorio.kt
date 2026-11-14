package com.copernicotv.app.data

import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesOperativas
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.SembrarEstadoIndiceHmac
import com.copernicotv.app.data.db.entities.EstadoIndiceHmacEntity
import com.copernicotv.app.data.db.entities.HmacPendienteEntity
import com.copernicotv.core.hmac.EstadoIndiceHmac
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.HmacPendiente
import com.copernicotv.core.hmac.MaquinaIndiceHmac
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.RechazoIndiceHmac
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.hmac.VersionOrigenHmac
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import javax.crypto.SecretKey
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
        private val operativas: ClavesOperativas = ClavesOperativas(),
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

        /**
         * Estado persistido, creando la fila singleton con `hmac:v1` si falta.
         *
         * Es una lectura de fase, no una puerta de integridad: no comprueba la
         * clave ni los sobres. Las operaciones que sellan o comparan usan
         * [evaluarIntegridad] bajo el mismo [candado], de modo que la fase que
         * devuelve este método nunca habilita por sí sola una comparación.
         */
        suspend fun estado(ahora: Instant = Instant.now()): EstadoIndiceHmac = candado.withLock { aModelo(asegurarBloqueado(ahora)) }

        /**
         * Conciliación completa en una sola sección crítica: fila persistida,
         * sobres existentes y clave de la generación activa.
         *
         * Es la fuente única para [selloHmac], [bloqueoIgualdad],
         * [inicializarLegitimamente] y la guardia de arranque. Así ninguna
         * operación que decide sobre la integridad observa un índice `operativo`
         * sin haber comprobado su clave, y la guardia y las operaciones normales
         * comparten el mismo [candado]. [estado] solo informa la fase persistida.
         */
        suspend fun evaluarIntegridad(ahora: Instant = Instant.now()): IntegridadIndiceHmac =
            candado.withLock { conciliarBloqueado(ahora).publica() }

        /**
         * Primera inicialización legítima: sin sobres `hmac:vN` persistidos.
         * Incorpora la clave de la generación inicial de forma explícita e
         * idempotente y persiste la fila de estado operativo. Si la clave falta
         * y ya hay sobres, declara la pérdida y no crea ninguna clave.
         */
        suspend fun inicializarLegitimamente(ahora: Instant = Instant.now()): EstadoIndiceHmac =
            candado.withLock { conciliarBloqueado(ahora).estado }

        /**
         * Guardia de sello (DC-04 §7, Q2): entrega la clave de la generación
         * activa para producir un sobre, o bloquea con `indice_hmac_no_operativo`.
         *
         * Nunca genera una clave sustituta. La conciliación bajo [candado] deja
         * el índice en `recuperacion_pendiente` si la clave de la generación
         * activa desapareció con sobres persistidos, y solo incorpora una clave
         * cuando no hay datos protegidos que puedan quedar inverificables.
         */
        suspend fun selloHmac(ahora: Instant = Instant.now()): SelloIndiceHmac =
            candado.withLock {
                when (val integridad = conciliarBloqueado(ahora)) {
                    is Conciliacion.Listo ->
                        // Ronda 6 (S44): la entrega es la clave operativa
                        // derivada de la raíz; la raíz queda como ancla de
                        // integridad y el sellado cuesta una op de Keystore.
                        SelloIndiceHmac.Disponible(
                            operativas.hmacOperativa(integridad.clave),
                            integridad.estado.generacionActiva,
                        )

                    is Conciliacion.Perdida, is Conciliacion.Recuperacion ->
                        SelloIndiceHmac.Bloqueada(RechazoIndiceHmac())
                }
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
         * Es `null` solo cuando la conciliación confirma que el índice está
         * operativo **y** su clave activa sigue en la bóveda; en cualquier otro
         * caso devuelve [RechazoIndiceHmac] con `indice_hmac_no_operativo`. Si
         * la clave desapareció con sobres persistidos, la conciliación deja el
         * índice en `recuperacion_pendiente` antes de bloquear.
         */
        suspend fun bloqueoIgualdad(ahora: Instant = Instant.now()): RechazoIndiceHmac? =
            candado.withLock {
                when (conciliarBloqueado(ahora)) {
                    is Conciliacion.Listo -> null
                    is Conciliacion.Perdida, is Conciliacion.Recuperacion -> RechazoIndiceHmac()
                }
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

        /**
         * Conciliación interna. Requiere que el llamador ya tenga [candado]: el
         * `Mutex` no es reentrante. Distingue la instalación sin datos de la
         * pérdida real por la presencia de sobres, nunca por el estado solo.
         */
        private suspend fun conciliarBloqueado(ahora: Instant): Conciliacion {
            val dao = db.estadoIndiceHmacDao()
            val anterior = dao.actual()
            if (anterior == null) {
                if (haySobres()) {
                    // Anomalía: sobres sellados sin la fila que describe su
                    // generación. Se preservan los datos y se declara la pérdida
                    // sin crear ninguna clave.
                    val base = filaInicial(ahora).also { dao.guardar(it) }
                    return declararPerdidaBloqueado(base, ahora)
                }
                val clave = claves.crearClaveHmac(GENERACION_INICIAL)
                val fila = filaInicial(ahora).also { dao.guardar(it) }
                return Conciliacion.Listo(aModelo(fila), clave, inicializado = true)
            }
            val estado = aModelo(anterior)
            if (!estado.esOperativo) return Conciliacion.Recuperacion(estado)
            val clave = claves.claveHmac(estado.generacionActiva)
            if (clave != null) return Conciliacion.Listo(estado, clave, inicializado = false)
            if (haySobres()) return declararPerdidaBloqueado(anterior, ahora)
            // Sin sobres no hay valores protegidos que puedan quedar
            // inverificables: se materializa la generación persistida, nunca
            // una sustituta.
            return Conciliacion.Listo(
                estado,
                claves.crearClaveHmac(estado.generacionActiva),
                inicializado = true,
            )
        }

        /**
         * `operativo → recuperacion_pendiente` dentro de la conciliación. Una
         * transición rechazada conserva el estado y no escribe nada.
         */
        private suspend fun declararPerdidaBloqueado(
            anterior: EstadoIndiceHmacEntity,
            ahora: Instant,
        ): Conciliacion {
            val estado = aModelo(anterior)
            return when (val resultado = MaquinaIndiceHmac.detectarPerdidaClave(estado, ahora)) {
                is ResultadoTransicion.Ok -> {
                    db.estadoIndiceHmacDao().guardar(aEntidad(resultado.estado, anterior))
                    Conciliacion.Perdida(resultado.estado)
                }

                is ResultadoTransicion.Rechazada -> Conciliacion.Recuperacion(estado)
            }
        }

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

        /**
         * Resultado interno de conciliar fila, sobres y clave. [Listo] garantiza
         * que [clave] es la de la generación activa de [estado].
         */
        private sealed interface Conciliacion {
            val estado: EstadoIndiceHmac

            data class Listo(
                override val estado: EstadoIndiceHmac,
                val clave: SecretKey,
                val inicializado: Boolean,
            ) : Conciliacion

            data class Perdida(override val estado: EstadoIndiceHmac) : Conciliacion

            data class Recuperacion(override val estado: EstadoIndiceHmac) : Conciliacion
        }

        private fun Conciliacion.publica(): IntegridadIndiceHmac =
            when (this) {
                is Conciliacion.Listo ->
                    if (inicializado) {
                        IntegridadIndiceHmac.Inicializado(estado)
                    } else {
                        IntegridadIndiceHmac.Operativo(estado)
                    }

                is Conciliacion.Perdida -> IntegridadIndiceHmac.Perdida(estado)
                is Conciliacion.Recuperacion -> IntegridadIndiceHmac.Recuperacion(estado)
            }

        private companion object {
            const val ID_SINGLETON = SembrarEstadoIndiceHmac.ID_SINGLETON

            /** Generación inicial del contrato: `hmac:v1`. */
            const val GENERACION_INICIAL = SembrarEstadoIndiceHmac.GENERACION_INICIAL
        }
    }
