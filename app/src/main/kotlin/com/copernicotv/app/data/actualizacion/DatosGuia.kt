package com.copernicotv.app.data.actualizacion

import androidx.work.Data

/**
 * Datos de los trabajos de guía EPG (S7, H-04).
 *
 * Igual que en la Actualización de Listas, WorkManager solo ofrece tres estados
 * finales. El código de [CLAVE_RESULTADO] distingue una pasada que no hizo
 * trabajo de una que sí, y la interfaz nunca presenta `sin_trabajo` como una
 * actualización exitosa.
 *
 * Solo viajan identificadores y códigos: ninguna URL, credencial ni nombre.
 */
internal object DatosGuia {
    const val CLAVE_FUENTE_GUIA: String = "fuente_guia_id"
    const val CLAVE_RESULTADO: String = "resultado"

    const val RESULTADO_COMPLETADO: String = "completado"
    const val RESULTADO_SIN_TRABAJO: String = "sin_trabajo"
    const val RESULTADO_FALLIDO: String = "fallido"

    fun de(fuenteGuiaId: Long): Data = construir(fuenteGuiaId, RESULTADO_COMPLETADO)

    fun sinTrabajo(fuenteGuiaId: Long = 0L): Data = construir(fuenteGuiaId, RESULTADO_SIN_TRABAJO)

    fun fallo(fuenteGuiaId: Long = 0L): Data = construir(fuenteGuiaId, RESULTADO_FALLIDO)

    fun fuenteGuiaId(datos: Data): Long? = datos.getLong(CLAVE_FUENTE_GUIA, -1L).takeIf { it > 0L }

    fun resultado(datos: Data): String? = datos.getString(CLAVE_RESULTADO)

    private fun construir(
        fuenteGuiaId: Long,
        resultado: String,
    ): Data =
        Data
            .Builder()
            .putLong(CLAVE_FUENTE_GUIA, fuenteGuiaId)
            .putString(CLAVE_RESULTADO, resultado)
            .build()
}
