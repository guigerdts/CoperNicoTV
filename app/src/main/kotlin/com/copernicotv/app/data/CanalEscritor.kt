package com.copernicotv.app.data

import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.core.modelo.FilaCanal
import com.copernicotv.core.modelo.InvariantesCanal
import com.copernicotv.core.modelo.ViolacionCanal

/**
 * Rechazo del escritor de `Canal` por incumplir las invariantes de §6.3.2.
 *
 * El código informado es el de la primera violación, para que el llamador
 * pueda propagarlo como error tipado sin inventar una taxonomía nueva.
 */
class CanalRechazado(
    val violaciones: List<ViolacionCanal>,
) : ImportacionRechazada(violaciones.first().codigo)

/**
 * Escritor de `Canal` que valida las invariantes de identidad antes de persistir.
 *
 * `InvariantesCanal` es la definición normativa (§6.3.2); este escritor es el
 * punto donde se convierte en rechazo efectivo: una fila inválida no llega a la
 * base y la transacción que la contiene se revierte.
 */
class CanalEscritor(
    private val db: AppDatabase,
) {
    /**
     * Valida y persiste un Canal.
     *
     * @param tvgIdUnicoEnFuente `true` solo si el `tvg-id` es válido y único en
     *   la Fuente; determina la forma exigida de `clave_unicidad`.
     * @throws CanalRechazado si la fila incumple alguna invariante.
     */
    suspend fun insertar(
        entidad: CanalEntity,
        tvgIdUnicoEnFuente: Boolean,
    ): Long {
        val violaciones =
            InvariantesCanal.validar(
                FilaCanal(
                    nombre = entidad.nombre,
                    estado = entidad.estado,
                    claveDerivable = entidad.claveDerivable,
                    claveCanonica = entidad.claveCanonica,
                    claveUnicidad = entidad.claveUnicidad,
                    tvgId = entidad.tvgId,
                    urlStreamCifradaPresente = entidad.urlStreamCifrada.isNotEmpty(),
                    urlStreamHmacPresente = entidad.urlStreamHmac.isNotEmpty(),
                ),
                tvgIdUnicoEnFuente,
            )
        if (violaciones.isNotEmpty()) throw CanalRechazado(violaciones)
        return db.canalDao().insertar(entidad)
    }

    /**
     * Valida y actualiza un Canal existente.
     *
     * Mantiene la misma fila y, por lo tanto, sus FKs. Solo cambian los campos
     * derivados de la declaración editada.
     */
    suspend fun actualizar(
        entidad: CanalEntity,
        tvgIdUnicoEnFuente: Boolean,
    ): Int {
        val violaciones =
            InvariantesCanal.validar(
                FilaCanal(
                    nombre = entidad.nombre,
                    estado = entidad.estado,
                    claveDerivable = entidad.claveDerivable,
                    claveCanonica = entidad.claveCanonica,
                    claveUnicidad = entidad.claveUnicidad,
                    tvgId = entidad.tvgId,
                    urlStreamCifradaPresente = entidad.urlStreamCifrada.isNotEmpty(),
                    urlStreamHmacPresente = entidad.urlStreamHmac.isNotEmpty(),
                ),
                tvgIdUnicoEnFuente,
            )
        if (violaciones.isNotEmpty()) throw CanalRechazado(violaciones)
        return db.canalDao().actualizar(entidad)
    }
}
