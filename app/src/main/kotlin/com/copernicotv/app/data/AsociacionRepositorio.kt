package com.copernicotv.app.data

import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AsociacionEntity
import com.copernicotv.core.epg.AsociacionActual
import com.copernicotv.core.epg.EstadoAsociacion
import com.copernicotv.core.epg.HuellaAsociacion
import com.copernicotv.core.epg.ObservacionCanalGuia
import com.copernicotv.core.epg.OperacionesAsociacion
import com.copernicotv.core.epg.OrigenAsociacion
import com.copernicotv.core.epg.ResultadoHuella
import com.copernicotv.core.epg.SenalesFuertes
import java.time.Instant
import javax.inject.Inject
import javax.inject.Singleton

/** Opción de Canal para resolver una Asociación. */
data class CanalOpcion(
    val id: Long,
    val nombre: String?,
)

/** Asociación con el nombre del Canal para la pantalla de resolución. */
data class AsociacionVista(
    val id: Long,
    val canalId: Long,
    val canalNombre: String?,
    val channelIdTexto: String,
    val fuenteGuiaId: Long,
    val estado: EstadoAsociacion,
    val origen: OrigenAsociacion,
    val huellaVersion: Int,
    val huellaValor: String?,
    val confirmadaEn: Instant?,
    val ultimaObservacion: Instant,
)

/**
 * Operaciones manuales de Asociación (E5-T4, DC-06).
 *
 * La reconfirmación no vuelve a parsear XMLTV: recalcula la huella desde las
 * señales persistidas. Confirmar, editar y revocar escriben en una sola fila;
 * la UI agrupa la acción que el usuario elige.
 */
@Singleton
class AsociacionRepositorio
    @Inject
    constructor(
        private val db: AppDatabase,
    ) {
        /** Reloj inyectable para pruebas de persistencia. */
        internal var reloj: () -> Instant = Instant::now

        suspend fun listar(fuenteGuiaId: Long): List<AsociacionVista> =
            db.asociacionDao().porFuente(fuenteGuiaId).map { fila ->
                fila.aVista(db.canalDao().porId(fila.canalId)?.nombre)
            }

        suspend fun porId(id: Long): AsociacionVista? =
            db.asociacionDao().porId(id)?.let { fila ->
                fila.aVista(db.canalDao().porId(fila.canalId)?.nombre)
            }

        /** Catálogo de Canales para editar el destino de una Asociación. */
        suspend fun canalesCatalogo(): List<CanalOpcion> = db.canalDao().todos().map { CanalOpcion(it.id, it.nombre) }

        suspend fun confirmar(id: Long): AsociacionVista? =
            mutar(id) { existente, ahora ->
                OperacionesAsociacion.confirmar(existente, observacionDe(existente), ahora)
            }

        suspend fun editar(
            id: Long,
            nuevoCanalId: Long,
        ): AsociacionVista? =
            mutar(id) { existente, ahora ->
                OperacionesAsociacion.editar(existente, nuevoCanalId, observacionDe(existente), ahora)
            }

        suspend fun revocar(id: Long): AsociacionVista? = mutar(id) { existente, ahora -> OperacionesAsociacion.revocar(existente, ahora) }

        private suspend fun mutar(
            id: Long,
            operacion: (AsociacionActual, Instant) -> com.copernicotv.core.epg.ActualizacionAsociacion,
        ): AsociacionVista? {
            val fila = db.asociacionDao().porId(id) ?: return null
            val ahora = reloj()
            val actualizacion = operacion(fila.aModelo(), ahora)
            val nueva = actualizacion.aEntidad(ahora)
            db.asociacionDao().actualizar(nueva)
            return db.asociacionDao().porId(id)?.let { fila ->
                fila.aVista(db.canalDao().porId(fila.canalId)?.nombre)
            }
        }

        /**
         * Reconstruye una observación desde las señales persistidas.
         *
         * Si la fila no tiene señales, la huella es `null`; la confirmación
         * manual sigue siendo válida y la UI puede advertir que no hay huella.
         */
        private fun observacionDe(existente: AsociacionActual): ObservacionCanalGuia {
            val huella = HuellaAsociacion.calcularDesdeSenales(existente.senales)
            return ObservacionCanalGuia(
                channelIdTexto = existente.channelIdTexto,
                resultadoHuella =
                    ResultadoHuella(
                        huella = huella,
                        senales = existente.senales,
                        iconosExcluidos = 0,
                        requiereClaveHmac = false,
                    ),
            )
        }

        private fun AsociacionEntity.aModelo(): AsociacionActual =
            AsociacionActual(
                id = id,
                canalId = canalId,
                fuenteGuiaId = fuenteGuiaId,
                channelIdTexto = channelIdTexto,
                estado = EstadoAsociacion.desde(estado) ?: EstadoAsociacion.SOSPECHOSA,
                origen = OrigenAsociacion.entries.firstOrNull { it.valor == origen } ?: OrigenAsociacion.MANUAL,
                huellaVersion = huellaVersion,
                huellaValor = huellaValor,
                senales = SenalesFuertes.desdeJson(senalesFuertes),
                confirmadaEn = confirmadaEn,
            )

        private fun AsociacionEntity.aVista(nombreCanal: String?): AsociacionVista =
            AsociacionVista(
                id = id,
                canalId = canalId,
                canalNombre = nombreCanal,
                channelIdTexto = channelIdTexto,
                fuenteGuiaId = fuenteGuiaId,
                estado = EstadoAsociacion.desde(estado) ?: EstadoAsociacion.SOSPECHOSA,
                origen = OrigenAsociacion.entries.firstOrNull { it.valor == origen } ?: OrigenAsociacion.MANUAL,
                huellaVersion = huellaVersion,
                huellaValor = huellaValor,
                confirmadaEn = confirmadaEn,
                ultimaObservacion = ultimaObservacion,
            )

        private fun com.copernicotv.core.epg.ActualizacionAsociacion.aEntidad(ahora: Instant): AsociacionEntity =
            AsociacionEntity(
                id = id ?: 0,
                canalId = canalId,
                fuenteGuiaId = fuenteGuiaId,
                channelIdTexto = channelIdTexto,
                estado = estado.valor,
                origen = origen.valor,
                huellaVersion = huellaVersion,
                huellaValor = huellaValor,
                senalesFuertes = senales.aJson(),
                ultimaObservacion = ultimaObservacion ?: ahora,
                confirmadaEn = confirmadaEn,
                actualizadaEn = ahora,
            )
    }
