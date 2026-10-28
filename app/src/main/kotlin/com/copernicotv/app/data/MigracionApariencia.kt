package com.copernicotv.app.data

import androidx.room.withTransaction
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaClavePendienteEntity
import com.copernicotv.core.identity.ClavePresentacion
import java.time.Instant

/**
 * Resultado de una ejecución de la migración de `clave_presentacion` (DC-05:630–638).
 *
 * [actualizadas] cuenta las filas antiguas que pasaron a `presentacion:v2:`;
 * [pendientesNuevas] las filas que se registraron por primera vez en
 * `AparienciaClavePendiente`; [omitidas] las filas que ya estaban en v2, ya
 * tenían registro de migración o esperaban una resolución explícita.
 */
data class ResultadoMigracionApariencia(
    val actualizadas: Int,
    val pendientesNuevas: Int,
    val omitidas: Int,
)

/**
 * Migración de la fórmula anterior de `clave_presentacion` a `presentacion:v2:`.
 *
 * La migración usa `group_title` y `tvg_name` persistidos, no depende de una
 * reimportación y nunca fusiona, borra ni sobrescribe filas: los grupos en
 * conflicto quedan registrados como pendientes hasta una resolución explícita
 * del usuario (DC-05:630–638). Toda la ejecución corre en una transacción, de
 * modo que un fallo no deja migraciones parciales.
 */
class MigracionApariencia(
    private val db: AppDatabase,
) {
    /** Ejecuta la migración completa. Es idempotente por contrato. */
    suspend fun ejecutar(ahora: Instant = Instant.now()): ResultadoMigracionApariencia =
        db.withTransaction {
            val dao = db.aparienciaDao()
            val filas = dao.todas()
            val clavesV2 = filas.associate { it.id to ClavePresentacion.clave(it.groupTitle, it.tvgName) }
            val registros = dao.pendientes().associateBy { it.aparienciaId }
            val grupos =
                filas.groupBy { fila ->
                    Triple(fila.listaId, fila.canalId, clavesV2.getValue(fila.id))
                }
            var actualizadas = 0
            var pendientesNuevas = 0
            var omitidas = 0

            for (grupo in grupos.values) {
                val claveV2 = clavesV2.getValue(grupo.first().id)
                val tieneRegistro = grupo.any { fila -> registros.containsKey(fila.id) }

                if (grupo.size == 1 && !tieneRegistro) {
                    val fila = grupo.single()
                    if (estaEnV2(fila.clavePresentacion)) {
                        omitidas++
                    } else {
                        dao.actualizarClavePresentacion(fila.id, claveV2, ahora)
                        actualizadas++
                    }
                    continue
                }

                // Grupo con más de una fila, o con una fila ya registrada:
                // no se actualiza, no se fusiona y no se borra ninguna fila.
                for (fila in grupo) {
                    if (estaEnV2(fila.clavePresentacion) || registros.containsKey(fila.id)) {
                        omitidas++
                        continue
                    }
                    dao.insertarPendiente(
                        AparienciaClavePendienteEntity(
                            aparienciaId = fila.id,
                            clavePresentacionV2 = claveV2,
                            motivo = MOTIVO_COLISION,
                            creadaEn = ahora,
                            resueltaEn = null,
                        ),
                    )
                    pendientesNuevas++
                }
            }

            ResultadoMigracionApariencia(
                actualizadas = actualizadas,
                pendientesNuevas = pendientesNuevas,
                omitidas = omitidas,
            )
        }

    private fun estaEnV2(clave: String): Boolean = clave.startsWith(ClavePresentacion.PREFIJO)

    companion object {
        /** Motivo registrado cuando varias filas comparten la misma presentación v2. */
        const val MOTIVO_COLISION: String = "colision_presentacion_v2"
    }
}
