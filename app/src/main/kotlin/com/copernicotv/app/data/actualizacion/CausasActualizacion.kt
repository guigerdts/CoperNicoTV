package com.copernicotv.app.data.actualizacion

/**
 * Causa de una Actualización, según DC-07 punto 9.
 *
 * La causa se persiste en el Estado de Actualización por Lista. Los valores
 * coinciden con los que ya usa `ActualizacionRepositorio` para que el Estado
 * siga siendo comparable entre la Actualización manual y la automática.
 */
object CausasActualizacion {
    const val MANUAL: String = "manual"
    const val AUTOMATICA: String = "automatica"
}
