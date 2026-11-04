package com.copernicotv.app.data

import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.EstadoIndiceHmacEntity
import com.copernicotv.app.data.db.entities.HmacPendienteEntity
import com.copernicotv.core.hmac.EstadoIndiceHmac
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.GeneracionHmac
import com.copernicotv.core.hmac.HmacPendiente
import com.copernicotv.core.hmac.MaquinaIndiceHmac
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.PlanReconstruccionHmac
import com.copernicotv.core.hmac.RechazoIndiceHmac
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.hmac.VersionOrigenHmac
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
    ) {
        /** Estado persistido, creando la fila singleton con `hmac:v1` si falta. */
        suspend fun estado(ahora: Instant = Instant.now()): EstadoIndiceHmac = aModelo(asegurar(ahora))

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
            PlanReconstruccionHmac.bloqueoIgualdad(aModelo(asegurar(ahora)))

        /** Registra o actualiza una fila no reconstruible por su clave única. */
        suspend fun registrarPendiente(
            pendiente: HmacPendiente,
            ahora: Instant = Instant.now(),
        ) {
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
        ): ResultadoTransicion {
            val anterior = asegurar(ahora)
            return when (val resultado = bloque(aModelo(anterior))) {
                is ResultadoTransicion.Ok -> {
                    db.estadoIndiceHmacDao().guardar(aEntidad(resultado.estado, anterior))
                    resultado
                }

                is ResultadoTransicion.Rechazada -> resultado
            }
        }

        private suspend fun asegurar(ahora: Instant): EstadoIndiceHmacEntity {
            val dao = db.estadoIndiceHmacDao()
            return dao.actual() ?: filaInicial(ahora).also { dao.guardar(it) }
        }

        private fun filaInicial(ahora: Instant): EstadoIndiceHmacEntity =
            EstadoIndiceHmacEntity(
                id = ID_SINGLETON,
                estado = FaseIndiceHmac.OPERATIVO.codigo,
                versionActiva = GeneracionHmac.formatear(1),
                versionObjetivo = null,
                iniciadoEn = ahora,
                actualizadoEn = ahora,
                filasTotales = 0,
                filasReconstruidas = 0,
                filasPendientes = 0,
                ultimoErrorTipo = null,
            )

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
            const val ID_SINGLETON = 1L
        }
    }
