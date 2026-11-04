package com.copernicotv.core.m3u

import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.identity.AgrupadorImportacion
import com.copernicotv.core.identity.AparienciaDeclarada
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanalDeclarado
import com.copernicotv.core.identity.DeclaracionRechazada
import javax.crypto.SecretKey

/** Canal listo para persistir, con su URL original y el sobre HMAC de la canónica. */
data class CanalCanario(
    val nombre: String?,
    val tvgId: String?,
    val urlOriginal: String,
    val urlCanonica: String,
    val urlStreamHmac: String,
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val claveDerivable: Boolean,
    val apariencias: List<AparienciaDeclarada>,
)

/** Resultado de importar un fixture M3U con el camino canario. */
data class ResultadoImportacionCanario(
    val canales: List<CanalCanario>,
    val rechazadas: List<DeclaracionRechazada>,
    val error: ErrorCanarioM3U? = null,
)

/**
 * Camino canario de importación de E1-C.
 *
 * Encadena parser mínimo → canonicalización → identidad → HMAC. No sustituye
 * al importador completo de E2: no reconcilia versiones, no crea Observaciones
 * y no implementa caché. Su único propósito es que el canario de E1-C
 * (importar → catálogo → D-pad) ejerza el camino real de identidad y
 * persistencia, no una simulación.
 */
object ImportadorCanario {
    const val VERSION_CANONICA: Int = 1
    const val GENERACION_HMAC: Int = 1

    fun importar(
        texto: String,
        claveHmac: SecretKey,
    ): ResultadoImportacionCanario {
        val parseo = ParserM3UCanario.parsear(texto)
        if (parseo.error != null) {
            return ResultadoImportacionCanario(emptyList(), emptyList(), parseo.error)
        }
        val agrupado = AgrupadorImportacion.agrupar(parseo.declaraciones, CampoUrl.CANAL_URL_STREAM)
        val canales = agrupado.canales.map { canalCanario(it, claveHmac) }
        return ResultadoImportacionCanario(canales, agrupado.rechazadas)
    }

    private fun canalCanario(
        canal: CanalDeclarado,
        claveHmac: SecretKey,
    ): CanalCanario {
        val sobre =
            HmacUrl.calcularSobre(
                clave = claveHmac,
                generacion = GENERACION_HMAC,
                campo = CampoUrl.CANAL_URL_STREAM,
                clase = canal.clase,
                urlCanonica = canal.urlCanonica,
                versionCanonica = VERSION_CANONICA,
            )
        return CanalCanario(
            nombre = canal.nombre,
            tvgId = canal.tvgId,
            urlOriginal = canal.urlOriginal,
            urlCanonica = canal.urlCanonica,
            urlStreamHmac = sobre,
            claveCanonica = canal.claveCanonica,
            claveUnicidad = canal.claveUnicidad,
            claveDerivable = canal.claveDerivable,
            apariencias = canal.apariencias,
        )
    }
}
