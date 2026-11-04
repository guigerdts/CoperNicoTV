package com.copernicotv.app

import android.app.Application
import androidx.hilt.work.HiltWorkerFactory
import androidx.work.Configuration
import dagger.hilt.android.HiltAndroidApp
import javax.inject.Inject

/**
 * Aplicación raíz. El grafo Hilt se crea aquí y vive todo el proceso.
 *
 * Implementa `Configuration.Provider` para que WorkManager use el
 * `WorkerFactory` de Hilt: sin esto, `@HiltWorker` no puede instanciarse y el
 * trabajo periódico fallaría al construirse (DC-07 punto 1, E4-T7).
 */
@HiltAndroidApp
class CoperNicoApplication :
    Application(),
    Configuration.Provider {
    @Inject
    lateinit var workerFactory: HiltWorkerFactory

    override val workManagerConfiguration: Configuration
        get() = Configuration.Builder().setWorkerFactory(workerFactory).build()
}
