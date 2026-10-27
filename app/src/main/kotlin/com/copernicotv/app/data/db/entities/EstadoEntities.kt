package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "EstadoActualizacion",
    foreignKeys = [
        ForeignKey(
            entity = FuenteEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ListaEntity::class,
            parentColumns = ["id"],
            childColumns = ["listaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fuenteId"), Index("listaId")],
)
data class EstadoActualizacionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fuenteId: Long? = null,
    val listaId: Long? = null,
    val causa: String,
    val versionImport: String,
    val inicio: Instant,
    val fin: Instant? = null,
    val resultado: String? = null,
)

@Entity(
    tableName = "DetalleAusencia",
    foreignKeys = [
        ForeignKey(
            entity = EstadoActualizacionEntity::class,
            parentColumns = ["id"],
            childColumns = ["estadoActualizacionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CanalEntity::class,
            parentColumns = ["id"],
            childColumns = ["canalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("estadoActualizacionId"), Index("canalId")],
)
data class DetalleAusenciaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val estadoActualizacionId: Long,
    val canalId: Long? = null,
    val estado: String,
    val texto: String,
    val creadoEn: Instant,
)

@Entity(
    tableName = "EvidenciaColision",
    foreignKeys = [
        ForeignKey(
            entity = EstadoActualizacionEntity::class,
            parentColumns = ["id"],
            childColumns = ["estadoActualizacionId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("estadoActualizacionId")],
)
data class EvidenciaColisionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val estadoActualizacionId: Long,
    val claveCanonica: String,
    val insumos: String,
    val fecha: Instant,
    val version: String,
)

@Entity(
    tableName = "ObservacionPendienteImportacion",
    foreignKeys = [
        ForeignKey(
            entity = FuenteEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["fuenteId", "claveDeduplicacion"], unique = true)],
)
data class ObservacionPendienteImportacionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fuenteId: Long,
    val importOrigen: String,
    val nombre: String? = null,
    val urlCifrada: ByteArray? = null,
    val urlHmac: ByteArray? = null,
    val logoUrlCifrada: ByteArray? = null,
    val logoUrlHmac: ByteArray? = null,
    val tvgId: String? = null,
    val grupo: String? = null,
    val claveCanonica: String? = null,
    val huellaDeclaracion: String,
    val claveDeduplicacion: String,
    val primeraObservacion: Instant,
    val ultimaObservacion: Instant,
    val conjuntoBc: String? = null,
)
