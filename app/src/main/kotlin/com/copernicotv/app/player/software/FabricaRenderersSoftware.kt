package com.copernicotv.app.player.software

import android.content.Context
import android.os.Handler
import androidx.media3.exoplayer.DefaultRenderersFactory
import androidx.media3.exoplayer.Renderer
import androidx.media3.exoplayer.mediacodec.MediaCodecSelector
import androidx.media3.exoplayer.video.VideoRendererEventListener
import com.copernicotv.core.player.DecoderVideo

/**
 * Fábrica de renderers con el renderer de video por software al final
 * (E7-T12, S2).
 *
 * `DefaultRenderersFactory` agrega primero los renderers MediaCodec
 * (hardware y software del dispositivo); después se añade
 * [RendererVideoSoftware], de modo que la selección de pistas solo lo
 * usa cuando ningún MediaCodec soporta el formato: hardware primero,
 * software FFmpeg como último recurso, garantizado por la misma política
 * pura de `:core` que produce el veredicto del diagnóstico.
 */
class FabricaRenderersSoftware(
    contexto: Context,
    private val inventarioNativo: () -> List<DecoderVideo>,
) : DefaultRenderersFactory(contexto) {
    override fun buildVideoRenderers(
        contexto: Context,
        modoExtension: Int,
        selectorMediaCodec: MediaCodecSelector,
        habilitarFallbackDecoders: Boolean,
        manejadorEventos: Handler,
        oyenteEventos: VideoRendererEventListener,
        msUnionPermitidoVideo: Long,
        renderers: ArrayList<Renderer>,
    ) {
        super.buildVideoRenderers(
            contexto,
            modoExtension,
            selectorMediaCodec,
            habilitarFallbackDecoders,
            manejadorEventos,
            oyenteEventos,
            msUnionPermitidoVideo,
            renderers,
        )
        renderers.add(
            RendererVideoSoftware(
                manejadorEventos,
                oyenteEventos,
                inventarioNativo,
            ),
        )
    }
}
