package com.copernicotv.app.data.db.entities

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey
import java.time.Instant

@Entity(
    tableName = "Perfil",
    indices = [Index(value = ["nombreNormalizado"], unique = true)],
)
data class PerfilEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val nombre: String,
    val nombreNormalizado: String,
    val creadoEn: Instant,
    val actualizadoEn: Instant,
)

@Entity(
    tableName = "PerfilLista",
    primaryKeys = ["perfilId", "listaId"],
    foreignKeys = [
        ForeignKey(
            entity = PerfilEntity::class,
            parentColumns = ["id"],
            childColumns = ["perfilId"],
            onDelete = ForeignKey.CASCADE,
        ),
        ForeignKey(
            entity = ListaEntity::class,
            parentColumns = ["id"],
            childColumns = ["listaId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index("listaId")],
)
data class PerfilListaEntity(
    val perfilId: Long,
    val listaId: Long,
)
