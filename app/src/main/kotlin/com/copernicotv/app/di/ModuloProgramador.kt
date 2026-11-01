package com.copernicotv.app.di

import android.content.Context
import androidx.work.WorkManager
import com.copernicotv.app.data.actualizacion.ProgramadorActualizaciones
import com.copernicotv.app.data.actualizacion.ProgramadorWorkManager
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Planificador de la Actualización (E4-T7).
 *
 * Es un módulo propio para que las pruebas instrumentadas lo reemplacen por un
 * doble con `@TestInstallIn`: `HiltTestApplication` no implementa
 * `Configuration.Provider`, así que WorkManager no puede inicializarse en ese
 * entorno. El doble ejecuta el coordinador real, de modo que las pruebas de
 * extremo a extremo siguen recorriendo descarga, reconciliación y transacción.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModuloProgramador {
    /**
     * WorkManager del proceso.
     *
     * `getInstance` inicializa WorkManager con la `Configuration` del
     * `Application` si aún no existe (DC-07 punto 1). Se provee como binding
     * para que el planificador pueda pedirlo de forma diferida.
     */
    @Provides
    @Singleton
    fun workManager(
        @ApplicationContext contexto: Context,
    ): WorkManager = WorkManager.getInstance(contexto)

    @Provides
    @Singleton
    fun programador(implementacion: ProgramadorWorkManager): ProgramadorActualizaciones = implementacion
}
