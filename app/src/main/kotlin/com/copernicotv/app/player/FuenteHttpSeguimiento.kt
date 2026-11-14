package com.copernicotv.app.player

import androidx.media3.common.MediaItem
import androidx.media3.common.MediaSource
import androidx.media3.datasource.DataSpec
import androidx.media3.datasource.HttpDataSource
import androidx.media3.datasource.TransferListener
import androidx.media3.exoplayer.drm.DrmSessionManagerProvider
import androidx.media3.exoplayer.source.MediaSourceFactory
import androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy
import com.copernicotv.core.player.ResolverRedirecciones
import com.copernicotv.core.player.TrazaRedireccion

/**
 * Fuente HTTP de reproducción con seguimiento de red (E7-T8, S4/S5).
 *
 * La fuente interna es la `DefaultHttpDataSource` de Media3 con
 * `allowCrossProtocolRedirects=true`: es la corrección de la causa raíz
 * (L2) y es el modo acotado y controlado del propio motor — máximo 20
 * saltos, solo esquemas HTTP(S), Location tal cual, sin TLS alterado.
 *
 * El envoltorio no cambia el camino de streaming: delega `open`, `read` y
 * `close` tal cual, y solo ante un fallo registra evidencia en
 * [TrazadoraRed]: ante un código HTTP inválido reconstruye la cadena de
 * redirecciones con el trazador puro de `:core` y toma el esquema final del
 * último salto real de la fuente interna; ante un fallo de red no repite
 * peticiones y registra solo los esquemas observables.
 */
internal class FuenteHttpSeguimiento(
    private val interna: HttpDataSource,
    private val trazadora: TrazadoraRed,
    private val trazadorRedirecciones: (String) -> TrazaRedireccion,
) : HttpDataSource {
    override fun addTransferListener(oyente: TransferListener) {
        interna.addTransferListener(oyente)
    }

    override fun open(dataSpec: DataSpec): Long =
        try {
            interna.open(dataSpec)
        } catch (fallo: HttpDataSource.InvalidResponseCodeException) {
            registrarTrazaFalloHttp(dataSpec, fallo)
            throw fallo
        } catch (fallo: HttpDataSource.HttpDataSourceException) {
            registrarTrazaFalloRed(dataSpec)
            throw fallo
        }

    override fun read(
        buffer: ByteArray,
        offset: Int,
        length: Int,
    ): Int = interna.read(buffer, offset, length)

    override fun close() = interna.close()

    override fun getUri() = interna.uri

    override fun getResponseCode() = interna.responseCode

    override fun getResponseHeaders() = interna.responseHeaders

    override fun setRequestProperty(
        nombre: String,
        valor: String,
    ) = interna.setRequestProperty(nombre, valor)

    override fun clearRequestProperty(nombre: String) = interna.clearRequestProperty(nombre)

    override fun clearAllRequestProperties() = interna.clearAllRequestProperties()

    /**
     * Ante un código HTTP inválido reconstruye la cadena de redirecciones
     * (replay acotado tras un fallo real) y conserva el esquema y el código
     * del último salto real, que la fuente interna aún recuerda.
     */
    private fun registrarTrazaFalloHttp(
        dataSpec: DataSpec,
        fallo: HttpDataSource.InvalidResponseCodeException,
    ) {
        val traza = trazadorRedirecciones(dataSpec.uri.toString())
        val esquemaFinalReal = interna.uri?.scheme
        val codigoFinalReal =
            runCatching { interna.responseCode }
                .getOrDefault(-1)
                .takeIf { it > 0 }
                ?: fallo.responseCode
        trazadora.registrar(
            traza.copy(
                codigoFinal = codigoFinalReal,
                esquemaFinal = esquemaFinalReal ?: traza.esquemaFinal,
            ),
        )
    }

    /**
     * Ante un fallo de red no repite peticiones: registra los esquemas
     * observables (origen y, si el motor llegó a abrir un salto, el final).
     */
    private fun registrarTrazaFalloRed(dataSpec: DataSpec) {
        val esquemaFinal = interna.uri?.scheme ?: return
        trazadora.registrar(
            TrazaRedireccion(
                codigoInicial = null,
                codigoFinal = null,
                redirecciones = 0,
                esquemaInicial = dataSpec.uri.scheme,
                esquemaFinal = esquemaFinal,
                circular = false,
                limiteAlcanzado = false,
            ),
        )
    }
}

/**
 * Fábrica HTTP de la sesión (E7-T8, S4).
 *
 * Configura explícitamente la fuente HTTP de Media3 con redirecciones
 * cross-protocolo permitidas y el User-Agent determinista de reproducción,
 * y entrega fuentes envueltas que trazan los fallos en [trazadora].
 */
internal class FabricaFuenteSeguimiento(
    private val trazadora: TrazadoraRed,
    private val trazadorRedirecciones: (String) -> TrazaRedireccion = { urlOriginal ->
        ResolverRedirecciones(cabeceras = mapOf(AGENTE_HTTP to trazadora.agenteUsuario))
            .resolver(urlOriginal)
    },
) : HttpDataSource.Factory {
    private val interna =
        androidx.media3.datasource.DefaultHttpDataSource.Factory()
            .setAllowCrossProtocolRedirects(true)
            .setUserAgent(trazadora.agenteUsuario)

    override fun createDataSource(): HttpDataSource =
        FuenteHttpSeguimiento(interna.createDataSource(), trazadora, trazadorRedirecciones)

    override fun setDefaultRequestProperties(
        propiedades: Map<String, String>,
    ): HttpDataSource.Factory {
        interna.setDefaultRequestProperties(propiedades)
        return this
    }
}

/**
 * Fábrica de MediaSource que registra el tipo de fuente creado (E7-T8, S5).
 *
 * Delega en la fábrica base y conserva el nombre simple de la clase del
 * MediaSource devuelto (`ProgressiveMediaSource`, `HlsMediaSource`, ...) en
 * la trazadora, para que el diagnóstico muestre el enrutado real del motor.
 */
internal class FabricaMediaSourcesGrabadora(
    private val base: MediaSourceFactory,
    private val trazadora: TrazadoraRed,
) : MediaSourceFactory {
    override fun createMediaSource(mediaItem: MediaItem): MediaSource =
        base.createMediaSource(mediaItem).also { trazadora.registrarFuenteMedia(it.javaClass.simpleName) }

    override fun getSupportedTypes(): IntArray = base.supportedTypes

    override fun setDrmSessionManagerProvider(
        proveedor: DrmSessionManagerProvider,
    ): MediaSourceFactory {
        base.setDrmSessionManagerProvider(proveedor)
        return this
    }

    override fun setLoadErrorHandlingPolicy(
        politica: LoadErrorHandlingPolicy,
    ): MediaSourceFactory {
        base.setLoadErrorHandlingPolicy(politica)
        return this
    }
}

private const val AGENTE_HTTP = "User-Agent"
