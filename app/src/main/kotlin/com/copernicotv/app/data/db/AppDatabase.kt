package com.copernicotv.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.copernicotv.app.data.db.dao.AparienciaDao
import com.copernicotv.app.data.db.dao.CacheM3UDao
import com.copernicotv.app.data.db.dao.CacheXmltvDao
import com.copernicotv.app.data.db.dao.CanalDao
import com.copernicotv.app.data.db.dao.ColeccionCanalDao
import com.copernicotv.app.data.db.dao.ColeccionDao
import com.copernicotv.app.data.db.dao.EstadoActualizacionDao
import com.copernicotv.app.data.db.dao.EstadoIndiceHmacDao
import com.copernicotv.app.data.db.dao.FuenteDao
import com.copernicotv.app.data.db.dao.GrupoDao
import com.copernicotv.app.data.db.dao.HistorialDao
import com.copernicotv.app.data.db.dao.HmacPendienteDao
import com.copernicotv.app.data.db.dao.ListaDao
import com.copernicotv.app.data.db.dao.PerfilDao
import com.copernicotv.app.data.db.dao.ReconstruccionHmacDao
import com.copernicotv.app.data.db.entities.AparienciaClavePendienteEntity
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.AsociacionEntity
import com.copernicotv.app.data.db.entities.CacheM3UEntity
import com.copernicotv.app.data.db.entities.CacheXmltvEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.DetalleAusenciaEntity
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import com.copernicotv.app.data.db.entities.EstadoIndiceHmacEntity
import com.copernicotv.app.data.db.entities.EvidenciaColisionEntity
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity
import com.copernicotv.app.data.db.entities.GeneracionGuiaEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.HistorialEntity
import com.copernicotv.app.data.db.entities.HmacPendienteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.ObservacionPendienteImportacionEntity
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.app.data.db.entities.OcultacionGrupoEntity
import com.copernicotv.app.data.db.entities.PerfilEntity
import com.copernicotv.app.data.db.entities.PerfilListaEntity
import com.copernicotv.app.data.db.entities.ProgramaEntity

/**
 * Room v1. Contiene las 26 tablas de §6.3.1, sin entidades adicionales.
 *
 * `exportSchema` deja el JSON versionado en `app/schemas` para que las pruebas
 * de esquema y las migraciones futuras tengan una referencia real.
 */
@Database(
    version = 1,
    exportSchema = true,
    entities = [
        FuenteEntity::class,
        ListaEntity::class,
        GrupoEntity::class,
        CanalEntity::class,
        AparienciaEntity::class,
        AparienciaClavePendienteEntity::class,
        FavoritoEntity::class,
        OcultacionCanalEntity::class,
        OcultacionGrupoEntity::class,
        HistorialEntity::class,
        ColeccionEntity::class,
        ColeccionCanalEntity::class,
        PerfilEntity::class,
        PerfilListaEntity::class,
        FuenteGuiaEntity::class,
        GeneracionGuiaEntity::class,
        ProgramaEntity::class,
        AsociacionEntity::class,
        EstadoActualizacionEntity::class,
        DetalleAusenciaEntity::class,
        EvidenciaColisionEntity::class,
        ObservacionPendienteImportacionEntity::class,
        CacheM3UEntity::class,
        CacheXmltvEntity::class,
        EstadoIndiceHmacEntity::class,
        HmacPendienteEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun estadoActualizacionDao(): EstadoActualizacionDao

    abstract fun estadoIndiceHmacDao(): EstadoIndiceHmacDao

    abstract fun hmacPendienteDao(): HmacPendienteDao

    abstract fun reconstruccionHmacDao(): ReconstruccionHmacDao

    abstract fun fuenteDao(): FuenteDao

    abstract fun listaDao(): ListaDao

    abstract fun grupoDao(): GrupoDao

    abstract fun canalDao(): CanalDao

    abstract fun cacheM3UDao(): CacheM3UDao

    abstract fun cacheXmltvDao(): CacheXmltvDao

    abstract fun aparienciaDao(): AparienciaDao

    abstract fun coleccionDao(): ColeccionDao

    abstract fun coleccionCanalDao(): ColeccionCanalDao

    abstract fun historialDao(): HistorialDao

    abstract fun perfilDao(): PerfilDao

    companion object {
        const val NOMBRE = "copernicotv.db"
    }
}
