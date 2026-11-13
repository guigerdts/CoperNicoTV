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
    val canalesPersistidos: Int? = null,
    val tiempoDescargaMs: Long? = null,
    val tiempoProcesamientoMs: Long? = null,
    val tiempoGuardadoMs: Long? = null,
    val motivo: String? = null,
) {
    /**
     * Línea única para Logcat. Cada campo es acotado y sanitizado: tipo de
     * contenido y motivo son códigos o tipos MIME, no contenido del servidor.
     */
    fun aLogcat(resultado: String): String =
        listOfNotNull(
            "resultado=$resultado",
            codigoHttp?.let { "codigo_http=$it" },
            tipoContenido?.let { "tipo_contenido=$it" },
            bytesDescargados?.let { "bytes=$it" },
            entradasDetectadas?.let { "entradas=$it" },
            canalesPersistidos?.let { "canales=$it" },
            tiempoDescargaMs?.let { "tiempo_descarga_ms=$it" },
            tiempoProcesamientoMs?.let { "tiempo_procesamiento_ms=$it" },
            tiempoGuardadoMs?.let { "tiempo_guardado_ms=$it" },
            motivo?.let { "motivo=$it" },
        ).joinToString(" ")
}
