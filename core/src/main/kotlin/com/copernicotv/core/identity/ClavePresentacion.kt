package com.copernicotv.core.identity

/**
 * Clave de presentación de una Apariencia, `presentacion:v2` (DC-05).
 *
 * Cada campo se canonicaliza con `NFC → recorte exterior White_Space → NFC`, sin
 * casefold, NFKC ni colapso de espacios interiores. `NULL` produce `N`, la
 * cadena vacía o solo `White_Space` produce `E`, y el resto `T` con longitud
 * UTF-8 big-endian.
 */
object ClavePresentacion {
    const val DOMINIO: String = "copernicotv/apariencia/clave_presentacion"
    const val PREFIJO: String = "presentacion:v2:"
    const val VERSION: Byte = 0x02

    /** Calcula `clave_presentacion` a partir de `group_title` y `tvg_name`. */
    fun clave(
        groupTitle: String?,
        tvgName: String?,
    ): String {
        val entrada =
            concatenar(
                listOf(
                    utf8(DOMINIO),
                    byteArrayOf(0x00),
                    byteArrayOf(VERSION),
                    campo(0x01, groupTitle),
                    campo(0x02, tvgName),
                ),
            )
        return PREFIJO + hexMinuscula(sha256(entrada))
    }

    private fun campo(
        id: Byte,
        valor: String?,
    ): ByteArray {
        if (valor == null) {
            return byteArrayOf(id, EtiquetaCampo.N.codigo)
        }
        val texto = canonicalizar(valor)
        if (texto.isEmpty()) {
            return byteArrayOf(id, EtiquetaCampo.E.codigo)
        }
        return concatenar(
            listOf(
                byteArrayOf(id, EtiquetaCampo.T.codigo),
                uint32Be(longitudUtf8(texto)),
                utf8(texto),
            ),
        )
    }

    private fun canonicalizar(valor: String): String {
        validarSurrogates(valor)
        val primera = nfc(valor)
        return nfc(trimExtUnicode(primera))
    }
}
