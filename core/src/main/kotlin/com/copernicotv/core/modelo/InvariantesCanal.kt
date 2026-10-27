package com.copernicotv.core.modelo

import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.EstadoCanal
import com.copernicotv.core.identity.ResultadoTvgId
import com.copernicotv.core.identity.ValidadorTvgId
import com.copernicotv.core.identity.trimExtUnicode

/**
 * Instantánea de los campos de identidad de una fila `Canal` que el escritor
 * debe validar antes de persistir (§6.3.2).
 */
data class FilaCanal(
    val nombre: String?,
    val estado: EstadoCanal,
    val claveDerivable: Boolean,
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val tvgId: String?,
    val urlStreamCifradaPresente: Boolean,
    val urlStreamHmacPresente: Boolean,
)

/** Códigos tipados de incumplimiento de las invariantes del escritor de `Canal`. */
enum class ViolacionCanal(val codigo: String) {
    CLAVE_UNICIDAD_CON_DERIVABLE_FALSO("clave_unicidad_con_clave_derivable_falso"),
    CLAVE_UNICIDAD_EN_ESTADO_BC("clave_unicidad_en_estado_bc"),
    CLAVE_UNICIDAD_SIN_ESTADO_A_DERIVABLE("clave_unicidad_sin_estado_a_derivable"),
    ESTADO_A_DERIVABLE_SIN_CLAVE_UNICIDAD("estado_a_derivable_sin_clave_unicidad"),
    CLAVE_CANONICA_SIN_NOMBRE("clave_canonica_sin_nombre"),
    NOMBRE_SIN_CLAVE_CANONICA("nombre_sin_clave_canonica"),
    CLAVE_CANONICA_PREFIJO_INVALIDO("clave_canonica_prefijo_invalido"),
    CLAVE_CANONICA_DIGEST_INVALIDO("clave_canonica_digest_invalido"),
    TVG_ID_INVALIDO("tvg_id_invalido"),
    CLAVE_UNICIDAD_TVG_INCONSISTENTE("clave_unicidad_tvg_inconsistente"),
    CLAVE_UNICIDAD_CANONICA_INCONSISTENTE("clave_unicidad_canonica_inconsistente"),
    URL_STREAM_AUSENTE("url_stream_ausente"),
    URL_STREAM_HMAC_AUSENTE("url_stream_hmac_ausente"),
}

/**
 * Invariantes del escritor de `Canal` (§6.3.2).
 *
 * Valida la coherencia entre `estado`, `clave_derivable`, `clave_canonica`,
 * `clave_unicidad`, `nombre` y `tvg_id`. No canonicaliza URLs ni descifra: la
 * URL original y su HMAC llegan ya calculados por el camino de escritura.
 *
 * @param tvgIdUnicoEnFuente `true` solo si el `tvg-id` es válido y único en la
 *   Fuente. Determina si `clave_unicidad` debe usar `tvg:v1:` o la canónica.
 */
object InvariantesCanal {
    private val DIGEST = Regex("^[0-9a-f]{64}$")

    fun validar(
        fila: FilaCanal,
        tvgIdUnicoEnFuente: Boolean,
    ): List<ViolacionCanal> {
        val violaciones = mutableListOf<ViolacionCanal>()
        validarUrl(violaciones, fila)
        validarNombre(violaciones, fila)
        validarTvgId(violaciones, fila)
        validarCorrelacion(violaciones, fila)
        validarClaveUnicidad(violaciones, fila, tvgIdUnicoEnFuente)
        return violaciones
    }

    private fun validarUrl(
        violaciones: MutableList<ViolacionCanal>,
        fila: FilaCanal,
    ) {
        if (!fila.urlStreamCifradaPresente) violaciones += ViolacionCanal.URL_STREAM_AUSENTE
        if (!fila.urlStreamHmacPresente) violaciones += ViolacionCanal.URL_STREAM_HMAC_AUSENTE
    }

    private fun validarNombre(
        violaciones: MutableList<ViolacionCanal>,
        fila: FilaCanal,
    ) {
        val nombreUtil = fila.nombre != null && trimExtUnicode(fila.nombre).isNotEmpty()
        if (!nombreUtil && fila.claveCanonica != null) {
            violaciones += ViolacionCanal.CLAVE_CANONICA_SIN_NOMBRE
        }
        if (nombreUtil && fila.claveCanonica == null) {
            violaciones += ViolacionCanal.NOMBRE_SIN_CLAVE_CANONICA
        }
        val canonica = fila.claveCanonica ?: return
        if (!canonica.startsWith(ClaveCanal.PREFIJO)) {
            violaciones += ViolacionCanal.CLAVE_CANONICA_PREFIJO_INVALIDO
        } else if (!DIGEST.matches(canonica.removePrefix(ClaveCanal.PREFIJO))) {
            violaciones += ViolacionCanal.CLAVE_CANONICA_DIGEST_INVALIDO
        }
    }

    private fun validarTvgId(
        violaciones: MutableList<ViolacionCanal>,
        fila: FilaCanal,
    ) {
        val tvg = fila.tvgId ?: return
        if (ValidadorTvgId.validar(tvg) !is ResultadoTvgId.Valido) {
            violaciones += ViolacionCanal.TVG_ID_INVALIDO
        }
    }

    private fun validarCorrelacion(
        violaciones: MutableList<ViolacionCanal>,
        fila: FilaCanal,
    ) {
        val deberiaTenerClave = fila.estado == EstadoCanal.A && fila.claveDerivable
        if (fila.claveUnicidad != null && !fila.claveDerivable) {
            violaciones += ViolacionCanal.CLAVE_UNICIDAD_CON_DERIVABLE_FALSO
        }
        if (fila.claveUnicidad != null && fila.estado != EstadoCanal.A) {
            violaciones += ViolacionCanal.CLAVE_UNICIDAD_EN_ESTADO_BC
        }
        if (fila.claveUnicidad != null && !deberiaTenerClave) {
            violaciones += ViolacionCanal.CLAVE_UNICIDAD_SIN_ESTADO_A_DERIVABLE
        }
        if (deberiaTenerClave && fila.claveUnicidad == null) {
            violaciones += ViolacionCanal.ESTADO_A_DERIVABLE_SIN_CLAVE_UNICIDAD
        }
    }

    private fun validarClaveUnicidad(
        violaciones: MutableList<ViolacionCanal>,
        fila: FilaCanal,
        tvgIdUnicoEnFuente: Boolean,
    ) {
        val clave = fila.claveUnicidad ?: return
        val tvg = fila.tvgId
        val tvgUsable =
            tvgIdUnicoEnFuente &&
                tvg != null &&
                ValidadorTvgId.validar(tvg) is ResultadoTvgId.Valido
        if (tvgUsable) {
            if (clave != ClaveCanal.PREFIJO_TVG + tvg) {
                violaciones += ViolacionCanal.CLAVE_UNICIDAD_TVG_INCONSISTENTE
            }
            return
        }
        if (clave != fila.claveCanonica) {
            violaciones += ViolacionCanal.CLAVE_UNICIDAD_CANONICA_INCONSISTENTE
        }
    }
}
