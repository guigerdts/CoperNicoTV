package com.copernicotv.app.data.db.dao

import androidx.room.Dao
import androidx.room.Query
import com.copernicotv.app.data.db.entities.EstadoActualizacionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import kotlinx.coroutines.flow.Flow

/** Conteo de Canales por Fuente, para derivar la disponibilidad (E4-T8). */
data class ConteoCanalesFuente(
    val fuenteId: Long,
    val total: Int,
)

/**
 * Lectura reactiva de los indicadores de S7 (E4-T8).
 *
 * Agrupa las consultas de solo lectura de la pantalla de Fuentes en su propio
 * DAO para no ampliar los DAOs del catálogo. Las Listas se leen de
 * `ListaDao.observarTodas()`, que ya existe.
 */
@Dao
interface IndicadoresFuenteDao {
    @Query("SELECT * FROM Fuente ORDER BY nombre")
    fun observarFuentes(): Flow<List<FuenteEntity>>

    /** Intentos cerrados: el agregado de la Fuente se deriva del de sus Listas. */
    @Query("SELECT * FROM EstadoActualizacion WHERE fin IS NOT NULL ORDER BY fin DESC, id DESC")
    fun observarCompletadas(): Flow<List<EstadoActualizacionEntity>>

    @Query("SELECT fuenteId, COUNT(*) AS total FROM Canal GROUP BY fuenteId")
    fun observarConteoPorFuente(): Flow<List<ConteoCanalesFuente>>
}
