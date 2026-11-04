package com.copernicotv.app.player

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/**
 * Política de reintentos de S20 (E6-T4).
 *
 * Se provee por separado para que las pruebas instrumentadas sustituyan solo la
 * espera y observen la secuencia de reintentos sin depender del reloj; las
 * esperas y el presupuesto siguen siendo los de producción.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModuloReintentos {
    @Provides
    @Singleton
    fun politicaReintentos(): PoliticaReintentos = PoliticaReintentos()
}
