package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(tableName = "FuenteGuia")
data class FuenteGuiaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val urlCifrada: ByteArray? = null,
    val urlHmac: ByteArray? = null,
    val generacionPublicadaId: Long? = null,
    val retencionVentanaPasadoDias: Long? = null,
    val retencionVentanaFuturoDias: Long? = null,
    val retencionMaxProgramas: Long? = null,
    val retencionPresupuestoBytes: Long? = null,
    val usarRetencionGlobal: Boolean,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)

@Entity(
    tableName = "GeneracionGuia",
    foreignKeys = [
        ForeignKey(
            entity = FuenteGuiaEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteGuiaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fuenteGuiaId")],
)
data class GeneracionGuiaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fuenteGuiaId: Long,
    val version: String,
    val estado: String,
    val creadoEn: Instant,
    val publicadaEn: Instant? = null,
)

@Entity(
    tableName = "Programa",
    foreignKeys = [
        ForeignKey(
            entity = GeneracionGuiaEntity::class,
            parentColumns = ["id"],
            childColumns = ["generacionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FuenteGuiaEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteGuiaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index("generacionId", "channelIdTexto", "inicio"),
        Index("fuenteGuiaId", "inicio"),
    ],
)
data class ProgramaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val generacionId: Long,
    val fuenteGuiaId: Long,
    val channelIdTexto: String,
    val titulo: String,
    val inicio: Instant,
    val fin: Instant,
    val descripcion: String? = null,
)

@Entity(
    tableName = "Asociacion",
    foreignKeys = [
        ForeignKey(
            entity = CanalEntity::class,
            parentColumns = ["id"],
            childColumns = ["canalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = FuenteGuiaEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteGuiaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["fuenteGuiaId", "channelIdTexto"], unique = true),
        Index("canalId"),
    ],
)
data class AsociacionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canalId: Long,
    val fuenteGuiaId: Long,
    val channelIdTexto: String,
    val estado: String,
    val origen: String,
    val huellaVersion: Int,
    val huellaValor: String? = null,
    val senalesFuertes: String,
    val ultimaObservacion: Instant,
    val confirmadaEn: Instant? = null,
    val actualizadaEn: Instant,
)
