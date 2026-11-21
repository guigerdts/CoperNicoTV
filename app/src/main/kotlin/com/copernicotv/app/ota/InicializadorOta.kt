package com.copernicotv.app.ota

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
 * Reconciliación del OTA al arrancar.
 *
 * Se dispara desde `MainActivity`, igual que la de listas: no se toca
 * WorkManager en `Application.onCreate` para no arrancarlo en cada instancia de
 * Robolectric ni antes de que el grafo de Hilt esté listo. [iniciar] es
 * idempotente por proceso.
 */
@Singleton
class InicializadorOta
    @Inject
    constructor(
        private val programador: ProgramadorOta,
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
                    Log.e(TAG, "No se pudo reconciliar la actualización de la app", fallo)
                }
            }
        }

        private companion object {
            const val TAG = "InicializadorOta"
        }
    }
