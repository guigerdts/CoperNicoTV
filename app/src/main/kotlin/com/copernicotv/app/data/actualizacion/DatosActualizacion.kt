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

    /**
     * Código del desenlace de la ejecución (H-04):
     * `completado`, `sin_trabajo` o `fallido`. WorkManager solo ofrece tres
     * estados finales; este código es el que distingue una ejecución que no
     * hizo trabajo de una que sí lo hizo.
     */
    const val CLAVE_RESULTADO: String = "resultado"
    const val RESULTADO_COMPLETADO: String = "completado"
    const val RESULTADO_SIN_TRABAJO: String = "sin_trabajo"
    const val RESULTADO_FALLIDO: String = "fallido"

    /** Claves del avance publicado con `setProgress` (H-04). */
    const val CLAVE_PROGRESO_HECHOS: String = "progreso_hechos"
    const val CLAVE_PROGRESO_TOTAL: String = "progreso_total"

    private const val VERSION = "v1"
    private const val SEPARADOR_REGISTRO = "\n"
    private const val SEPARADOR_CAMPO = ","
    private const val CAMPOS_POR_REGISTRO = 8

    /** Empaqueta la causa y el resultado por Lista de una ejecución exitosa. */
    fun de(
        fuenteId: Long,
        causa: String,
        resumenes: List<ResumenActualizacion>,
    ): Data = construir(fuenteId, causa, RESULTADO_COMPLETADO, codificar(resumenes))

    /** Empaqueta una ejecución que no hizo trabajo porque otra tenía el reclamo. */
    fun sinTrabajo(
        fuenteId: Long,
        causa: String,
    ): Data = construir(fuenteId, causa, RESULTADO_SIN_TRABAJO, "")

    /** Empaqueta un cierre fallido con los resúmenes parciales disponibles. */
    fun fallo(
        fuenteId: Long,
        causa: String,
        resumenes: List<ResumenActualizacion>,
    ): Data = construir(fuenteId, causa, RESULTADO_FALLIDO, codificar(resumenes))

    private fun construir(
        fuenteId: Long,
        causa: String,
        resultado: String,
        resumenes: String,
    ): Data =
        Data
            .Builder()
            .putLong(CLAVE_FUENTE, fuenteId)
            .putString(CLAVE_CAUSA, causa)
            .putString(CLAVE_RESULTADO, resultado)
            .putString(CLAVE_RESUMENES, resumenes)
            .build()

    /** Desenlace publicado por la ejecución, o `null` si no lo declaró. */
    fun resultado(datos: Data): String? = datos.getString(CLAVE_RESULTADO)

    /**
     * Avance en Listas terminadas sobre el total (H-04).
     *
     * Solo transporta dos enteros: no incluye nombres, URLs ni texto libre, así
     * que no puede filtrar credenciales al registro de WorkManager.
     */
    fun progreso(
        hechos: Int,
        total: Int,
    ): Data =
        Data
            .Builder()
            .putInt(CLAVE_PROGRESO_HECHOS, hechos)
            .putInt(CLAVE_PROGRESO_TOTAL, total)
            .build()

    /** Lee el avance publicado; `null` si la ejecución no lo informó. */
    fun progreso(datos: Data): Pair<Int, Int>? {
        val claves = datos.keyValueMap
        if (CLAVE_PROGRESO_HECHOS !in claves || CLAVE_PROGRESO_TOTAL !in claves) return null
        return datos.getInt(CLAVE_PROGRESO_HECHOS, 0) to datos.getInt(CLAVE_PROGRESO_TOTAL, 0)
    }

    fun fuenteId(datos: Data): Long? = datos.getLong(CLAVE_FUENTE, -1L).takeIf { it > 0L }

    fun causa(datos: Data): String? = datos.getString(CLAVE_CAUSA)

    /** Resúmenes publicados por la ejecución; vacío si no publicó ninguno. */
    fun resumenes(datos: Data): List<ResumenActualizacion> = datos.getString(CLAVE_RESUMENES)?.let(::decodificar).orEmpty()

    private fun codificar(resumenes: List<ResumenActualizacion>): String {
        val registros = resumenes.map(::codificarRegistro)
        return (listOf(VERSION) + registros).joinToString(SEPARADOR_REGISTRO)
    }

    private fun codificarRegistro(resumen: ResumenActualizacion): String =
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

    private fun decodificar(texto: String): List<ResumenActualizacion> {
        val registros = texto.split(SEPARADOR_REGISTRO)
        if (registros.firstOrNull() != VERSION) return emptyList()
        return registros.drop(1).mapNotNull(::decodificarRegistro)
    }

    private fun decodificarRegistro(registro: String): ResumenActualizacion? {
        val campos = registro.split(SEPARADOR_CAMPO)
        if (campos.size != CAMPOS_POR_REGISTRO) return null
        val listaId = campos[0].toLongOrNull() ?: return null
        val cifras = campos.subList(2, CAMPOS_POR_REGISTRO - 1).map { it.toIntOrNull() }
        if (cifras.any { it == null }) return null
        val numeros = cifras.filterNotNull()
        return ResumenActualizacion(
            listaId = listaId,
            resultado = campos[1],
            nuevas = numeros[0],
            actualizadas = numeros[1],
            ausentes = numeros[2],
            ambiguas = numeros[3],
            observaciones = numeros[4],
            error = campos[7].ifEmpty { null },
        )
    }
}
