package com.copernicotv.app.data.actualizacion

import com.copernicotv.app.data.ResumenActualizacion
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.actualizacion.ClaseErrorActualizacion
import com.copernicotv.core.actualizacion.ClasificacionErroresActualizacion
import kotlinx.coroutines.CancellationException
import java.security.GeneralSecurityException
import java.security.ProviderException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Actualización de todas las Listas de una Fuente, sin depender de Room ni de red.
 *
 * Es una interfaz para poder probar el coordinador con un doble, igual que el
 * antiguo ejecutor de E4-T8 hacía en la pantalla de Fuentes. La implementación
 * real la provee Hilt sobre `ActualizacionRepositorio`.
 */
fun interface ActualizadorLista {
    suspend fun actualizar(
        listaId: Long,
        causa: String,
    ): ResumenActualizacion
}

/** Resultado de una ejecución del coordinador, ya decidido para WorkManager. */
sealed interface ResultadoEjecucion {
    /** Todas las Listas se actualizaron; no hay nada que reintentar. */
    data class Exito(
        val resumenes: List<ResumenActualizacion>,
    ) : ResultadoEjecucion

    /** Hubo un error transitorio y quedan reintentos en este ciclo. */
    data class Reintentar(
        val resumenes: List<ResumenActualizacion>,
        val motivo: String,
    ) : ResultadoEjecucion

    /** Fallo definitivo en este ciclo: se espera el siguiente periódico. */
    data class Fallo(
        val resumenes: List<ResumenActualizacion>,
        val motivo: String,
    ) : ResultadoEjecucion

    /** Otra ejecución tiene reclamada la Fuente: esta no corre (DC-07 punto 6). */
    data object Coalescido : ResultadoEjecucion
}

/** Decisión que el worker traduce a `Result.success`, `retry` o `failure`. */
enum class DecisionEjecucion {
    EXITO,
    REINTENTAR,
    FALLAR,
}

/** Traduce el resultado del coordinador a la decisión de WorkManager. */
fun decisionDe(resultado: ResultadoEjecucion): DecisionEjecucion =
    when (resultado) {
        is ResultadoEjecucion.Exito, ResultadoEjecucion.Coalescido -> DecisionEjecucion.EXITO
        is ResultadoEjecucion.Reintentar -> DecisionEjecucion.REINTENTAR
        is ResultadoEjecucion.Fallo -> DecisionEjecucion.FALLAR
    }

/**
 * Coordina una ejecución de la Actualización (DC-07 puntos 6, 7 y 9).
 *
 * El worker no decide nada por su cuenta: pide [ejecutar] y traduce el
 * resultado. Así la exclusión por Fuente, la clasificación de errores y el
 * tope de reintentos se prueban en JVM sin WorkManager.
 *
 * El reclamo se libera siempre, incluso cuando la corrutina se cancela: si no,
 * un trabajo cancelado dejaría la Fuente bloqueada hasta el próximo arranque.
 */
@Singleton
class CoordinadorActualizacion
    @Inject
    constructor(
        private val db: AppDatabase,
        private val actualizador: ActualizadorLista,
        private val reclamo: ReclamoFuente,
    ) {
        /**
         * Ejecuta la Actualización de todas las Listas de [fuenteId].
         *
         * @param intento número de intento de WorkManager (`runAttemptCount`).
         */
        suspend fun ejecutar(
            fuenteId: Long,
            causa: String,
            intento: Int,
        ): ResultadoEjecucion {
            if (!reclamo.reclamar(fuenteId)) return ResultadoEjecucion.Coalescido
            return try {
                val listas = db.listaDao().porFuente(fuenteId)
                if (listas.isEmpty()) {
                    ResultadoEjecucion.Fallo(emptyList(), ERROR_SIN_LISTAS)
                } else {
                    decidir(listas.map { lista -> actualizar(lista.id, causa) }, intento)
                }
            } finally {
                reclamo.liberar(fuenteId)
            }
        }

        /**
         * Actualiza una Lista sin propagar excepciones.
         *
         * Un fallo de una Lista no cancela las demás (DC-07 punto 7): cada
         * Lista se procesa de forma independiente y su resultado queda en el
         * resumen. Las credenciales no disponibles se distinguen porque su
         * clasificación es propia.
         */
        private suspend fun actualizar(
            listaId: Long,
            causa: String,
        ): ResumenActualizacion =
            try {
                actualizador.actualizar(listaId, causa)
            } catch (cancelacion: CancellationException) {
                throw cancelacion
            } catch (_: GeneralSecurityException) {
                fallo(listaId, ERROR_CREDENCIALES)
            } catch (_: ProviderException) {
                fallo(listaId, ERROR_CREDENCIALES)
            } catch (_: Exception) {
                fallo(listaId, ERROR_ACTUALIZACION_FALLIDA)
            }

        private fun fallo(
            listaId: Long,
            codigo: String,
        ): ResumenActualizacion =
            ResumenActualizacion(
                listaId = listaId,
                resultado = RESULTADO_FALLIDA,
                error = codigo,
            )

        /**
         * Decide reintento o fallo según DC-07 punto 7.
         *
         * Un solo error permanente o de credenciales cierra el ciclo: reintentar
         * no lo va a resolver. Si todos los errores son transitorios y quedan
         * intentos, se reintenta.
         */
        private fun decidir(
            resumenes: List<ResumenActualizacion>,
            intento: Int,
        ): ResultadoEjecucion {
            val errores = resumenes.mapNotNull { it.error }
            if (errores.isEmpty()) return ResultadoEjecucion.Exito(resumenes)
            val motivo = errores.first()
            val clases = errores.map(ClasificacionErroresActualizacion::clasificar).toSet()
            val definitivo =
                ClaseErrorActualizacion.PERMANENTE in clases ||
                    ClaseErrorActualizacion.CREDENCIALES_NO_DISPONIBLES in clases
            return when {
                definitivo -> ResultadoEjecucion.Fallo(resumenes, motivo)
                intento < MAXIMO_REINTENTOS -> ResultadoEjecucion.Reintentar(resumenes, motivo)
                else -> ResultadoEjecucion.Fallo(resumenes, motivo)
            }
        }

        private companion object {
            /** DC-07 punto 4: como máximo 3 reintentos consecutivos por ejecución. */
            const val MAXIMO_REINTENTOS = 3

            const val RESULTADO_FALLIDA = "fallida"
            const val ERROR_SIN_LISTAS = "sin_listas"
            const val ERROR_CREDENCIALES = "credenciales_no_disponibles"
            const val ERROR_ACTUALIZACION_FALLIDA = "actualizacion_fallida"
        }
    }
