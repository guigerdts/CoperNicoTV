package com.copernicotv.app.player

import java.net.URLDecoder
import java.net.URLEncoder

/** Tipo de pista seleccionable durante la reproducción (E6-T3). */
enum class TipoPista {
    AUDIO,
    SUBTITULOS,
}

/**
 * Pista que el contenido actual ofrece, tal como la publica Media3.
 *
 * [grupo] es el índice del grupo dentro de `Tracks.groups` e [indice] la
 * posición dentro de ese grupo. Ambos identifican la pista mientras dure la
 * instantánea; una selección nueva siempre parte del estado vigente.
 */
data class PistaDisponible(
    val grupo: Int,
    val indice: Int,
    val tipo: TipoPista,
    val idioma: String?,
    val etiqueta: String?,
    val canales: Int,
    val forzada: Boolean,
    val activa: Boolean,
    val soportada: Boolean,
)

/**
 * Preferencia global de pista para audio o subtítulos (D3, E6-T3).
 *
 * Nunca crea pistas: solo ordena la selección cuando el contenido ofrece una
 * coincidencia. [Desactivada] únicamente tiene sentido para subtítulos.
 */
sealed interface PreferenciaPista {
    data object Automatica : PreferenciaPista

    data object Desactivada : PreferenciaPista

    data class Pista(
        val idioma: String?,
        val etiqueta: String?,
    ) : PreferenciaPista
}

/** Preferencias globales de audio y subtítulos (D3). */
data class PreferenciasPista(
    val audio: PreferenciaPista = PreferenciaPista.Automatica,
    val subtitulos: PreferenciaPista = PreferenciaPista.Automatica,
)

/**
 * Estado de pistas publicado por la sesión.
 *
 * [avisoAudio] y [avisoSubtitulos] solo se activan cuando hay una preferencia
 * concreta guardada y el contenido actual no ofrece una pista equivalente: en
 * ese caso la reproducción usa `auto/default`, la preferencia se conserva y la
 * interfaz debe avisarlo (D3, S44d).
 */
data class EstadoPistas(
    val audio: List<PistaDisponible> = emptyList(),
    val subtitulos: List<PistaDisponible> = emptyList(),
    val preferenciaAudio: PreferenciaPista = PreferenciaPista.Automatica,
    val preferenciaSubtitulos: PreferenciaPista = PreferenciaPista.Automatica,
    val avisoAudio: Boolean = false,
    val avisoSubtitulos: Boolean = false,
    val subtitulosDesactivados: Boolean = true,
) {
    val audioActivo: PistaDisponible? get() = audio.firstOrNull { it.activa }

    val subtitulosActivos: PistaDisponible? get() = subtitulos.firstOrNull { it.activa }

    val hayAviso: Boolean get() = avisoAudio || avisoSubtitulos
}

/** Devuelve la pista que satisface [preferencia], si el contenido la ofrece. */
fun buscarPista(
    pistas: List<PistaDisponible>,
    preferencia: PreferenciaPista.Pista,
): PistaDisponible? {
    val idioma = preferencia.idioma?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val etiqueta = preferencia.etiqueta?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }
    val exacta =
        pistas.firstOrNull { pista ->
            pista.soportada &&
                pista.idioma?.trim()?.lowercase() == idioma &&
                pista.etiqueta?.trim()?.lowercase() == etiqueta
        }
    if (exacta != null) return exacta
    if (idioma == null) return null
    return pistas.firstOrNull { it.soportada && it.idioma?.trim()?.lowercase() == idioma }
}

/** Indica si una preferencia concreta quedó sin cobertura en el contenido actual. */
fun preferenciaNoDisponible(
    pistas: List<PistaDisponible>,
    preferencia: PreferenciaPista,
): Boolean = preferencia is PreferenciaPista.Pista && buscarPista(pistas, preferencia) == null

/**
 * Etiqueta identificable de una pista: título del contenedor o nombre del
 * idioma. Devuelve `null` cuando el contenido no aporta ninguno de los dos,
 * para que la presentación decida el texto de reserva.
 */
