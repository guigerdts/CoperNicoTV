package com.copernicotv.core.epg

/**
 * Canal del catálogo que se quiere asociar con un canal de guía.
 *
 * [tvgId] es la única señal de identidad fuerte; el nombre solo propone.
 */
data class CanalAsociableEntrada(
    val canalId: Long,
    val nombre: String?,
    val tvgId: String?,
)

/** Canal declarado por la guía: id contractual y todos sus `display-name`. */
data class CanalGuiaEntrada(
    val channelIdTexto: String,
    val nombres: List<String>,
)

/** Nivel de confianza de una propuesta, derivado del puntaje. */
enum class NivelPropuesta {
    EXACTA,
    ALTA,
    MEDIA,
    BAJA,
}

/**
 * Propuesta de asociación con su puntaje y sus motivos.
 *
 * [puntaje] va de 0 a 100 y sirve para ordenar; no autoriza por sí solo una
 * asociación automática.
 */
data class PropuestaAsociacion(
    val channelIdTexto: String,
    val puntaje: Int,
    val nivel: NivelPropuesta,
    val motivos: List<String>,
)

/** Qué puede hacer la aplicación sin intervención del usuario. */
enum class DecisionAsociacion {
    /** Hay una única propuesta inequívoca y se puede confirmar sin preguntar. */
    AUTOMATICA,

    /** Hay propuestas pero ninguna es segura: requiere confirmación manual. */
    PENDIENTE,

    /** No hay ninguna propuesta razonable. */
    SIN_CANDIDATO,
}

/**
 * Resultado de proponer asociaciones para un canal.
 *
 * [motivo] es un código estable para diagnóstico y UI
 * (`id_exacto`, `nombre_exacto_unico`, `empate`, `semejanza`, `sin_candidato`).
 */
data class ResultadoPropuestas(
    val propuestas: List<PropuestaAsociacion>,
    val decision: DecisionAsociacion,
    val motivo: String,
)

/**
 * Heurística de propuestas de asociación M3U ↔ XMLTV.
 *
 * Reglas deliberadamente conservadoras, en este orden:
 * 1. `tvg-id` igual al `id` del canal de guía ⇒ puntaje 100, decisión
 *    automática si es el único candidato con ese puntaje.
 * 2. Nombre normalizado idéntico ⇒ puntaje 90; automática solo si es único.
 * 3. Un nombre de guía contenido en el del canal (o al revés), con al menos
 *    cuatro caracteres ⇒ puntaje 60. Nunca automática.
 * 4. Solapamiento de palabras ≥ 0,75 con dos o más palabras por lado ⇒ hasta
 *    75 puntos. Nunca automática.
 *
 * Todo lo demás no se propone. La semejanza superficial nunca asocia: como
 * mucho, alimenta una propuesta que el usuario debe confirmar.
 */
object HeuristicaPropuestasEpg {
    const val PUNTAJE_ID_EXACTO: Int = 100
    const val PUNTAJE_NOMBRE_EXACTO: Int = 90
    const val PUNTAJE_CONTENIDO: Int = 60
    const val MAXIMO_PROPUESTAS: Int = 5
    private const val MINIMO_CARACTERES_CONTENIDO = 4
    private const val MINIMO_PALABRAS_SOLAPAMIENTO = 2
    private const val UMBRAL_SOLAPAMIENTO = 0.75

    fun proponer(
        canal: CanalAsociableEntrada,
        guia: List<CanalGuiaEntrada>,
        maximo: Int = MAXIMO_PROPUESTAS,
    ): ResultadoPropuestas {
        val tvgId = canal.tvgId?.trim()?.takeIf { it.isNotEmpty() }?.lowercase()
        val nombreCanal = normalizarNombre(canal.nombre)
        val tokensCanal = palabras(nombreCanal)
        val propuestas =
            guia.mapNotNull { candidato ->
                puntuar(candidato, tvgId, nombreCanal, tokensCanal)
            }.sortedWith(compareByDescending<PropuestaAsociacion> { it.puntaje }.thenBy { it.channelIdTexto })

        val recortadas = propuestas.take(maximo.coerceAtLeast(1))
        if (recortadas.isEmpty()) {
            return ResultadoPropuestas(emptyList(), DecisionAsociacion.SIN_CANDIDATO, "sin_candidato")
        }
        val mejor = recortadas.first().puntaje
        val empatadas = recortadas.count { it.puntaje == mejor }
        return when {
            mejor >= PUNTAJE_ID_EXACTO && empatadas == 1 ->
                ResultadoPropuestas(recortadas, DecisionAsociacion.AUTOMATICA, "id_exacto")
            mejor >= PUNTAJE_NOMBRE_EXACTO && empatadas == 1 ->
                ResultadoPropuestas(recortadas, DecisionAsociacion.AUTOMATICA, "nombre_exacto_unico")
            empatadas > 1 -> ResultadoPropuestas(recortadas, DecisionAsociacion.PENDIENTE, "empate")
            else -> ResultadoPropuestas(recortadas, DecisionAsociacion.PENDIENTE, "semejanza")
        }
    }

