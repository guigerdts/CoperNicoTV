package com.copernicotv.app.data.db.dao

import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.reconciliacion.FilaHistorica
import com.copernicotv.core.reconciliacion.IdentidadReconciliacion

/**
 * Proyección compacta de un `Canal` para la reconciliación de actualizaciones.
 *
 * Carga solo los campos que el motor de reconciliación necesita. Deja fuera la
 * URL y el logo cifrados, los atributos extra y cualquier otro blob, de modo
 * que la carga de un catálogo de 147 000 canales no retenga las entidades
 * completas en memoria.
 */
data class CanalIdentidadProyeccion(
    val id: Long,
    val nombre: String?,
    val tvgId: String?,
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val claveDerivable: Boolean,
    val estado: EstadoCanal,
    val evidencia: String?,
    val decision: String?,
    val urlStreamHmac: ByteArray,
) {
    /** Convierte la proyección en la identidad que consume `Reconciliador`. */
    fun aFilaHistorica(): FilaHistorica =
        FilaHistorica(
            id = id,
            identidad =
                IdentidadReconciliacion(
                    nombre = nombre,
                    tvgId = tvgId,
                    claveCanonica = claveCanonica,
                    claveUnicidad = claveUnicidad,
                    claveDerivable = claveDerivable,
                    urlStreamHmac = urlStreamHmac.toString(Charsets.UTF_8),
                ),
            estado = estado,
            evidencia = evidencia,
            decision = decision,
        )
}
