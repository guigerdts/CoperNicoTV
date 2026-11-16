package com.copernicotv.app.di

import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.DiagnosticoDao
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Provisión del DAO del diagnóstico persistente de reproducción (E7-T10, S6a).
 *
 * Vive en un módulo propio para no inflar [AppModule]; el
 * [com.copernicotv.app.data.DiagnosticoRepositorio] se inyecta con su
 * constructor anotado y no necesita proveedor.
 */
@Module
@InstallIn(SingletonComponent::class)
object ModuloDiagnostico {
    @Provides
    fun diagnosticoDao(db: AppDatabase): DiagnosticoDao = db.diagnosticoDao()
}