    /** Normaliza un nombre para comparar: NFC, minúsculas, sin puntuación. */
    fun normalizarNombre(valor: String?): String {
        if (valor.isNullOrBlank()) return ""
        val base =
            runCatching { HuellaAsociacion.normalizarEpg(valor) }
                .getOrElse { valor.lowercase() }
        return base
            .map { caracter -> if (caracter.isLetterOrDigit()) caracter else ' ' }
            .joinToString("")
            .split(' ')
            .filter { it.isNotEmpty() }
            .joinToString(" ")
    }

    private fun puntuar(
        candidato: CanalGuiaEntrada,
        tvgId: String?,
        nombreCanal: String,
        tokensCanal: Set<String>,
    ): PropuestaAsociacion? {
        val id = candidato.channelIdTexto.trim()
        if (id.isEmpty()) return null
        if (tvgId != null && id.lowercase() == tvgId) {
            return PropuestaAsociacion(id, PUNTAJE_ID_EXACTO, NivelPropuesta.EXACTA, listOf("id_exacto"))
        }
        val nombres = candidato.nombres.mapNotNull { normalizarNombre(it).takeIf { texto -> texto.isNotEmpty() } }
        if (nombres.isEmpty()) return null
        if (nombreCanal.isNotEmpty() && nombreCanal in nombres) {
            return PropuestaAsociacion(id, PUNTAJE_NOMBRE_EXACTO, NivelPropuesta.ALTA, listOf("nombre_exacto"))
        }
        var mejor: PropuestaAsociacion? = null
        for (nombre in nombres) {
            val candidata = puntuarPorSemejanza(id, nombre, nombreCanal, tokensCanal) ?: continue
            if (mejor == null || candidata.puntaje > mejor.puntaje) mejor = candidata
        }
        return mejor
    }

    private fun puntuarPorSemejanza(
        id: String,
        nombreGuia: String,
        nombreCanal: String,
        tokensCanal: Set<String>,
    ): PropuestaAsociacion? {
        if (esNombreContenido(nombreGuia, nombreCanal)) {
            return PropuestaAsociacion(id, PUNTAJE_CONTENIDO, NivelPropuesta.MEDIA, listOf("nombre_contenido"))
        }
        val tokensGuia = palabras(nombreGuia)
        if (tokensCanal.size < MINIMO_PALABRAS_SOLAPAMIENTO || tokensGuia.size < MINIMO_PALABRAS_SOLAPAMIENTO) {
            return null
        }
        val comunes = tokensCanal.intersect(tokensGuia).size
        val union = tokensCanal.union(tokensGuia).size
        if (union == 0) return null
        val solapamiento = comunes.toDouble() / union
        if (solapamiento < UMBRAL_SOLAPAMIENTO) return null
        val puntaje = (PUNTAJE_CONTENIDO + (solapamiento * 15).toInt()).coerceAtMost(75)
        return PropuestaAsociacion(id, puntaje, NivelPropuesta.MEDIA, listOf("palabras_en_comun"))
    }

    private fun palabras(nombreNormalizado: String): Set<String> = nombreNormalizado.split(' ').filter { it.isNotEmpty() }.toSet()

    /** True si uno de los dos nombres contiene al otro con longitud suficiente. */
    private fun esNombreContenido(
        nombreGuia: String,
        nombreCanal: String,
    ): Boolean {
        if (nombreGuia.isEmpty() || nombreCanal.isEmpty()) return false
        val guiaEnCanal = nombreGuia.length >= MINIMO_CARACTERES_CONTENIDO && nombreCanal.contains(nombreGuia)
        val canalEnGuia = nombreCanal.length >= MINIMO_CARACTERES_CONTENIDO && nombreGuia.contains(nombreCanal)
        return guiaEnCanal || canalEnGuia
    }
}
