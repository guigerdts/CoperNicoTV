package com.copernicotv.app.data

/**
 * Fases visibles de una importación por URL (E7-T7).
 *
 * El código es el identificador contractual que la UI traduce a texto; nunca
 * lleva datos de la lista ni de la ubicación.
 */
enum class FaseImportacion(val codigo: String) {
    CONECTANDO("conectando"),
    DESCARGANDO("descargando"),
    PROCESANDO("procesando_m3u"),
    GUARDANDO("guardando"),
}

/**
 * Diagnóstico seguro de una importación por URL (E7-T7).
 *
 * Solo contiene campos técnicos sin datos sensibles: nunca la URL completa,
 * `userinfo`, credenciales ni tokens (S57a). La UI lo muestra como detalle y
 * Logcat lo registra línea a línea con el mismo contrato.
 */
data class DiagnosticoImportacion(
    val codigoHttp: Int? = null,
    val tipoContenido: String? = null,
    val bytesDescargados: Long? = null,
    val entradasDetectadas: Int? = null,
    val canalesDetectados: Int? = null,
    val canalesPersistidos: Int? = null,
    val tiempoDescargaMs: Long? = null,
    val tiempoProcesamientoMs: Long? = null,
    val tiempoGuardadoMs: Long? = null,
    val ultimoAvance: String? = null,
    val sinAvanceMs: Long? = null,
    val motivo: String? = null,
    /** Líneas físicas leídas por el parser (S28). */
    val lineasLeidas: Long? = null,
    /** Bloques `#EXTINF` encontrados por el parser, incluidos sin URL (S28). */
    val extinfEncontrados: Long? = null,
    /** URLs asociadas a entradas emitidas (S28). */
    val urlsAsociadas: Int? = null,
    /** Entradas clasificadas como TV por la ruta de su URL (heurística, S28). */
    val entradasTv: Int? = null,
    /** Entradas clasificadas como VOD por la ruta de su URL (heurística, S28). */
    val entradasVod: Int? = null,
    /** Entradas sin marcador de ruta; nunca se descartan por esto (S28). */
    val entradasOtro: Int? = null,
    /** Descartes del parser por código de diagnóstico: `codigo=conteo` (S28). */
    val descartadosParser: Map<String, Long>? = null,
    /** Rechazos de canonización por motivo tipado: `motivo=conteo` (S28). */
    val rechazosCanonizacion: Map<String, Int>? = null,
    /** Declaraciones de identidad creadas antes de agrupar (S28). */
    val declaracionesCreadas: Int? = null,
    /** Operaciones HMAC ejecutadas con la clave de Keystore (S28). */
    val operacionesCriptograficas: Int? = null,
    /** Tiempo de la etapa de agrupación de identidad (S28). */
    val tiempoAgrupacionMs: Long? = null,
    /** Tiempo de la etapa de sobres HMAC por canal único (S28). */
    val tiempoHmacMs: Long? = null,
) {
    /**
     * Línea única para Logcat. Cada campo es acotado y sanitizado: tipo de
     * contenido y motivo son códigos o tipos MIME, no contenido del servidor.
     */
    fun aLogcat(resultado: String): String = aCampos(resultado).joinToString(" ")

    /**
     * Informe multilínea para el portapapeles del dispositivo (E7-T7, S21).
     *
     * Mismo contrato que [aLogcat]: campos técnicos acotados, sin URL, sin
     * `userinfo`, sin credenciales ni tokens. Una línea por campo para que el
     * usuario pueda leerlo y pegarlo completo sin ADB.
     */
    fun aInforme(resultado: String): String = aCampos(resultado).joinToString("\n")

    /** Campos comunes de diagnóstico, acotados y sanitizados. */
    private fun aCampos(resultado: String): List<String> =
        campos(resultado).mapNotNull { (clave, valor) ->
            valor?.let { "$clave=$it" }
        }

    /** Pares `clave-valor` del diagnóstico; valor nulo = campo ausente (S28). */
    private fun campos(resultado: String): List<Pair<String, Any?>> =
        listOf(
            "resultado" to resultado,
            "codigo_http" to codigoHttp,
            "tipo_contenido" to tipoContenido,
            "bytes" to bytesDescargados,
            "lineas" to lineasLeidas,
            "extinf" to extinfEncontrados,
            "urls" to urlsAsociadas,
            "entradas" to entradasDetectadas,
            "tv" to entradasTv,
            "vod" to entradasVod,
            "otro" to entradasOtro,
            "descartes" to descartadosParser?.let { compactar(it) },
            "rechazos" to rechazosCanonizacion?.let { compactar(it) },
            "declaraciones" to declaracionesCreadas,
            "canales_detectados" to canalesDetectados,
            "canales" to canalesPersistidos,
            "ops_cripto" to operacionesCriptograficas,
            "tiempo_descarga_ms" to tiempoDescargaMs,
            "tiempo_procesamiento_ms" to tiempoProcesamientoMs,
            "tiempo_agrupacion_ms" to tiempoAgrupacionMs,
            "tiempo_hmac_ms" to tiempoHmacMs,
            "tiempo_guardado_ms" to tiempoGuardadoMs,
            "ultimo_avance" to ultimoAvance,
            "sin_avance_ms" to sinAvanceMs,
            "motivo" to motivo,
        )

    /** Compacta un mapa `clave=conteo` en una línea acotada de códigos, sin datos. */
    private fun compactar(valores: Map<String, *>): String =
        valores.entries.joinToString(",") { par ->
            "${par.key}=${par.value}"
        }
}
