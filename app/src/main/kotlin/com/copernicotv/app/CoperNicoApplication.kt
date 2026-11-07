package com.copernicotv.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import com.copernicotv.app.data.GuardiaArranqueIndiceHmac
import dagger.hilt.android.HiltAndroidApp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Aplicación raíz. El grafo Hilt se crea aquí y vive todo el proceso.
 *
 * Implementa `Configuration.Provider` para que WorkManager use el
 * `WorkerFactory` de Hilt: sin esto, `@HiltWorker` no puede instanciarse y el
 * trabajo periódico fallaría al construirse (DC-07 punto 1, E4-T7).
 *
 * En `onCreate` lanza la [GuardiaArranqueIndiceHmac] en segundo plano (DC-04
 * §7, E2-T6): inicializa la clave `v1` en una instalación virgen o declara la
 * pérdida de la clave HMAC sin regenerarla; el alcance vive en un campo para
 * que el trabajo sobreviva al arranque.
 */
@HiltAndroidApp
class CoperNicoApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    @Inject
    lateinit var guardiaArranqueIndiceHmac: GuardiaArranqueIndiceHmac

    private val alcanceArranque = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    override fun onCreate() {
        super.onCreate()
        alcanceArranque.launch {
            guardiaArranqueIndiceHmac.evaluar()
        }
    }

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