fun etiquetaPista(pista: PistaDisponible): String? {
    val titulo = pista.etiqueta?.trim()?.takeIf { it.isNotEmpty() }
    return titulo ?: nombreIdioma(pista.idioma)
}

/** Nombre legible de un código ISO 639; `null` si no identifica un idioma. */
fun nombreIdioma(codigo: String?): String? {
    val normalizado = codigo?.trim()?.lowercase()?.takeIf { it.isNotEmpty() } ?: return null
    if (normalizado == "und" || normalizado == "zxx" || normalizado == "mul") return null
    return NOMBRES_IDIOMA[normalizado] ?: normalizado.uppercase()
}

/** Serializa una preferencia para persistirla (S44a). */
fun codificarPreferencia(preferencia: PreferenciaPista): String =
    when (preferencia) {
        PreferenciaPista.Automatica -> VALOR_AUTOMATICA
        PreferenciaPista.Desactivada -> VALOR_DESACTIVADA
        is PreferenciaPista.Pista -> "$PREFIJO_PISTA:${codificarCampo(preferencia.idioma)}:${codificarCampo(preferencia.etiqueta)}"
    }

/** Reconstruye una preferencia persistida; un valor inválido cae en automático. */
fun decodificarPreferencia(valor: String?): PreferenciaPista {
    val texto = valor?.trim().orEmpty()
    return when {
        texto.isEmpty() || texto == VALOR_AUTOMATICA -> PreferenciaPista.Automatica
        texto == VALOR_DESACTIVADA -> PreferenciaPista.Desactivada
        texto.startsWith("$PREFIJO_PISTA:") -> decodificarPista(texto)
        else -> PreferenciaPista.Automatica
    }
}

private fun decodificarPista(texto: String): PreferenciaPista {
    val partes = texto.split(':', limit = 3)
    if (partes.size != 3) return PreferenciaPista.Automatica
    val idioma = decodificarCampo(partes[1])
    val etiqueta = decodificarCampo(partes[2])
    if (idioma == null && etiqueta == null) return PreferenciaPista.Automatica
    return PreferenciaPista.Pista(idioma, etiqueta)
}

private fun codificarCampo(valor: String?): String = URLEncoder.encode(valor.orEmpty(), "UTF-8")

private fun decodificarCampo(valor: String): String? =
    runCatching { URLDecoder.decode(valor, "UTF-8") }
        .getOrNull()
        ?.takeIf { it.isNotEmpty() }

private const val VALOR_AUTOMATICA = "auto"
private const val VALOR_DESACTIVADA = "off"
private const val PREFIJO_PISTA = "pista"

private val NOMBRES_IDIOMA =
    mapOf(
        "spa" to "Español",
        "es" to "Español",
        "eng" to "Inglés",
        "en" to "Inglés",
        "por" to "Portugués",
        "pt" to "Portugués",
        "fra" to "Francés",
        "fre" to "Francés",
        "fr" to "Francés",
        "deu" to "Alemán",
        "ger" to "Alemán",
        "de" to "Alemán",
        "ita" to "Italiano",
        "it" to "Italiano",
        "jpn" to "Japonés",
        "ja" to "Japonés",
        "zho" to "Chino",
        "chi" to "Chino",
        "zh" to "Chino",
        "ara" to "Árabe",
        "ar" to "Árabe",
        "rus" to "Ruso",
        "ru" to "Ruso",
        "hin" to "Hindi",
        "hi" to "Hindi",
        "kor" to "Coreano",
        "ko" to "Coreano",
        "nld" to "Neerlandés",
        "dut" to "Neerlandés",
        "nl" to "Neerlandés",
        "swe" to "Sueco",
        "sv" to "Sueco",
        "pol" to "Polaco",
        "pl" to "Polaco",
        "tur" to "Turco",
        "tr" to "Turco",
        "ell" to "Griego",
        "gre" to "Griego",
        "el" to "Griego",
    )
