package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import com.copernicotv.app.data.db.entities.FuenteGuiaEntity

/**
 * Fuentes de guía EPG.
 *
 * La tabla existía desde el esquema v1 sin DAO. Se añade el mínimo que la
 * fusión de Canales necesita para satisfacer la FK de `Asociacion`.
 */
@Dao
interface FuenteGuiaDao {
    @Insert
    suspend fun insertar(fila: FuenteGuiaEntity): Long

    @Query("SELECT * FROM FuenteGuia WHERE id = :id")
    suspend fun porId(id: Long): FuenteGuiaEntity?

    @Query("SELECT COUNT(*) FROM FuenteGuia")
    suspend fun contar(): Int
}
