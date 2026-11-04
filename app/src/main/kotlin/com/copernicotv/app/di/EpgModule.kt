package com.copernicotv.app.di

import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.FuenteGuiaDao
import com.copernicotv.app.data.db.dao.GeneracionGuiaDao
import com.copernicotv.app.data.db.dao.ProgramaDao
import com.copernicotv.core.xmltv.ParserXmltv
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent

/**
 * Dependencias de EPG (E5).
 *
 * Se separan de `AppModule` para no superar el umbral de funciones del módulo
 * y para que la guía tenga un punto de composición propio.
 */
@Module
@InstallIn(SingletonComponent::class)
object EpgModule {
    @Provides
    fun fuenteGuiaDao(db: AppDatabase): FuenteGuiaDao = db.fuenteGuiaDao()

    @Provides
    fun generacionGuiaDao(db: AppDatabase): GeneracionGuiaDao = db.generacionGuiaDao()

    @Provides
    fun programaDao(db: AppDatabase): ProgramaDao = db.programaDao()

    @Provides
    fun parserXmltv(): ParserXmltv = ParserXmltv()
}
