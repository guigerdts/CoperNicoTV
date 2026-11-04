package com.copernicotv.core.player

import com.copernicotv.core.identity.ClaseUrl
import java.util.Locale

/**
 * Protocolo de transporte declarado por la URL de stream de un Canal (§5, S16).
 *
 * La clasificación describe el contenedor que la URL declara; no afirma que el
 * motor lo reproduzca ni que la reproducción esté validada. Admitir y persistir
 * una declaración no declara compatibilidad (S16, L30).
 */
enum class ProtocoloStream(val etiqueta: String) {
    HLS("HLS"),
    DASH("DASH"),
    PROGRESIVO("HTTP/HTTPS progresivo"),
    RTSP("RTSP"),
    SMOOTH_STREAMING("SmoothStreaming"),
    RTMP("RTMP"),
}

/**
 * Clasifica la URL canónica de `Canal.url_stream` según su clase HMAC y su
 * extensión.
 *
 * No decide si Media3 puede reproducirla: RTMP sigue en alcance condicionado y
 * su soporte real lo determina la capa de reproducción, no esta función.
 */
object ClasificadorStream {
    private const val HLS = ".m3u8"
    private const val DASH = ".mpd"
    private const val SMOOTH_ISM = ".ism/manifest"
    private const val SMOOTH_ISML = ".isml/manifest"

    fun clasificar(
        urlCanonica: String,
        clase: ClaseUrl,
    ): ProtocoloStream =
        when (clase) {
            ClaseUrl.RTSP -> ProtocoloStream.RTSP
            ClaseUrl.RTMP -> ProtocoloStream.RTMP
            // `content://` se rechaza para `Canal.url_stream` antes de llegar
            // aquí (S53b); la rama existe solo para cubrir la clase declarada.
            ClaseUrl.SAF -> ProtocoloStream.PROGRESIVO
            ClaseUrl.HTTP_HTTPS -> clasificarHttp(rutaDe(urlCanonica))
        }

    private fun clasificarHttp(ruta: String): ProtocoloStream {
        val minuscula = ruta.lowercase(Locale.ROOT)
        return when {
            minuscula.endsWith(HLS) -> ProtocoloStream.HLS
            minuscula.endsWith(DASH) -> ProtocoloStream.DASH
            minuscula.endsWith(SMOOTH_ISM) || minuscula.endsWith(SMOOTH_ISML) ->
                ProtocoloStream.SMOOTH_STREAMING
            else -> ProtocoloStream.PROGRESIVO
        }
    }

    /** Descarta query y fragmento para mirar solo la ruta del recurso. */
    private fun rutaDe(urlCanonica: String): String = urlCanonica.substringBefore('#').substringBefore('?')
}
