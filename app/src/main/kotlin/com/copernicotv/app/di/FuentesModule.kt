package com.copernicotv.app.di

import com.copernicotv.app.data.IndicadoresFuenteRepositorio
import com.copernicotv.app.data.IndicadoresFuenteRepositorioRoom
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Grafo de la pantalla de Fuentes (E4-T8). */
@Module
@InstallIn(SingletonComponent::class)
object FuentesModule {
    /** Indicadores de S7 por Fuente. */
    @Provides
    @Singleton
    fun indicadoresFuente(implementacion: IndicadoresFuenteRepositorioRoom): IndicadoresFuenteRepositorio = implementacion
}
