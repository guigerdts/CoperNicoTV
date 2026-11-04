package com.copernicotv.app.data.actualizacion

import android.util.Log
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Reconciliación de programaciones al arrancar (DC-07 punto 5).
 *
 * Se dispara desde `MainActivity` y no desde `Application.onCreate`: así el
 * arranque de WorkManager no ocurre en cada instancia de prueba de Robolectric
 * ni antes de que el grafo de Hilt esté listo. [iniciar] es idempotente por
 * proceso, porque una recreación de la Activity no debe repetir la
 * reconciliación.
 */
@Singleton
class InicializadorActualizaciones
    @Inject
    constructor(
        private val programador: ProgramadorActualizaciones,
    ) {
        private val iniciado = AtomicBoolean(false)

        /** Vive lo que vive el proceso; la reconciliación es de una sola vez. */
        private val alcance = CoroutineScope(SupervisorJob() + Dispatchers.IO)

        fun iniciar() {
            if (!iniciado.compareAndSet(false, true)) return
            alcance.launch {
                try {
                    programador.reconciliar()
                } catch (cancelacion: CancellationException) {
                    throw cancelacion
                } catch (fallo: Exception) {
                    Log.e(TAG, "No se pudo reconciliar la Actualización automática", fallo)
                }
            }
        }

        private companion object {
            const val TAG = "InicializadorActualizaciones"
        }
    }
