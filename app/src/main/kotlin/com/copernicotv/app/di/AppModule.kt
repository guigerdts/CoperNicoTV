package com.copernicotv.app.di

import android.content.Context
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.sqlite.db.SupportSQLiteDatabase
import com.copernicotv.app.crypto.ClavesApp
import com.copernicotv.app.crypto.ClavesKeystore
import com.copernicotv.app.data.TiemposHttp
import com.copernicotv.app.data.cache.AlmacenCache
import com.copernicotv.app.data.cache.Descargador
import com.copernicotv.app.data.cache.DescargadorHttp
import com.copernicotv.app.data.cache.RepositorioCache
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.dao.AparienciaDao
import com.copernicotv.app.data.db.dao.CacheM3UDao
import com.copernicotv.app.data.db.dao.CacheXmltvDao
import com.copernicotv.app.data.db.dao.CanalDao
import com.copernicotv.app.data.db.dao.ColeccionCanalDao
import com.copernicotv.app.data.db.dao.ColeccionDao
import com.copernicotv.app.data.db.dao.DetalleAusenciaDao
import com.copernicotv.app.data.db.dao.EstadoActualizacionDao
import com.copernicotv.app.data.db.dao.EstadoIndiceHmacDao
import com.copernicotv.app.data.db.dao.EvidenciaColisionDao
import com.copernicotv.app.data.db.dao.FavoritoDao
import com.copernicotv.app.data.db.dao.FuenteDao
import com.copernicotv.app.data.db.dao.GrupoDao
import com.copernicotv.app.data.db.dao.HistorialDao
import com.copernicotv.app.data.db.dao.HmacPendienteDao
import com.copernicotv.app.data.db.dao.ListaDao
import com.copernicotv.app.data.db.dao.ObservacionPendienteDao
import com.copernicotv.app.data.db.dao.OcultacionCanalDao
import com.copernicotv.app.data.db.dao.OcultacionGrupoDao
import com.copernicotv.app.data.db.dao.PerfilDao
import com.copernicotv.app.data.db.dao.ReconstruccionHmacDao
import com.copernicotv.core.identity.CaseFolder
import com.copernicotv.core.identity.CaseFolderCompleto
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import java.io.File
import java.time.Instant
import javax.inject.Singleton

/** Grafo de la aplicación: base Room v1, DAOs y claves de Keystore. */
@Module
@InstallIn(SingletonComponent::class)
object AppModule {
    private const val DIRECTORIO_CACHE = "cache-transporte"

    @Provides
    @Singleton
    fun baseDeDatos(
        @ApplicationContext contexto: Context,
    ): AppDatabase =
        Room
            .databaseBuilder(contexto, AppDatabase::class.java, AppDatabase.NOMBRE)
            .addCallback(SembrarEstadoIndiceHmac)
            .build()

    @Provides
    fun tiemposHttp(): TiemposHttp = TiemposHttp()

    @Provides
    fun fuenteDao(db: AppDatabase): FuenteDao = db.fuenteDao()

    @Provides
    fun listaDao(db: AppDatabase): ListaDao = db.listaDao()

    @Provides
    fun grupoDao(db: AppDatabase): GrupoDao = db.grupoDao()

    @Provides
    fun canalDao(db: AppDatabase): CanalDao = db.canalDao()

    @Provides
    fun aparienciaDao(db: AppDatabase): AparienciaDao = db.aparienciaDao()

    @Provides
    fun detalleAusenciaDao(db: AppDatabase): DetalleAusenciaDao = db.detalleAusenciaDao()

    @Provides
    fun evidenciaColisionDao(db: AppDatabase): EvidenciaColisionDao = db.evidenciaColisionDao()

    @Provides
    fun observacionPendienteDao(db: AppDatabase): ObservacionPendienteDao = db.observacionPendienteDao()

    @Provides
    fun estadoActualizacionDao(db: AppDatabase): EstadoActualizacionDao = db.estadoActualizacionDao()

    @Provides
    fun coleccionDao(db: AppDatabase): ColeccionDao = db.coleccionDao()

    @Provides
    fun historialDao(db: AppDatabase): HistorialDao = db.historialDao()

    @Provides
    fun favoritoDao(db: AppDatabase): FavoritoDao = db.favoritoDao()

    @Provides
    fun ocultacionCanalDao(db: AppDatabase): OcultacionCanalDao = db.ocultacionCanalDao()

    @Provides
    fun ocultacionGrupoDao(db: AppDatabase): OcultacionGrupoDao = db.ocultacionGrupoDao()

    @Provides
    fun perfilDao(db: AppDatabase): PerfilDao = db.perfilDao()

    @Provides
    fun estadoIndiceHmacDao(db: AppDatabase): EstadoIndiceHmacDao = db.estadoIndiceHmacDao()

    @Provides
    fun hmacPendienteDao(db: AppDatabase): HmacPendienteDao = db.hmacPendienteDao()

    @Provides
    fun reconstruccionHmacDao(db: AppDatabase): ReconstruccionHmacDao = db.reconstruccionHmacDao()

    @Provides
    fun cacheM3UDao(db: AppDatabase): CacheM3UDao = db.cacheM3UDao()

    @Provides
    fun cacheXmltvDao(db: AppDatabase): CacheXmltvDao = db.cacheXmltvDao()

    @Provides
    @Singleton
    fun almacenCache(
        @ApplicationContext contexto: Context,
        claves: ClavesApp,
    ): AlmacenCache = AlmacenCache(File(contexto.filesDir, DIRECTORIO_CACHE), claves)

    @Provides
    @Singleton
    fun descargadorHttp(): Descargador = DescargadorHttp()

    @Provides
    @Singleton
    fun repositorioCache(
        db: AppDatabase,
        almacen: AlmacenCache,
        descargador: Descargador,
        claves: ClavesApp,
    ): RepositorioCache = RepositorioCache(db, almacen, descargador, claves)

    /** Case folding completo conforme a Unicode 15.1.0 para la identidad. */
    @Provides
    @Singleton
    fun caseFolder(): CaseFolder = CaseFolderCompleto

    @Provides
    fun coleccionCanalDao(db: AppDatabase): ColeccionCanalDao = db.coleccionCanalDao()

    @Provides
    @Singleton
    fun claves(implementacion: ClavesKeystore): ClavesApp = implementacion
}

/**
 * Siembra la fila singleton de `EstadoIndiceHmac` (`id = 1`) al crear la base.
 *
 * El índice HMAC se declara operativo con la generación `v1` y sin filas
 * pendientes; E2-T6 es dueño de la reconstrucción y de sus transiciones.
 */
private object SembrarEstadoIndiceHmac : RoomDatabase.Callback() {
    override fun onCreate(db: SupportSQLiteDatabase) {
        val ahora = Instant.now().toEpochMilli()
        db.execSQL(
            """
            INSERT OR IGNORE INTO EstadoIndiceHmac
                (id, estado, versionActiva, versionObjetivo, iniciadoEn, actualizadoEn,
                 filasTotales, filasReconstruidas, filasPendientes, ultimoErrorTipo)
            VALUES (1, 'operativo', 'hmac:v1', NULL, ?, ?, 0, 0, 0, NULL)
            """.trimIndent(),
            arrayOf(ahora, ahora),
        )
    }
}
