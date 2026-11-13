package com.copernicotv.core.m3u

import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.identity.AgrupadorImportacion
import com.copernicotv.core.identity.AparienciaDeclarada
import com.copernicotv.core.identity.AtributoExtra
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanalDeclarado
import com.copernicotv.core.identity.ClaseUrl
import com.copernicotv.core.identity.ColisionCanonica
import com.copernicotv.core.identity.DeclaracionCanal
import com.copernicotv.core.identity.DeclaracionRechazada
import java.io.InputStream
import javax.crypto.SecretKey

/** Canal listo para persistir, con URL y logo cifrables y sus HMAC. */
data class CanalImportadoM3U(
    val nombre: String?,
    val tvgId: String?,
    val urlOriginal: String,
    val urlStreamHmac: String,
    val logoOriginal: String?,
    val logoHmac: String?,
    val atributosExtra: List<AtributoExtra>,
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val claveDerivable: Boolean,
    val apariencias: List<AparienciaDeclarada>,
)

/** Resultado de importar una lista M3U con el parser tolerante. */
data class ResultadoImportacionM3U(
    val canales: List<CanalImportadoM3U>,
    val rechazadas: List<DeclaracionRechazada>,
    val colisiones: List<ColisionCanonica>,
    val diagnosticos: List<DiagnosticoM3U>,
    val repeticionesInformativas: Int,
) {
    /** Diagnósticos de severidad error; no impiden persistir el catálogo recuperado. */
    val errores: List<DiagnosticoM3U>
        get() = diagnosticos.filter { it.severidad == SeveridadM3U.ERROR }
}

/**
 * Importador real de E2: encadena el parser tolerante, la agrupación de
 * identidad y el cálculo de HMAC.
 *
 * El parser puede recuperar entradas parciales de una lista con errores; esas
 * entradas se importan y los diagnósticos se informan por separado. La
 * reconciliación entre versiones y las Observaciones pertenecen a E4.
 */
object ImportadorM3U {
    /** Versión de canonicalización vigente: `cv1`. */
    const val VERSION_CANONICA: Int = 1

    /** Generación HMAC inicial: `v1`. */
    const val GENERACION_HMAC: Int = 1

    /**
     * Importa un flujo sin cerrarlo; el llamador conserva su propiedad.
     *
     * [generacionHmac] es la generación de la clave con la que se sellan los
     * sobres: por defecto la inicial `v1`; durante la reconstrucción del
     * índice (DC-04 §7) el llamador pasa la generación objetivo.
     */
    fun importar(
        entrada: InputStream,
        claveHmac: SecretKey,
        limites: LimitesParserM3U = LimitesParserM3U(),
        generacionHmac: Int = GENERACION_HMAC,
    ): ResultadoImportacionM3U = convertir(ParserM3U.parsear(entrada, limites), claveHmac, generacionHmac)

    /** Importa un texto ya decodificado como UTF-8. */
    fun importar(
        texto: String,
        claveHmac: SecretKey,
        limites: LimitesParserM3U = LimitesParserM3U(),
        generacionHmac: Int = GENERACION_HMAC,
    ): ResultadoImportacionM3U = convertir(ParserM3U.parsear(texto, limites), claveHmac, generacionHmac)

    /**
     * Importa un resultado de parser ya obtenido.
     *
     * Permite al llamador separar la lectura en streaming del parseo (fase de
     * descarga) de la conversión, la agrupación de identidad y el cálculo de
     * HMAC (fase de procesamiento), para informar el progreso con fases reales.
     */
    fun importar(
        parseo: ResultadoParserM3U,
        claveHmac: SecretKey,
        generacionHmac: Int = GENERACION_HMAC,
    ): ResultadoImportacionM3U = convertir(parseo, claveHmac, generacionHmac)

    private fun convertir(
        parseo: ResultadoParserM3U,
        claveHmac: SecretKey,
        generacionHmac: Int,
    ): ResultadoImportacionM3U {
        val declaraciones = parseo.entradas.map(::declaracion)
        val agrupado = AgrupadorImportacion.agrupar(declaraciones, CampoUrl.CANAL_URL_STREAM)
        val canales = agrupado.canales.map { canal -> convertirCanal(canal, claveHmac, generacionHmac) }
        return ResultadoImportacionM3U(
            canales = canales,
            rechazadas = agrupado.rechazadas,
            colisiones = agrupado.colisiones,
            diagnosticos = parseo.diagnosticos,
            repeticionesInformativas = agrupado.repeticionesInformativas,
        )
    }

    private fun declaracion(entrada: EntradaM3U): DeclaracionCanal =
        DeclaracionCanal(
            nombre = entrada.nombre,
            urlOriginal = entrada.url,
            tvgId = entrada.tvgId,
            groupTitle = entrada.groupTitle,
            tvgName = entrada.tvgName,
            logoOriginal = entrada.logo,
            atributosExtra = entrada.atributosExtra.map { AtributoExtra(it.clave, it.valor) },
        )

    private fun convertirCanal(
        canal: CanalDeclarado,
        claveHmac: SecretKey,
        generacionHmac: Int,
    ): CanalImportadoM3U {
        val sobreStream =
            HmacUrl.calcularSobre(
                clave = claveHmac,
                generacion = generacionHmac,
                campo = CampoUrl.CANAL_URL_STREAM,
                clase = canal.clase,
                urlCanonica = canal.urlCanonica,
                versionCanonica = VERSION_CANONICA,
            )
        val sobreLogo =
            canal.logoCanonica?.let { logo ->
                HmacUrl.calcularSobre(
                    clave = claveHmac,
                    generacion = generacionHmac,
                    campo = CampoUrl.CANAL_LOGO_URL,
                    clase = ClaseUrl.HTTP_HTTPS,
                    urlCanonica = logo,
                    versionCanonica = VERSION_CANONICA,
                )
            }
        return CanalImportadoM3U(
            nombre = canal.nombre,
            tvgId = canal.tvgId,
            urlOriginal = canal.urlOriginal,
            urlStreamHmac = sobreStream,
            logoOriginal = canal.logoOriginal,
            logoHmac = sobreLogo,
            atributosExtra = canal.atributosExtra,
            claveCanonica = canal.claveCanonica,
            claveUnicidad = canal.claveUnicidad,
            claveDerivable = canal.claveDerivable,
            apariencias = canal.apariencias,
        )
    }
}
