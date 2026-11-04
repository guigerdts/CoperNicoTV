package com.copernicotv.core.m3u

import java.io.InputStream

/** Par clave-valor de un `#EXTINF`, en su orden de aparición. */
data class ParAtributoM3U(
    val clave: String,
    val valor: String,
)

/**
 * Límites defensivos del parser (ADR-0006).
 *
 * Los dos primeros son configurables globalmente y sus valores iniciales son
 * valores de especificación, no validados experimentalmente. [maxMuestrasUbicacion]
 * acota la muestra de ubicaciones por código de diagnóstico: el conteo total
 * nunca se recorta, la lista de ubicaciones sí.
 */
data class LimitesParserM3U(
    val maxLongitudLinea: Int = MAX_LONGITUD_LINEA_POR_DEFECTO,
    val maxAtributosPorEntrada: Int = MAX_ATRIBUTOS_POR_ENTRADA_POR_DEFECTO,
    val maxMuestrasUbicacion: Int = MAX_MUESTRAS_UBICACION_POR_DEFECTO,
) {
    init {
        require(maxLongitudLinea >= 1) { "maxLongitudLinea debe ser positivo" }
        require(maxAtributosPorEntrada >= 1) { "maxAtributosPorEntrada debe ser positivo" }
        require(maxMuestrasUbicacion >= 1) { "maxMuestrasUbicacion debe ser positivo" }
    }

    companion object {
        const val MAX_LONGITUD_LINEA_POR_DEFECTO: Int = 64 * 1024
        const val MAX_ATRIBUTOS_POR_ENTRADA_POR_DEFECTO: Int = 64
        const val MAX_MUESTRAS_UBICACION_POR_DEFECTO: Int = 5
    }
}

/** Ubicación física de un diagnóstico: línea 1-based y ordinal 1-based del `#EXTINF`. */
data class UbicacionM3U(
    val linea: Long,
    val ordinal: Long? = null,
)

/** Gravedad de un diagnóstico. Un informativo no descarta entradas. */
enum class SeveridadM3U {
    ERROR,
    INFORMATIVO,
}

/**
 * Códigos de diagnóstico del parser, agregados por código (ADR-0006).
 *
 * [codigo] es el identificador contractual estable que se informa al usuario y
 * se registra en diagnóstico; nunca incluye datos de la lista.
 */
enum class CodigoM3U(
    val codigo: String,
    val severidad: SeveridadM3U,
) {
    SIN_CABECERA("m3u_sin_cabecera", SeveridadM3U.ERROR),
    LINEA_EXCEDIDA("m3u_linea_excedida", SeveridadM3U.ERROR),
    EXTINF_SIN_URL("m3u_extinf_sin_url", SeveridadM3U.ERROR),
    ATRIBUTOS_DESBORDADOS("m3u_atributos_desbordados", SeveridadM3U.INFORMATIVO),
    LINEA_DESCONOCIDA("m3u_linea_desconocida", SeveridadM3U.INFORMATIVO),
}

/** Diagnóstico agregado: conteo total y muestra acotada de ubicaciones. */
data class DiagnosticoM3U(
    val codigo: CodigoM3U,
    val severidad: SeveridadM3U,
    val conteoTotal: Long,
    val ubicaciones: List<UbicacionM3U>,
)

/**
 * Entrada emitida por el parser, sin identidad y sin deduplicar (Enmienda L26
 * de ADR-0006).
 *
 * [nombre], [tvgId], [tvgName], [logo] y [groupTitle] son `null` cuando el
 * atributo está ausente y cadena vacía cuando está declarado vacío: el parser
 * no normaliza `NULL` a `""` ni al contrario.
 */
data class EntradaM3U(
    val ordinal: Long,
    val linea: Long,
    val duracion: String,
    val nombre: String?,
    val url: String,
    val tvgId: String?,
    val tvgName: String?,
    val logo: String?,
    val groupTitle: String?,
    val atributos: List<ParAtributoM3U>,
    val atributosExtra: List<ParAtributoM3U>,
)

/** Resultado de un parseo: catálogo parcial recuperado y diagnósticos agregados. */
data class ResultadoParserM3U(
    val entradas: List<EntradaM3U>,
    val diagnosticos: List<DiagnosticoM3U>,
) {
    fun diagnostico(codigo: CodigoM3U): DiagnosticoM3U? = diagnosticos.firstOrNull { it.codigo == codigo }

    val errores: List<DiagnosticoM3U>
        get() = diagnosticos.filter { it.severidad == SeveridadM3U.ERROR }

    val informativos: List<DiagnosticoM3U>
        get() = diagnosticos.filter { it.severidad == SeveridadM3U.INFORMATIVO }
}

/**
 * Parser M3U/M3U8 streaming y tolerante a fallos (ADR-0006).
 *
 * Procesa el documento línea a línea: no carga el documento completo en memoria
 * y la cota de memoria por entrada es el límite de longitud de línea. El
 * resultado nunca es `null` ni lanza una excepción global cuando hubo
 * recuperación posible: devuelve las entradas recuperadas y los diagnósticos
 * agregados por código.
 *
 * El parser no deduplica, no aplica la clave de presentación y no normaliza la
 * URL (Enmienda L26): emite cada `#EXTINF` con sus atributos, su orden y su URL
 * tal como aparecen.
 */
object ParserM3U {
    /**
     * Parsea [entrada] como flujo.
     *
     * Si se aporta [alEntrada], cada entrada se entrega en el momento en que se
     * completa y no se acumula en el resultado; así el llamador puede persistir
     * por lotes sin retener el catálogo completo. El [InputStream] no se cierra.
     */
    fun parsear(
        entrada: InputStream,
        limites: LimitesParserM3U = LimitesParserM3U(),
        alEntrada: ((EntradaM3U) -> Unit)? = null,
    ): ResultadoParserM3U = ParseadorM3U(limites, alEntrada).parsear(entrada)

    /** Variante de conveniencia para pruebas y textos ya decodificados (UTF-8). */
    fun parsear(
        texto: String,
        limites: LimitesParserM3U = LimitesParserM3U(),
        alEntrada: ((EntradaM3U) -> Unit)? = null,
    ): ResultadoParserM3U = parsear(texto.byteInputStream(Charsets.UTF_8), limites, alEntrada)
}
