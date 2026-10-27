package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import com.copernicotv.core.identity.EstadoCanal
import java.time.Instant

@Entity(
    tableName = "Fuente",
    indices = [Index("nombre", unique = true)],
)
data class FuenteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val estado: String,
    val intervaloMinutos: Long? = null,
    val usarIntervaloGlobal: Boolean,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)

@Entity(
    tableName = "Lista",
    foreignKeys = [
        ForeignKey(
            entity = FuenteEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("fuenteId")],
)
data class ListaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fuenteId: Long,
    val nombre: String,
    val ubicacionCifrada: ByteArray,
    val ubicacionHmac: ByteArray,
    val esquema: String,
    val orden: Int,
    val estado: String,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)

@Entity(
    tableName = "Grupo",
    foreignKeys = [
        ForeignKey(
            entity = ListaEntity::class,
            parentColumns = ["id"],
            childColumns = ["listaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["listaId", "nombre"], unique = true)],
)
data class GrupoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listaId: Long,
    val nombre: String,
)

@Entity(
    tableName = "Canal",
    foreignKeys = [
        ForeignKey(
            entity = FuenteEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["fuenteId", "claveUnicidad"], unique = true),
        Index("fuenteId", "claveCanonica"),
        Index("fuenteId", "urlStreamHmac"),
        Index("fuenteId", "tvgId"),
        Index("nombre"),
    ],
)
data class CanalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fuenteId: Long,
    val nombre: String? = null,
    val urlStreamCifrada: ByteArray,
    val urlStreamHmac: ByteArray,
    val logoUrlCifrada: ByteArray? = null,
    val logoUrlHmac: ByteArray? = null,
    val tvgId: String? = null,
    val claveCanonica: String? = null,
    val claveUnicidad: String? = null,
    val claveDerivable: Boolean,
    val estado: EstadoCanal = EstadoCanal.A,
    val atributosExtra: String? = null,
    val decision: String? = null,
    val fechaDecision: Instant? = null,
    val evidencia: String? = null,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)

@Entity(
    tableName = "Apariencia",
    foreignKeys = [
        ForeignKey(
            entity = ListaEntity::class,
            parentColumns = ["id"],
            childColumns = ["listaId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CanalEntity::class,
            parentColumns = ["id"],
            childColumns = ["canalId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = GrupoEntity::class,
            parentColumns = ["id"],
            childColumns = ["grupoId"],
            onDelete = ForeignKey.SET_NULL,
        ),
    ],
    indices = [
        Index(value = ["listaId", "canalId", "clavePresentacion"], unique = true),
        Index("listaId", "canalId"),
        Index("grupoId"),
        Index("listaId", "orden"),
    ],
)
data class AparienciaEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val listaId: Long,
    val canalId: Long,
    val grupoId: Long? = null,
    val orden: Int,
    val groupTitle: String? = null,
    val tvgName: String? = null,
    val clavePresentacion: String,
    val repeticiones: Int,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)

@Entity(
    tableName = "AparienciaClavePendiente",
    foreignKeys = [
        ForeignKey(
            entity = AparienciaEntity::class,
            parentColumns = ["id"],
            childColumns = ["aparienciaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("clavePresentacionV2")],
)
data class AparienciaClavePendienteEntity(
    @PrimaryKey val aparienciaId: Long,
    val clavePresentacionV2: String,
    val motivo: String,
    val creadaEn: Instant,
    val resueltaEn: Instant? = null,
)

@Entity(
    tableName = "Favorito",
    foreignKeys = [
        ForeignKey(
            entity = CanalEntity::class,
            parentColumns = ["id"],
            childColumns = ["canalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["canalId"], unique = true)],
)
data class FavoritoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canalId: Long,
    val creadoEn: Instant,
)

@Entity(
    tableName = "OcultacionCanal",
    foreignKeys = [
        ForeignKey(
            entity = CanalEntity::class,
            parentColumns = ["id"],
            childColumns = ["canalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["canalId"], unique = true)],
)
data class OcultacionCanalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canalId: Long,
    val creadoEn: Instant,
)

@Entity(
    tableName = "OcultacionGrupo",
    foreignKeys = [
        ForeignKey(
            entity = GrupoEntity::class,
            parentColumns = ["id"],
            childColumns = ["grupoId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["grupoId"], unique = true)],
)
data class OcultacionGrupoEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val grupoId: Long,
    val creadoEn: Instant,
)

@Entity(
    tableName = "Historial",
    foreignKeys = [
        ForeignKey(
            entity = CanalEntity::class,
            parentColumns = ["id"],
            childColumns = ["canalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [
        Index(value = ["canalId"], unique = true),
        Index("fecha", "canalId"),
    ],
)
data class HistorialEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val canalId: Long,
    val fecha: Instant,
)

@Entity(tableName = "Coleccion")
data class ColeccionEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)

@Entity(
    tableName = "ColeccionCanal",
    foreignKeys = [
        ForeignKey(
            entity = ColeccionEntity::class,
            parentColumns = ["id"],
            childColumns = ["coleccionId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = CanalEntity::class,
            parentColumns = ["id"],
            childColumns = ["canalId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("coleccionId", "canalId")],
)
data class ColeccionCanalEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val coleccionId: Long,
    val canalId: Long,
    val posicion: Int,
)
