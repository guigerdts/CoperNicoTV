package com.copernicotv.app.player

import android.media.MediaCodecList
import android.os.Build
import com.copernicotv.core.player.DeclaracionFormatoVideo
import com.copernicotv.core.player.DecoderVideo
import com.copernicotv.core.player.DecoderVideoExterno
import com.copernicotv.core.player.SeleccionVideo
import com.copernicotv.core.player.TipoDecoder
import com.copernicotv.core.player.seleccionarDecoderVideo

/**
 * Inventario de decoders de video del dispositivo (E7-T11, S1).
 *
 * Construye el inventario una sola vez desde `MediaCodecList` (los códecs
 * del dispositivo no cambian en runtime) y delega la decisión en la política
 * pura de `:core`, con los decoders externos de la fase 2 al final. La
 * conversión `MediaCodecInfo → DecoderVideo` es la parte ligada al
 * framework; se cubre con pruebas instrumentadas.
 *
 * La detección hardware/software usa `MediaCodecInfo.isHardwareAccelerated`
 * desde la API 29; en 26–28 (minSdk) recurre a la convención de nombres de
 * AOSP: `OMX.google.*` y `c2.android.*` son software, el resto hardware.
 * Es una heurística declarada, no una certeza falsa.
 */
class InventarioDecodificadores(
    private val externos: List<DecoderVideoExterno> = emptyList(),
    private val fuente: () -> List<DecoderVideo> = ::inventarioNativo,
) {
    private val inventario: List<DecoderVideo> by lazy(fuente)

    fun seleccionar(declaracion: DeclaracionFormatoVideo?): SeleccionVideo = seleccionarDecoderVideo(declaracion, inventario, externos)

    private companion object {
        /** Convierte los decoders de video del dispositivo (E7-T11, S1/S3). */
        fun inventarioNativo(): List<DecoderVideo> {
            val lista = MediaCodecList(MediaCodecList.REGULAR_CODECS)
            return lista.codecInfos
                .flatMap { info -> info.supportedTypes.filter { it.startsWith("video/") } }
                .mapNotNull { mime -> decoderDe(info, mime) }
        }

        /** Traduce un `MediaCodecInfo` + MIME de video a datos puros de `:core`. */
        fun decoderDe(
            info: android.media.MediaCodecInfo,
            mime: String,
        ): DecoderVideo? {
            val capacidades =
                try {
                    info.getCapabilitiesForType(mime)
                } catch (noDeclara: IllegalArgumentException) {
                    return null
                }
            val video = capacidades.videoCapabilities ?: return null
            return DecoderVideo(
                nombre = info.name,
                mime = mime,
                tipo = tipoDe(info),
                perfiles = capacidades.profileLevels.map { it.profile }.distinct(),
                anchoMax = video.supportedWidths.upper,
                altoMax = video.supportedHeights.upper,
            )
        }

        /** Hardware primero; heurística por nombre solo bajo la API 29 (S2). */
        fun tipoDe(info: android.media.MediaCodecInfo): TipoDecoder =
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                if (info.isHardwareAccelerated) TipoDecoder.HARDWARE else TipoDecoder.SOFTWARE
            } else {
                val nombre = info.name.lowercase()
                if (nombre.contains("omx.google") || nombre.contains("c2.android")) {
                    TipoDecoder.SOFTWARE
                } else {
                    TipoDecoder.HARDWARE
                }
            }
    }
}
