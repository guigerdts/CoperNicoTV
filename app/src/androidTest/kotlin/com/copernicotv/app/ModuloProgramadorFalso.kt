package com.copernicotv.app

import com.copernicotv.app.data.actualizacion.CausasActualizacion
import com.copernicotv.app.data.actualizacion.CoordinadorActualizacion
import com.copernicotv.app.data.actualizacion.EstadoSolicitud
import com.copernicotv.app.data.actualizacion.ProgramadorActualizaciones
import com.copernicotv.app.data.actualizacion.ResultadoEjecucion
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.di.ModuloProgramador
import dagger.Module
import dagger.Provides
import dagger.hilt.components.SingletonComponent
import dagger.hilt.testing.TestInstallIn
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emitAll
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Planificador de prueba para las pruebas instrumentadas (E4-T7).
 *
 * `HiltTestRunner` sustituye la Application por `HiltTestApplication`, que no
 * implementa `Configuration.Provider`: WorkManager no puede inicializarse en ese
 * entorno y el planificador real rompería el arranque de la Activity. Este doble
 * reemplaza solo el planificador y ejecuta el [CoordinadorActualizacion] real,
 * así que las pruebas siguen recorriendo descarga, reconciliación y transacción
 * con la base y el fixture reales.
 */
@Module
@TestInstallIn(components = [SingletonComponent::class], replaces = [ModuloProgramador::class])
object ModuloProgramadorFalso {
    @Provides
    @Singleton
    fun programador(implementacion: ProgramadorFalso): ProgramadorActualizaciones = implementacion
}

/** Planificador síncrono: publica el estado y ejecuta el coordinador en el acto. */
@Singleton
class ProgramadorFalso
    @Inject
    constructor(
        private val coordinador: CoordinadorActualizacion,
        private val db: AppDatabase,
    ) : ProgramadorActualizaciones {
        private val estados = MutableStateFlow<Map<Long, EstadoSolicitud>>(emptyMap())

        override fun observar(): Flow<Map<Long, EstadoSolicitud>> = estados

        override fun observarLista(listaId: Long): Flow<EstadoSolicitud> =
            flow {
                val fuenteId = db.listaDao().porId(listaId)?.fuenteId ?: return@flow
                emitAll(estados.map { it[fuenteId] ?: EstadoSolicitud() })
            }

        override suspend fun solicitarLista(listaId: Long) {
            val fuenteId = db.listaDao().porId(listaId)?.fuenteId ?: return
            solicitarFuente(fuenteId)
        }

        override suspend fun solicitarFuente(fuenteId: Long) {
            publicar(fuenteId, EstadoSolicitud(enCurso = true))
            val resumenes =
                when (val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.MANUAL, 0)) {
                    is ResultadoEjecucion.Exito -> resultado.resumenes
                    is ResultadoEjecucion.Fallo -> resultado.resumenes
                    is ResultadoEjecucion.Reintentar -> resultado.resumenes
                    ResultadoEjecucion.Coalescido -> emptyList()
                }
            publicar(fuenteId, EstadoSolicitud(enCurso = false, resumenes = resumenes))
        }

        override suspend fun reconciliar() = Unit

        private fun publicar(
            fuenteId: Long,
            estado: EstadoSolicitud,
        ) {
            estados.value = estados.value + (fuenteId to estado)
        }
    }
