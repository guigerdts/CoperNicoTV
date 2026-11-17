package com.copernicotv.app.player

import android.content.Context
import com.copernicotv.core.player.ConfiguracionImagen
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Política de recuperación de E7-T10 (S4) y observadores de conectividad.
 *
 * Se proveen por separado para que las pruebas instrumentadas sustituyan solo
 * la espera y la conectividad: el backoff, el presupuesto y la detección de
 * red siguen siendo los de producción. Cada sesión crea su propio observador
 * de conectividad con la fábrica y lo libera al cerrarse.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModuloReintentos {
    @Provides
    @Singleton
    fun politicaReintentos(): PoliticaReintentos = PoliticaReintentos()

    @Provides
    @Singleton
    fun configuracionImagen(): ConfiguracionImagen = ConfiguracionImagen()

    @Provides
    @Singleton
    fun fabricaConectividad(
        @ApplicationContext contexto: Context,
    ): FabricaConectividad = FabricaConectividad { ConectividadAndroid(contexto) }
}
