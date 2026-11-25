package com.copernicotv.app.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.TypeConverters
import com.copernicotv.app.data.db.dao.AparienciaDao
import com.copernicotv.app.data.db.dao.AsociacionDao
import com.copernicotv.app.data.db.dao.CacheM3UDao
import com.copernicotv.app.data.db.dao.CacheXmltvDao
import com.copernicotv.app.data.db.dao.CanalDao
import com.copernicotv.app.data.db.dao.ColeccionCanalDao
import com.copernicotv.app.data.db.dao.ColeccionDao
import com.copernicotv.app.data.db.dao.ConexionDao
import com.copernicotv.app.data.db.dao.DetalleAusenciaDao
import com.copernicotv.app.data.db.dao.DiagnosticoDao
import com.copernicotv.app.data.db.dao.EstadoActualizacionDao
import com.copernicotv.app.data.db.dao.EstadoIndiceHmacDao
import com.copernicotv.app.data.db.dao.EvidenciaColisionDao
import com.copernicotv.app.data.db.dao.FavoritoDao
import com.copernicotv.app.data.db.dao.FuenteDao
import com.copernicotv.app.data.db.dao.FuenteGuiaDao
import com.copernicotv.app.data.db.dao.GeneracionGuiaDao
import com.copernicotv.app.data.db.dao.GrupoDao
import com.copernicotv.app.data.db.dao.HistorialDao
import com.copernicotv.app.data.db.dao.HmacPendienteDao
import com.copernicotv.app.data.db.dao.IndicadoresFuenteDao
import com.copernicotv.app.data.db.dao.ListaDao
import com.copernicotv.app.data.db.dao.ObservacionPendienteDao
import com.copernicotv.app.data.db.dao.OcultacionCanalDao
import com.copernicotv.app.data.db.dao.OcultacionGrupoDao
import com.copernicotv.app.data.db.dao.PerfilDao
import com.copernicotv.app.data.db.dao.ProgramaDao
import com.copernicotv.app.data.db.dao.ReconstruccionHmacDao
import com.copernicotv.app.data.db.entities.AparienciaClavePendienteEntity
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.AsociacionEntity
import com.copernicotv.app.data.db.entities.CacheM3UEntity
import com.copernicotv.app.data.db.entities.CacheXmltvEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.ConexionEntity
import com.copernicotv.app.data.db.entities.DetalleAusenciaEntity
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import com.copernicotv.app.data.db.entities.EstadoIndiceHmacEntity
import com.copernicotv.app.data.db.entities.EventoDiagnosticoEntity
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
import com.copernicotv.app.data.db.entities.SesionDiagnosticoEntity

/**
 * Room v5. Contiene las 28 tablas de §6.3.1 y del diagnóstico persistente de
 * reproducción, más `Conexion` (gestión de conexiones IPTV).
 *
 * Cambios de la v5, todos aditivos:
 * - Tabla `Conexion`: perfiles Xtream Codes y M3U/M3U8 con secretos cifrados.
 * - `FuenteGuia`: origen, estado, conexión administradora, última actualización
 *   y último error.
 * - `Programa`: subtítulo, categoría, `episode-num` e idioma.
 *
 * Ninguna fila existente se altera ni se pierde.
 */
@Database(
    version = 5,
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
        SesionDiagnosticoEntity::class,
        EventoDiagnosticoEntity::class,
        ConexionEntity::class,
    ],
)
@TypeConverters(Converters::class)
abstract class AppDatabase : RoomDatabase() {
    abstract fun diagnosticoDao(): DiagnosticoDao

    abstract fun estadoActualizacionDao(): EstadoActualizacionDao

    abstract fun detalleAusenciaDao(): DetalleAusenciaDao

    abstract fun evidenciaColisionDao(): EvidenciaColisionDao

    abstract fun observacionPendienteDao(): ObservacionPendienteDao

    abstract fun estadoIndiceHmacDao(): EstadoIndiceHmacDao

    abstract fun hmacPendienteDao(): HmacPendienteDao

    abstract fun reconstruccionHmacDao(): ReconstruccionHmacDao

    abstract fun fuenteDao(): FuenteDao

    abstract fun fuenteGuiaDao(): FuenteGuiaDao

    abstract fun generacionGuiaDao(): GeneracionGuiaDao

    abstract fun programaDao(): ProgramaDao

    abstract fun listaDao(): ListaDao

    abstract fun grupoDao(): GrupoDao

    abstract fun canalDao(): CanalDao

    abstract fun cacheM3UDao(): CacheM3UDao

    abstract fun cacheXmltvDao(): CacheXmltvDao

    abstract fun aparienciaDao(): AparienciaDao

    abstract fun asociacionDao(): AsociacionDao

    abstract fun coleccionDao(): ColeccionDao

    abstract fun coleccionCanalDao(): ColeccionCanalDao

    abstract fun historialDao(): HistorialDao

    abstract fun favoritoDao(): FavoritoDao

    abstract fun ocultacionCanalDao(): OcultacionCanalDao

    abstract fun ocultacionGrupoDao(): OcultacionGrupoDao

    abstract fun perfilDao(): PerfilDao

    abstract fun indicadoresFuenteDao(): IndicadoresFuenteDao

    abstract fun conexionDao(): ConexionDao

    companion object {
        const val NOMBRE = "copernicotv.db"
    }
}
