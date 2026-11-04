package com.copernicotv.core.identity

/** Motivo tipado por el que un `tvg-id` no es utilizable. */
enum class MotivoTvgId(val codigo: String) {
    AUSENTE("tvg_id_ausente"),
    VACIO_TRAS_RECORTE("tvg_id_vacio"),
    CONTIENE_CONTROL("tvg_id_control"),
    SURROGATE_INVALIDO("tvg_id_surrogate_invalido"),
    EXCEDE_LONGITUD("tvg_id_longitud"),
}

/** Resultado de validar un `tvg-id` (DC-01, ADR-0004). */
sealed interface ResultadoTvgId {
    data class Valido(val valor: String) : ResultadoTvgId

    data class Invalido(val motivo: MotivoTvgId) : ResultadoTvgId
}

/**
 * Validación de `tvg-id` (DC-01 punto 2).
 *
 * Un valor es válido si tras recorte exterior `White_Space` no queda vacío, no
 * contiene caracteres `Cc`/`Cf` y no supera 512 code points. No se impone un
 * alfabeto adicional. El valor válido se persiste ya recortado.
 */
object ValidadorTvgId {
    const val MAXIMO_CODE_POINTS: Int = 512

    /** Valida y recorta [valor]. */
    fun validar(valor: String?): ResultadoTvgId {
        if (valor == null) return ResultadoTvgId.Invalido(MotivoTvgId.AUSENTE)
        val recortado = trimExtUnicode(valor)
        if (recortado.isEmpty()) return ResultadoTvgId.Invalido(MotivoTvgId.VACIO_TRAS_RECORTE)
        if (!surrogatesValidos(recortado)) {
            return ResultadoTvgId.Invalido(MotivoTvgId.SURROGATE_INVALIDO)
        }
        for (caracter in recortado) {
            when (Character.getType(caracter)) {
                Character.CONTROL.toInt(), Character.FORMAT.toInt() ->
                    return ResultadoTvgId.Invalido(MotivoTvgId.CONTIENE_CONTROL)
            }
        }
        if (recortado.codePointCount(0, recortado.length) > MAXIMO_CODE_POINTS) {
            return ResultadoTvgId.Invalido(MotivoTvgId.EXCEDE_LONGITUD)
        }
        return ResultadoTvgId.Valido(recortado)
    }

    private fun surrogatesValidos(valor: String): Boolean =
        try {
            validarSurrogates(valor)
            true
        } catch (_: SurrogateNoEmparejadoException) {
            false
        }
}
