package com.copernicotv.core.player

/**
 * Ruta de decodificación software de `:core` (E7-T12, S2).
 *
 * La decisión de qué MIME cubre el decoder externo FFmpeg (LGPL) es pura y
 * JVM-testeable: el mapa MIME → codec de libavcodec y la integración del
 * decoder externo en la política existente de [seleccionarDecoderVideo]
 * (hardware → software nativo → externos, E7-T11, S4).
 *
 * El renderer externo de `:app` consulta exactamente esta política: reporta
 * soporte solo cuando la selección recae en el decoder externo, de modo que
 * la decisión visible en el diagnóstico y la decisión de render son la misma.
 */
object DecodersSoftware {
    /**
     * Codec de libavcodec que decodifica el MIME dado (E7-T12, S3: matriz
     * inicial MPEG-2 → MPEG-4 Part 2 → H.263; HEVC queda para evaluación
     * posterior y H.264 nunca entra por software: va por hardware).
     *
     * Devuelve `null` para MIMEs que el subconjunto no incluye: la política
     * no finge soporte de lo que no se compiló.
     */
    fun codecDeMime(mime: String?): String? =
        when (mime?.lowercase()) {
            "video/mpeg2" -> "mpeg2video"
            "video/mp4v-es" -> "mpeg4"
            "video/3gpp" -> "h263"
            else -> null
        }

    /** Nombre de decoder que se reporta en el diagnóstico para un codec. */
    fun nombreDeCodec(codec: String): String = "ffmpeg:$codec"
}

/**
 * Decoder externo software FFmpeg (E7-T12, S2), integrado como el último
 * candidato de la política de E7-T11: solo se selecciona cuando ningún
 * decoder nativo del dispositivo (hardware ni software MediaCodec) declara
 * el MIME. Sin límites de resolución declarados: libavcodec decodifica la
 * resolución del stream sin límite impuesto por esta capa.
 */
class DecoderSoftwareVideo : DecoderVideoExterno {
    override val nombre: String = "ffmpeg-software"

    override val tipo: TipoDecoder = TipoDecoder.SOFTWARE

    override fun declarar(mime: String): DecoderVideo? {
        val codec = DecodersSoftware.codecDeMime(mime) ?: return null
        return DecoderVideo(
            nombre = DecodersSoftware.nombreDeCodec(codec),
            mime = mime,
            tipo = TipoDecoder.SOFTWARE,
        )
    }
}
