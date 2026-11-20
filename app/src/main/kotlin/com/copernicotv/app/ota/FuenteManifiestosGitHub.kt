package com.copernicotv.app.ota

import com.copernicotv.core.ota.FirmaManifiesto
import com.copernicotv.core.ota.ManifiestoActualizacion
import com.copernicotv.core.ota.PublicacionesGitHub
import com.copernicotv.core.ota.ResultadoManifiesto

/** Manifiesto que ya pasó la verificación de firma, con sus bytes originales. */
data class ManifiestoVerificado(
    val manifiesto: ManifiestoActualizacion,
    val json: ByteArray,
    val firma: ByteArray,
)

/** Motivo tipado de un fallo al leer el canal. */
enum class FalloFuente {
    CONFIGURACION,
    SIN_RED,
    SERVIDOR,
    RESPUESTA_INVALIDA,
    FIRMA_INVALIDA,
    SIN_PUBLICACIONES,
}

/** Resultado de consultar las publicaciones del canal. */
sealed interface ResultadoFuente {
    data class Manifiestos(val verificados: List<ManifiestoVerificado>) : ResultadoFuente

    data object SinPublicaciones : ResultadoFuente

    data class Fallo(
        val tipo: FalloFuente,
        val detalle: String? = null,
    ) : ResultadoFuente
}

/** Fuente de manifiestos firmados del canal oficial. */
interface FuenteManifiestos {
    suspend fun buscar(): ResultadoFuente
}

/**
 * Fuente basada en GitHub Releases.
 *
 * La API solo se usa para descubrir publicaciones y localizar `update.json` y
 * `update.json.sig`. Ningún dato de la API decide una actualización: cada
 * manifiesto se verifica con la clave pública embebida y se valida contra el
 * paquete y la huella de firma de esta compilación. Una publicación sin firma
 * válida se ignora sin afectar a las demás.
 */
class FuenteManifiestosGitHub(
    private val cliente: ClienteHttpOta,
    private val configuracion: ConfiguracionOta,
    private val maximoPublicaciones: Int = 5,
) : FuenteManifiestos {
    override suspend fun buscar(): ResultadoFuente {
        if (!configuracion.configurada) return ResultadoFuente.Fallo(FalloFuente.CONFIGURACION)
        val lista =
            when (val respuesta = cliente.obtener(configuracion.urlReleases, PublicacionesGitHub.MAX_BYTES)) {
                is ResultadoHttp.Exito -> respuesta.cuerpo
                is ResultadoHttp.Fallo -> return ResultadoFuente.Fallo(tipoDe(respuesta.tipo), respuesta.detalle)
            }
        val publicaciones =
            PublicacionesGitHub.parse(lista.decodeToString())
                ?: return ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA)
        val candidatas = publicaciones.filter { !it.borrador }.take(maximoPublicaciones)
        if (candidatas.isEmpty()) return ResultadoFuente.SinPublicaciones

        val verificados = mutableListOf<ManifiestoVerificado>()
        var firmaInvalida = false
        var respuestaInvalida = false
        for (publicacion in candidatas) {
            val manifiestoActivo = publicacion.activo(PublicacionesGitHub.NOMBRE_MANIFIESTO)
            val firmaActivo = publicacion.activo(PublicacionesGitHub.NOMBRE_FIRMA)
            if (manifiestoActivo == null || firmaActivo == null) {
                respuestaInvalida = true
                continue
            }
            val json = leer(manifiestoActivo.urlDescarga, ManifiestoActualizacion.MAX_BYTES) ?: continue
            val firma = leer(firmaActivo.urlDescarga, MAX_BYTES_FIRMA) ?: continue
            if (!FirmaManifiesto.verificar(json, firma, configuracion.clavePublicaManifiesto)) {
                firmaInvalida = true
                continue
            }
            val parseo =
                ManifiestoActualizacion.parse(
                    texto = json.decodeToString(),
                    paqueteEsperado = configuracion.paquete,
                    huellaFirmaEsperada = configuracion.huellaFirmaApk,
                )
            when (parseo) {
                is ResultadoManifiesto.Valido ->
                    verificados += ManifiestoVerificado(parseo.manifiesto, json, firma)
                is ResultadoManifiesto.Invalido -> respuestaInvalida = true
            }
        }
        return when {
            verificados.isNotEmpty() -> ResultadoFuente.Manifiestos(verificados)
            firmaInvalida -> ResultadoFuente.Fallo(FalloFuente.FIRMA_INVALIDA)
            respuestaInvalida -> ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA)
            else -> ResultadoFuente.SinPublicaciones
        }
    }

    private suspend fun leer(
        url: String,
        maxBytes: Int,
    ): ByteArray? =
        when (val respuesta = cliente.obtener(url, maxBytes)) {
            is ResultadoHttp.Exito -> respuesta.cuerpo
            is ResultadoHttp.Fallo -> null
        }

    private fun tipoDe(fallo: FalloHttp): FalloFuente =
        when (fallo) {
            FalloHttp.ESTADO_HTTP -> FalloFuente.SERVIDOR
            FalloHttp.SIN_RED, FalloHttp.TIMEOUT -> FalloFuente.SIN_RED
            else -> FalloFuente.RESPUESTA_INVALIDA
        }

    private companion object {
        /** La firma DER de P-256 ronda 70 bytes; el tope es holgado. */
        const val MAX_BYTES_FIRMA = 4096
    }
}
