package com.copernicotv.core.xmltv

import java.time.Instant

/**
 * Canal declarado por un documento XMLTV.
 *
 * No se persiste como entidad propia: la asociación aprobada es
 * `(fuente_guia_id, channel_id_texto)` (D1(a)). Los nombres e iconos solo
 * alimentan la huella `huella:v2` de DC-06.
 */
data class CanalXmltv(
    val id: String,
    val nombres: List<String>,
    val iconos: List<String>,
)

/** Programación parseada de un documento XMLTV. */
data class ProgramaXmltv(
    val channelIdTexto: String,
    val titulo: String,
    val inicio: Instant,
    val fin: Instant,
    val descripcion: String?,
)

/** Errores recuperables de un elemento XMLTV. */
enum class ErrorElementoXmltv(val codigo: String) {
    CANAL_SIN_ID("canal_sin_id"),
    CANAL_ID_DUPLICADO("canal_id_duplicado"),
    PROGRAMA_SIN_CANAL("programa_sin_canal"),
    PROGRAMA_SIN_INICIO("programa_sin_inicio"),
    PROGRAMA_SIN_FIN("programa_sin_fin"),
    FECHA_INVALIDA("fecha_invalida"),
    RANGO_INVALIDO("rango_invalido"),
    TITULO_AUSENTE("titulo_ausente"),
}

/** Error recuperable con la ubicación del elemento, sin volcar su contenido. */
data class ErrorXmltv(
    val tipo: ErrorElementoXmltv,
    val ubicacion: String,
    val detalle: String? = null,
)

/** Errores globales que impiden completar el parseo. */
enum class ErrorGlobalXmltv(val codigo: String) {
    RAIZ_INVALIDA("raiz_invalida"),
    XML_MALFORMADO("xml_malformado"),
    EOF_ABRUPTO("eof_abrupto"),
    ENCODING_INVALIDO("encoding_invalido"),
    LECTURA("lectura"),
    CONFIGURACION("configuracion"),
}

/** Tope de errores recuperables conservados en memoria durante un parseo. */
const val MAXIMO_ERRORES_RETENIDOS: Int = 1000

/**
 * Resumen del parseo.
 *
 * [errores] contiene como máximo [MAXIMO_ERRORES_RETENIDOS] entradas para no
 * crecer con un documento patológico; [erroresTotales] conserva el conteo real.
 */
data class ResultadoParseoXmltv(
    val canales: Int,
    val programas: Int,
    val descartados: Int,
    val erroresTotales: Int,
    val errores: List<ErrorXmltv>,
    val errorGlobal: ErrorGlobalXmltv? = null,
    val detalleGlobal: String? = null,
    val cancelado: Boolean = false,
)

/** Consumidor incremental: el documento nunca se materializa completo. */
interface ConsumidorXmltv {
    suspend fun alCanal(canal: CanalXmltv) = Unit

    suspend fun alPrograma(programa: ProgramaXmltv) = Unit

    suspend fun alError(error: ErrorXmltv) = Unit
}
