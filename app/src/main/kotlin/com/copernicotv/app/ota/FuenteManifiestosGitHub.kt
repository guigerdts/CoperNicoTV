package com.copernicotv.app.ota

import com.copernicotv.core.ota.FirmaManifiesto
import com.copernicotv.core.ota.ManifiestoActualizacion
import com.copernicotv.core.ota.PublicacionGitHub
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
        val publicaciones =
            when (val respuesta = cliente.obtener(configuracion.urlReleases, PublicacionesGitHub.MAX_BYTES)) {
                is ResultadoHttp.Exito ->
                    PublicacionesGitHub.parse(respuesta.cuerpo.decodeToString())
                        ?: return ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA)
                is ResultadoHttp.Fallo -> return ResultadoFuente.Fallo(tipoDe(respuesta.tipo), respuesta.detalle)
            }
        val candidatas = publicaciones.filter { !it.borrador }.take(maximoPublicaciones)
        if (candidatas.isEmpty()) return ResultadoFuente.SinPublicaciones
        return verificarTodas(candidatas)
    }

    /**
     * Verifica cada publicación y resume el canal.
     *
     * Una publicación rota no invalida a las demás: se guarda el motivo del
     * fallo y se devuelve el mejor resultado posible (manifiestos válidos >
     * firma inválida > respuesta inválida > sin publicaciones).
     */
    private suspend fun verificarTodas(candidatas: List<PublicacionGitHub>): ResultadoFuente {
        val verificados = mutableListOf<ManifiestoVerificado>()
        var firmaInvalida = false
        var respuestaInvalida = false
        candidatas.forEach { publicacion ->
            when (val verificacion = verificarPublicacion(publicacion)) {
                is VerificacionPublicacion.Valida -> verificados += verificacion.manifiesto
                VerificacionPublicacion.FirmaInvalida -> firmaInvalida = true
                VerificacionPublicacion.ManifiestoInvalido, VerificacionPublicacion.SinActivos -> respuestaInvalida = true
                VerificacionPublicacion.SinDescarga -> Unit
            }
        }
        return when {
            verificados.isNotEmpty() -> ResultadoFuente.Manifiestos(verificados)
            firmaInvalida -> ResultadoFuente.Fallo(FalloFuente.FIRMA_INVALIDA)
            respuestaInvalida -> ResultadoFuente.Fallo(FalloFuente.RESPUESTA_INVALIDA)
            else -> ResultadoFuente.SinPublicaciones
        }
    }

    private suspend fun verificarPublicacion(publicacion: PublicacionGitHub): VerificacionPublicacion {
        val activoManifiesto = publicacion.activo(PublicacionesGitHub.NOMBRE_MANIFIESTO)
        val activoFirma = publicacion.activo(PublicacionesGitHub.NOMBRE_FIRMA)
        if (activoManifiesto == null || activoFirma == null) return VerificacionPublicacion.SinActivos
        val json =
            leer(activoManifiesto.urlDescarga, ManifiestoActualizacion.MAX_BYTES)
                ?: return VerificacionPublicacion.SinDescarga
        val firma = leer(activoFirma.urlDescarga, MAX_BYTES_FIRMA) ?: return VerificacionPublicacion.SinDescarga
        if (!FirmaManifiesto.verificar(json, firma, configuracion.clavePublicaManifiesto)) {
            return VerificacionPublicacion.FirmaInvalida
        }
        val parseo =
            ManifiestoActualizacion.parse(
                texto = json.decodeToString(),
                paqueteEsperado = configuracion.paquete,
                huellaFirmaEsperada = configuracion.huellaFirmaApk,
            )
        return when (parseo) {
            is ResultadoManifiesto.Valido ->
                VerificacionPublicacion.Valida(ManifiestoVerificado(parseo.manifiesto, json, firma))
            is ResultadoManifiesto.Invalido -> VerificacionPublicacion.ManifiestoInvalido
        }
    }

    /** Desenlace de verificar una publicación concreta. */
    private sealed interface VerificacionPublicacion {
        data class Valida(val manifiesto: ManifiestoVerificado) : VerificacionPublicacion

        data object SinActivos : VerificacionPublicacion

        data object SinDescarga : VerificacionPublicacion

        data object FirmaInvalida : VerificacionPublicacion

        data object ManifiestoInvalido : VerificacionPublicacion
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
