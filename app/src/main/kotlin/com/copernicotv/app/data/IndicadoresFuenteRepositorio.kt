package com.copernicotv.app.data

import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import javax.inject.Inject
import javax.inject.Singleton

/** Lectura de los indicadores de S7 por Fuente (E4-T8). */
interface IndicadoresFuenteRepositorio {
    fun observar(): Flow<List<IndicadoresFuente>>
}

/**
 * Implementación Room de [IndicadoresFuenteRepositorio].
 *
 * La disponibilidad se deriva del catálogo persistido: una Fuente está
 * disponible si tiene al menos un Canal. Un fallo de Actualización no la
 * modifica porque no borra Canales; es el criterio de S7c.
 *
 * El Estado de Actualización se lee del último intento completado de sus
 * Listas. La Fuente no guarda un resultado propio: el estado canónico es el de
 * cada Lista y el agregado se deriva.
 */
@Singleton
class IndicadoresFuenteRepositorioRoom
    @Inject
    constructor(
        private val db: AppDatabase,
    ) : IndicadoresFuenteRepositorio {
        override fun observar(): Flow<List<IndicadoresFuente>> {
            val dao = db.indicadoresFuenteDao()
            return combine(
                dao.observarFuentes(),
                db.listaDao().observarTodas(),
                dao.observarCompletadas(),
                dao.observarConteoPorFuente(),
            ) { fuentes, listas, estados, conteos ->
                construir(
                    fuentes = fuentes,
                    listas = listas,
                    estados = estados,
                    canalesPorFuente = conteos.associate { it.fuenteId to it.total },
                )
            }
        }

        private fun construir(
            fuentes: List<FuenteEntity>,
            listas: List<ListaEntity>,
            estados: List<EstadoActualizacionEntity>,
            canalesPorFuente: Map<Long, Int>,
        ): List<IndicadoresFuente> {
            val listasPorFuente = listas.groupBy { it.fuenteId }
            return fuentes.map { fuente ->
                val idsDeListas = listasPorFuente[fuente.id].orEmpty().map { it.id }.toSet()
                IndicadoresFuente(
                    fuenteId = fuente.id,
                    nombre = fuente.nombre,
                    actualizacion =
                        estados
                            .filter { estado ->
                                estado.fuenteId == fuente.id || estado.listaId?.let { it in idsDeListas } == true
                            }.mapNotNull(::resultado)
                            .maxByOrNull { it.fin },
                    disponibilidad =
                        if ((canalesPorFuente[fuente.id] ?: 0) > 0) {
                            DisponibilidadFuente.DISPONIBLE
                        } else {
                            DisponibilidadFuente.SIN_CONTENIDO
                        },
                )
            }
        }

        private fun resultado(estado: EstadoActualizacionEntity): ResultadoActualizacionFuente? {
            val fin = estado.fin ?: return null
            val codigo = estado.resultado ?: return null
            val exito = codigo == RESULTADO_COMPLETADA
            return ResultadoActualizacionFuente(
                exito = exito,
                causa = if (exito) null else codigo,
                fin = fin,
            )
        }

        private companion object {
            /**
             * Código que `ActualizacionRepositorio` persiste en
             * `EstadoActualizacion.resultado` al cerrar un intento correcto.
             * `IndicadoresFuenteTest` lo contrasta contra el pipeline real.
             */
            const val RESULTADO_COMPLETADA = "completada"
        }
    }
