package com.copernicotv.app.data.actualizacion

import androidx.work.Data
import com.copernicotv.app.data.ResumenActualizacion

/**
 * Datos que viajan en el `WorkRequest` de la Actualización (DC-07 puntos 5, 6 y 9).
 *
 * El trabajo periódico y el manual comparten el mismo formato: la Fuente y la
 * causa en las claves propias, y el resultado por Lista en un campo único
 * codificado. WorkManager limita `Data` a 10 KB y una Fuente tiene pocas Listas,
 * así que el resultado agregado cabe de sobra sin abrir una tabla nueva.
 *
 * La codificación solo transporta códigos e identificadores numéricos; no
 * incluye nombres, URLs ni texto libre, así que no puede filtrar credenciales
 * al registro de WorkManager (DC-04).
 */
internal object DatosActualizacion {
    const val CLAVE_FUENTE: String = "fuente_id"
    const val CLAVE_CAUSA: String = "causa"
    const val CLAVE_RESUMENES: String = "resumenes"

    private const val VERSION = "v1"
    private const val SEPARADOR_REGISTRO = "\n"
    private const val SEPARADOR_CAMPO = ","
    private const val CAMPOS_POR_REGISTRO = 8

    /** Empaqueta la causa y el resultado por Lista de una ejecución. */
    fun de(
        fuenteId: Long,
        causa: String,
        resumenes: List<ResumenActualizacion>,
    ): Data =
        Data
            .Builder()
            .putLong(CLAVE_FUENTE, fuenteId)
            .putString(CLAVE_CAUSA, causa)
            .putString(CLAVE_RESUMENES, codificar(resumenes))
            .build()

    fun fuenteId(datos: Data): Long? = datos.getLong(CLAVE_FUENTE, -1L).takeIf { it > 0L }

    fun causa(datos: Data): String? = datos.getString(CLAVE_CAUSA)

    /** Resúmenes publicados por la ejecución; vacío si no publicó ninguno. */
    fun resumenes(datos: Data): List<ResumenActualizacion> =
        datos.getString(CLAVE_RESUMENES)?.let(::decodificar).orEmpty()

    private fun codificar(resumenes: List<ResumenActualizacion>): String =
        (listOf(VERSION) +
            resumenes.map { resumen ->
                listOf(
                    resumen.listaId,
                    resumen.resultado,
                    resumen.nuevas,
                    resumen.actualizadas,
                    resumen.ausentes,
                    resumen.ambiguas,
                    resumen.observaciones,
                    resumen.error.orEmpty(),
                ).joinToString(SEPARADOR_CAMPO)
            }).joinToString(SEPARADOR_REGISTRO)

    private fun decodificar(texto: String): List<ResumenActualizacion> {
        val registros = texto.split(SEPARADOR_REGISTRO)
        if (registros.firstOrNull() != VERSION) return emptyList()
        return registros.drop(1).mapNotNull(::decodificarRegistro)
    }

    private fun decodificarRegistro(registro: String): ResumenActualizacion? {
        val campos = registro.split(SEPARADOR_CAMPO)
        if (campos.size != CAMPOS_POR_REGISTRO) return null
        val listaId = campos[0].toLongOrNull() ?: return null
        val nuevas = campos[2].toIntOrNull() ?: return null
        val actualizadas = campos[3].toIntOrNull() ?: return null
        val ausentes = campos[4].toIntOrNull() ?: return null
        val ambiguas = campos[5].toIntOrNull() ?: return null
        val observaciones = campos[6].toIntOrNull() ?: return null
        return ResumenActualizacion(
            listaId = listaId,
            resultado = campos[1],
            nuevas = nuevas,
            actualizadas = actualizadas,
            ausentes = ausentes,
            ambiguas = ambiguas,
            observaciones = observaciones,
            error = campos[7].ifEmpty { null },
        )
    }
}
