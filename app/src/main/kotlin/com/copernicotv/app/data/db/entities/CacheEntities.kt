package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "CacheM3U",
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
    indices = [
        Index(value = ["fuenteId", "listaId", "tipo", "urlHmac"], unique = true),
    ],
)
data class CacheM3UEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fuenteId: Long,
    val listaId: Long,
    val urlCifrada: ByteArray,
    val urlHmac: ByteArray,
    val tipo: String,
    val fetchedAt: Instant,
    val expiresAt: Instant,
    val tamanoBytes: Long,
    val estadoFrescura: String,
    val archivoRef: String,
)

@Entity(
    tableName = "CacheXmltv",
    foreignKeys = [
        ForeignKey(
            entity = FuenteGuiaEntity::class,
            parentColumns = ["id"],
            childColumns = ["fuenteGuiaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["fuenteGuiaId", "tipo", "urlHmac"], unique = true)],
)
data class CacheXmltvEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val fuenteGuiaId: Long,
    val urlCifrada: ByteArray,
    val urlHmac: ByteArray,
    val tipo: String,
    val fetchedAt: Instant,
    val expiresAt: Instant,
    val tamanoBytes: Long,
    val estadoFrescura: String,
    val archivoRef: String,
)
