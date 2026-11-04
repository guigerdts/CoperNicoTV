package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

/** Singleton (`id = 1`) del estado del indice HMAC. No registra `cvM`. */
@Entity(tableName = "EstadoIndiceHmac")
data class EstadoIndiceHmacEntity(
    @PrimaryKey val id: Long = 1,
    val estado: String,
    val versionActiva: String,
    val versionObjetivo: String? = null,
    val iniciadoEn: Instant,
    val actualizadoEn: Instant,
    val filasTotales: Long,
    val filasReconstruidas: Long,
    val filasPendientes: Long,
    val ultimoErrorTipo: String? = null,
)

/** Filas no reconstruibles. `versionOrigen` es `cvM` o `desconocida`; nunca `vN`. */
@Entity(
    tableName = "HmacPendiente",
    indices = [Index(value = ["entidad", "entidadId", "columna"], unique = true)],
)
data class HmacPendienteEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val entidad: String,
    val entidadId: Long,
    val columna: String,
    val versionOrigen: String,
    val motivo: String,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)
