package com.copernicotv.core.player

/**
 * Tipo de un decoder de video disponible en el dispositivo (E7-T11, S1).
 *
 * `HARDWARE` cubre los decoders acelerados por el fabricante; `SOFTWARE`
 * los puramente en CPU (incluidos los `c2.android.*` de AOSP y los que la
 * fase 2 integre como decoders externos).
 */
enum class TipoDecoder {
    HARDWARE,
    SOFTWARE,
}

/**
 * Motivo del veredicto de soporte de la pista de video (E7-T11, S5).
 *
 * Cada valor responde exactamente «por qué esta pista sí/no puede
 * reproducirse en este dispositivo», sin confundir pista inexistente con
 * formato no soportado ni con límites de perfil o resolución.
 */
enum class MotivoSoporteVideo {
    /** Sin pista de video declarada: no hay nada que evaluar. */
    PISTA_AUSENTE,

    /** Ningún decoder del dispositivo (ni externo) declara el MIME. */
    MIME_NO_SOPORTADO,

    /** Hay decoder para el MIME pero el perfil/nivel no está soportado. */
    PERFIL_NO_SOPORTADO,

    /** Hay decoder para el MIME pero la resolución excede los límites. */
    RESOLUCION_NO_SOPORTADA,

    /** Decoder compatible encontrado y seleccionado. */
    SOPORTADO,
}

/** Perfil y nivel declarados por un codec string («avc1.640028» → 0x64/0x28). */
data class PerfilNivel(
    val perfil: Int,
    val nivel: Int,
)

/** Declaración del formato de video que la sesión quiere reproducir (E7-T11, S3). */
data class DeclaracionFormatoVideo(
    val mime: String? = null,
    val codec: String? = null,
    val ancho: Int? = null,
    val alto: Int? = null,
)

/**
 * Parsea el perfil y el nivel de un codec string (RFC 6381).
 *
 * Solo cuando el string realmente los declara de forma inequívoca:
 * `avc1`/`avc3` llevan perfil y nivel hexadecimales tras el punto; los
 * strings de HEVC (`hev1`/`hvc1`), H.263, MPEG-4 visual y los MIME sin
 * codec string (MPEG-2 en TS) quedan desconocidos — la política no finge
 * rechazo de perfil con datos que no existen.
 */
fun perfilNivelDeCodec(codec: String?): PerfilNivel? {
    if (codec.isNullOrEmpty()) return null
    val partes = codec.split('.')
    if (partes.size < 2) return null
    val tipo = partes[0].lowercase()
    if (tipo != "avc1" && tipo != "avc3") return null
    val hex = partes[1]
    if (hex.length != 6 || hex.any { it.lowercaseChar() !in '0'..'9' && it.lowercaseChar() !in 'a'..'f' }) {
        return null
    }
    val perfil = hex.substring(0, 2).toInt(16)
    val nivel = hex.substring(4, 6).toInt(16)
    return PerfilNivel(perfil, nivel)
}

/**
 * Un decoder de video del dispositivo (E7-T11, S1).
 *
 * El inventario lo construye el servicio Android desde `MediaCodecList`;
 * los límites de perfil y resolución se representan como datos puros para
 * que la decisión sea JVM-testeable. Lista de perfiles vacía = el
 * dispositivo no declara restricción de perfil; límites `null` = sin
 * límite de resolución conocido.
 */
data class DecoderVideo(
    val nombre: String,
    val mime: String,
    val tipo: TipoDecoder,
    /** Perfiles soportados (0xNN); vacío = sin restricción de perfil declarada. */
    val perfiles: List<Int> = emptyList(),
    val anchoMax: Int? = null,
    val altoMax: Int? = null,
)

/**
 * Interfaz de integración para decoders externos (E7-T11, S8).
 *
 * La fase 2 (decodificación software bajo LGPL, ADR-0008) registra aquí
 * sus decoders; la política los consulta SIEMPRE al final, después del
 * hardware y del software nativo del dispositivo. Fase 1 no implementa
 * ningún decoder externo.
 */
interface DecoderVideoExterno {
    val nombre: String
    val tipo: TipoDecoder

    /** El decoder que este proveedor aporta para el MIME dado; `null` si no aplica. */
    fun declarar(mime: String): DecoderVideo?
}

/** Resultado de la selección de decoder para una declaración de formato (S4/S5). */
data class SeleccionVideo(
    val motivo: MotivoSoporteVideo,
    val decoder: String? = null,
    val tipo: TipoDecoder? = null,
    /** Decoders consultados y descartados, en orden de evaluación. */
    val rechazados: List<String> = emptyList(),
)

/**
 * Selecciona el decoder de video para la declaración dada (E7-T11, S4).
 *
 * Orden de preferencia: hardware del dispositivo, software del dispositivo,
 * decoders externos registrados. Cada candidato se evalúa por MIME, perfil
 * declarado y límites de resolución; el veredicto nombra el motivo exacto
 * cuando nada sirve. Nunca cambia de decoder durante la sesión: la consulta
 * es estática por formato.
 */
fun seleccionarDecoderVideo(
    declaracion: DeclaracionFormatoVideo?,
    inventario: List<DecoderVideo>,
    externos: List<DecoderVideoExterno> = emptyList(),
): SeleccionVideo {
    val mime = declaracion?.mime ?: return SeleccionVideo(MotivoSoporteVideo.PISTA_AUSENTE)
    val perfilNivel = perfilNivelDeCodec(declaracion.codec)

    val candidatos =
        inventario
            .filter { it.mime.equals(mime, ignoreCase = true) }
            .sortedBy { if (it.tipo == TipoDecoder.HARDWARE) 0 else 1 } +
            externos.mapNotNull { it.declarar(mime) }

    if (candidatos.isEmpty()) {
        return SeleccionVideo(MotivoSoporteVideo.MIME_NO_SOPORTADO)
    }

    var rechazoPerfil = false
    val descartados = mutableListOf<String>()
    for (candidato in candidatos) {
        val perfil = perfilNivel?.perfil
        if (
            perfil != null &&
            candidato.perfiles.isNotEmpty() &&
            candidato.perfiles.none { it == perfil }
        ) {
            rechazoPerfil = true
            descartados += candidato.nombre
            continue
        }
        val fueraDeLimites =
            (declaracion.ancho != null && candidato.anchoMax != null && declaracion.ancho > candidato.anchoMax) ||
                (declaracion.alto != null && candidato.altoMax != null && declaracion.alto > candidato.altoMax)
        if (fueraDeLimites) {
            descartados += candidato.nombre
            continue
        }
        return SeleccionVideo(
            MotivoSoporteVideo.SOPORTADO,
            candidato.nombre,
            candidato.tipo,
            descartados,
        )
    }

    return if (rechazoPerfil) {
        SeleccionVideo(MotivoSoporteVideo.PERFIL_NO_SOPORTADO, rechazados = descartados)
    } else {
        SeleccionVideo(MotivoSoporteVideo.RESOLUCION_NO_SOPORTADA, rechazados = descartados)
    }
}
