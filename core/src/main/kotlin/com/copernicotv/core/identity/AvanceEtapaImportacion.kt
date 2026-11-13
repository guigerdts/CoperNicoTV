package com.copernicotv.core.identity

/**
 * Etapa tipada del pipeline de importación (E7-T7 ronda 4, S34).
 *
 * El código es estable y viaja en el informe copiable: si el vigilante aborta,
 * el diagnóstico indica la etapa exacta donde quedó el procesamiento. Los
 * códigos `persistencia_*` e `importacion_completada` los emite la capa de
 * persistencia (`:app`) con el mismo tipo; el resto nace en `:core`.
 */
enum class EtapaImportacion(val codigo: String) {
    /** Inicio de la agrupación de identidad. */
    AGRUPACION_INICIADA("agrupacion_iniciada"),

    /** Canonización de declaraciones (URLs, tvg-id, logo) por lote. */
    CONVERSION_IDENTIDADES("conversion_identidades"),

    /** Deduplicación exacta y derivación de identidad por lote. */
    DERIVACION_CLAVES("derivacion_claves"),

    /** Construcción de Canales y detección de colisiones por lote. */
    VALIDACION_CATALOGO("validacion_catalogo"),

    /** La agrupación terminó y devolvió sus Canales. */
    AGRUPACION_COMPLETADA("agrupacion_completada"),

    /** Cálculo de sobres HMAC por lote (portador de las ops criptográficas). */
    SOBRES_HMAC("sobres_hmac"),

    /** Inserción de entidades `Canal` por lote (capa de persistencia). */
    PERSISTENCIA_CANALES("persistencia_canales"),

    /** Inserción de grupos únicos (capa de persistencia). */
    PERSISTENCIA_GRUPOS("persistencia_grupos"),

    /** Inserción de filas `Apariencia` por lote (capa de persistencia). */
    PERSISTENCIA_APARIENCIAS("persistencia_apariencias"),

    /** Importación completa, tras la transacción confirmada. */
    IMPORTACION_COMPLETADA("importacion_completada"),
}

/**
 * Avance diferenciado por etapa del pipeline de importación (S34).
 *
 * Cada evento incluye los elementos procesados acumulados de su etapa, el
 * tiempo acumulado de la etapa, el tiempo transcurrido desde el avance
 * anterior (de cualquier etapa, medido por el emisor), las operaciones
 * criptográficas acumuladas del pipeline y la cantidad de canales válidos
 * identificados hasta el momento. No contiene datos de la lista.
 */
data class AvanceEtapaImportacion(
    val etapa: EtapaImportacion,
    val elementos: Int,
    val msEtapa: Long,
    val msDesdeUltimoAvance: Long,
    val opsCriptograficas: Int,
    val canalesValidos: Int,
)
