package com.copernicotv.core.identity

import java.util.Locale

/** Etiqueta de campo de las serializaciones de identidad. */
enum class EtiquetaCampo(val codigo: Byte) {
    N(0x00),
    E(0x01),
    T(0x02),
}

/** Estados de identidad A/B/C de ADR-0004. */
enum class EstadoCanal { A, B, C }

/** Resultado de derivar la identidad de una declaración (DC-01). */
data class IdentidadDerivada(
    val nombreNormalizado: String?,
    val tvgIdPersistido: String?,
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val claveDerivable: Boolean,
)

/**
 * Derivación de la clave de Canal, `canon:v2` (DC-01, ADR-0004).
 *
 * `normalizar_nombre = NFC(lower_root(trim_ext_unicode(NFC(nombre))))` en ese
 * orden exacto. No se colapsan espacios interiores, no se eliminan acentos y
 * no se aplica NFKC ni plegado de confusables.
 */
object ClaveCanal {
    const val DOMINIO: String = "copernicotv/canal/clave_canonica"
    const val PREFIJO: String = "canon:v2:"
    const val VERSION: Byte = 0x02
    const val PREFIJO_TVG: String = "tvg:v1:"

    /** Etiqueta de un nombre: `N` si es `NULL`, `E` si queda vacío tras recorte, `T` si no. */
    fun etiqueta(nombre: String?): EtiquetaCampo =
        when {
            nombre == null -> EtiquetaCampo.N
            trimExtUnicode(nombre).isEmpty() -> EtiquetaCampo.E
            else -> EtiquetaCampo.T
        }

    /** Nombre canónico normalizado. */
    fun normalizarNombre(nombre: String): String {
        validarSurrogates(nombre)
        val primera = nfc(nombre)
        val recortado = trimExtUnicode(primera)
        val minusculas = recortado.lowercase(Locale.ROOT)
        return nfc(minusculas)
    }

    /**
     * `clave_canonica` de un nombre y una URL canónica.
     *
     * Devuelve `null` si el nombre es `NULL`, vacío o solo `White_Space`: en
     * esos casos no se emite clave y la fila no deriva por nombre (DC-01,
     * C-03/H-03).
     */
    fun canonica(
        nombre: String?,
        urlCanonica: String,
    ): String? {
        if (nombre == null) return null
        if (trimExtUnicode(nombre).isEmpty()) return null
        val normalizado = normalizarNombre(nombre)
        val entrada = entradaCanonica(normalizado, urlCanonica)
        return PREFIJO + hexMinuscula(sha256(entrada))
    }

    /**
     * Deriva la identidad completa de una declaración.
     *
     * @param tvgIdUnicoEnFuente `true` solo si el `tvg-id` es válido y único en
     *   la Fuente. Un duplicado se trata como no único y no habilita clave.
     */
    fun derivar(
        nombre: String?,
        urlCanonica: String,
        tvgId: String?,
        tvgIdUnicoEnFuente: Boolean,
        estado: EstadoCanal = EstadoCanal.A,
    ): IdentidadDerivada {
        val validado = ValidadorTvgId.validar(tvgId)
        val persistido = (validado as? ResultadoTvgId.Valido)?.valor
        val tvgUsable = persistido != null && tvgIdUnicoEnFuente
        val canonica = canonica(nombre, urlCanonica)
        val derivable = tvgUsable || canonica != null
        val unicidad =
            if (estado == EstadoCanal.A && derivable) {
                if (tvgUsable) PREFIJO_TVG + persistido else canonica
            } else {
                null
            }
        return IdentidadDerivada(
            nombreNormalizado = nombre?.let { normalizarNombre(it) },
            tvgIdPersistido = persistido,
            claveCanonica = canonica,
            claveUnicidad = unicidad,
            claveDerivable = derivable,
        )
    }

    internal fun entradaCanonica(
        nombreNormalizado: String,
        urlCanonica: String,
    ): ByteArray =
        concatenar(
            listOf(
                utf8(DOMINIO),
                byteArrayOf(0x00),
                byteArrayOf(VERSION),
                campoTexto(0x01, EtiquetaCampo.T, nombreNormalizado),
                campoTexto(0x02, EtiquetaCampo.T, urlCanonica),
            ),
        )

    internal fun campoTexto(
        id: Byte,
        etiqueta: EtiquetaCampo,
        valor: String,
    ): ByteArray =
        when (etiqueta) {
            EtiquetaCampo.N, EtiquetaCampo.E -> byteArrayOf(id, etiqueta.codigo)
            EtiquetaCampo.T ->
                concatenar(
                    listOf(
                        byteArrayOf(id, etiqueta.codigo),
                        uint32Be(longitudUtf8(valor)),
                        utf8(valor),
                    ),
                )
        }
}
