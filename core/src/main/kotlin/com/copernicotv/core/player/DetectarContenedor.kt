package com.copernicotv.core.player

/**
 * Formato observado en la evidencia de una respuesta HTTP (E7-T9, S3).
 *
 * Es una observación, no una promesa: `HLS` significa «la respuesta se ve
 * como una lista HLS», no «el canal es HLS y se reproduce». `HTML` marca una
 * respuesta que no es un stream (autenticación, bloqueo o error del panel).
 */
enum class FormatoObservado(val etiqueta: String) {
    HLS("HLS"),
    DASH("DASH"),
    TS("MPEG-TS"),
    MP4("MP4"),
    MP3("MP3"),
    AAC("AAC"),
    HTML("HTML"),
    DESCONOCIDO("desconocido"),
}

/**
 * Detector de contenedor por evidencia combinada (E7-T9, S3).
 *
 * Combina los bytes iniciales de la respuesta (evidencia primaria, porque el
 * MIME declarado y la extensión de la URL no garantizan el contenido) con el
 * MIME de la respuesta. Reglas de evidencia:
 *
 * - Los bytes deciden primero: un servidor puede servir una lista HLS con
 *   `text/html` u `application/octet-stream`, o una página de bloqueo con
 *   MIME de video.
 * - El MIME decide solo cuando los bytes no alcanzan para clasificar
 *   (respuesta truncada o sin patrón reconocible).
 * - Una respuesta HTML se clasifica como `HTML` y nunca como stream (S3).
 * - MPEG-TS exige el byte de sincronía `0x47` con periodicidad de paquete
 *   (188 bytes): un único `0x47` al inicio no es evidencia suficiente.
 *
 * La re-selección de MediaSource (S4) solo es plausible para HLS y DASH:
 * para los contenedores progresivos ya actuaron los extractores de Media3 y
 * repetirlos con otro MIME no aporta nada.
 */
object DetectarContenedor {
    /** Constantes de MIME de Media3 como literales verificados en :app. */
    const val MIME_HLS = "application/vnd.apple.mpegurl"
    const val MIME_DASH = "application/dash+xml"

    /** Longitud de muestra suficiente para las reglas de evidencia. */
    const val LONGITUD_MUESTRA = 2_048

    private val PREFIJO_HLS = "#EXTM3U"
    private val MARCA_MP4 = "ftyp"

    /** Observa el formato de la respuesta a partir de su MIME y sus bytes. */
    fun observar(
        tipoMime: String?,
        bytes: ByteArray,
    ): FormatoObservado {
        val porBytes = porBytes(bytes)
        if (porBytes != FormatoObservado.DESCONOCIDO) return porBytes
        return porMime(tipoMime)
    }

    /**
     * MIME de Media3 para re-seleccionar la fuente (S4): solo HLS y DASH
     * tienen un `MediaSource` alternativo plausiblemente distinto del
     * progresivo que ya falló. Los demás formatos no se re-seleccionan.
     */
    fun mimeTypeDeReSeleccion(formato: FormatoObservado): String? =
        when (formato) {
            FormatoObservado.HLS -> MIME_HLS
            FormatoObservado.DASH -> MIME_DASH
            FormatoObservado.TS,
            FormatoObservado.MP4,
            FormatoObservado.MP3,
            FormatoObservado.AAC,
            FormatoObservado.HTML,
            FormatoObservado.DESCONOCIDO,
            -> null
        }

    /** Clasifica por bytes iniciales; `DESCONOCIDO` si no hay evidencia. */
    private fun porBytes(bytes: ByteArray): FormatoObservado {
        val inicio = encabezado(bytes)
        if (inicio.startsWith(PREFIJO_HLS)) return FormatoObservado.HLS
        if (inicio.contains("<!DOCTYPE", ignoreCase = true) || inicio.contains("<HTML", ignoreCase = true)) {
            return FormatoObservado.HTML
        }
        if (inicio.contains("<MPD", ignoreCase = true)) return FormatoObservado.DASH
        if (esTs(bytes)) return FormatoObservado.TS
        if (bytes.size >= 8 && String(bytes, 4, 4, Charsets.US_ASCII) == MARCA_MP4) return FormatoObservado.MP4
        if (inicio.startsWith("ID3")) return FormatoObservado.MP3
        if (bytes.size >= 3 && bytes[0] == 0xFF.toByte() && (bytes[1].toInt() and 0xE0) == 0xE0) {
            return if ((bytes[1].toInt() shr 1) and 0x03 == 0) FormatoObservado.AAC else FormatoObservado.MP3
        }
        return FormatoObservado.DESCONOCIDO
    }

    /** Descarta BOM y espacios previos para leer las marcas de texto. */
    private fun encabezado(bytes: ByteArray): String {
        var desde = 0
        if (bytes.size >= 3 && bytes[0] == 0xEF.toByte() && bytes[1] == 0xBB.toByte() && bytes[2] == 0xBF.toByte()) {
            desde = 3
        }
        val esEspacio = byteArrayOf(' '.code.toByte(), '\n'.code.toByte(), '\r'.code.toByte(), '\t'.code.toByte())
        while (desde < bytes.size && bytes[desde] in esEspacio) {
            desde += 1
        }
        val fin = minOf(bytes.size, desde + LONGITUD_MUESTRA)
        return if (desde >= fin) "" else String(bytes, desde, fin - desde, Charsets.UTF_8)
    }

    /**
     * MPEG-TS: el primer byte es `0x47` y los paquetes se repiten cada 188
     * bytes. Con muestras largas se exige la periodicidad completa; con
     * muestras cortas basta el primer par de sincronías.
     */
    private fun esTs(bytes: ByteArray): Boolean {
        if (bytes.isEmpty() || bytes[0] != 0x47.toByte()) return false
        if (bytes.size < 188) return true
        if (bytes[188] != 0x47.toByte()) return false
        return bytes.size < 376 || bytes[376] == 0x47.toByte()
    }

    /** Clasifica por MIME declarado cuando los bytes no deciden. */
    private fun porMime(tipoMime: String?): FormatoObservado {
        val minuscula = tipoMime?.lowercase()?.substringBefore(';')?.trim() ?: return FormatoObservado.DESCONOCIDO
        return when {
            minuscula in listOf("application/vnd.apple.mpegurl", "vnd.apple.mpegurl", "application/x-mpegurl") -> FormatoObservado.HLS
            minuscula == "application/dash+xml" -> FormatoObservado.DASH
            minuscula == "video/mp2t" -> FormatoObservado.TS
            minuscula == "video/mp4" -> FormatoObservado.MP4
            minuscula == "audio/mpeg" -> FormatoObservado.MP3
            minuscula == "audio/aac" -> FormatoObservado.AAC
            minuscula.startsWith("text/html") -> FormatoObservado.HTML
            else -> FormatoObservado.DESCONOCIDO
        }
    }
}
