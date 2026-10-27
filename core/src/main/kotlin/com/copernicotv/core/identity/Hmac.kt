package com.copernicotv.core.identity

import java.util.Base64

/** Motivo tipado de un sobre HMAC inválido (DC-04, L31). */
enum class MotivoSobre(val codigo: String) {
    FORMATO_INVALIDO("hmac_formato_invalido"),
    BASE64_NO_CANONICO("hmac_base64_no_canonico"),
    LONGITUD_INVALIDA("hmac_longitud_invalida"),
    VERSION_FUERA_DE_RANGO("hmac_version_fuera_de_rango"),
}

/** Resultado de interpretar un sobre `hmac:vN:cvM:<base64>`. */
sealed interface ResultadoSobre {
    data class Versionado(
        val generacion: Int,
        val versionCanonica: Int,
        val base64: String,
        val mac: ByteArray,
    ) : ResultadoSobre

    data class Legacy(
        val generacion: Int,
        val base64: String,
        val mac: ByteArray,
    ) : ResultadoSobre

    data class Invalido(val motivo: MotivoSobre) : ResultadoSobre
}

/**
 * Sobre HMAC versionado (DC-04, L31).
 *
 * `hmac:vN:cvM:<base64>` donde `vN` es la generación de la clave HMAC y `cvM`
 * la versión de canonicalización. El sobre legacy `hmac:vN:<base64>` no declara
 * `cvM` y nunca se asume `cv1`.
 */
object SobreHmac {
    private val VERSIONADO = Regex("^hmac:v([1-9][0-9]*):cv([1-9][0-9]*):([A-Za-z0-9+/]+={0,2})$")
    private val LEGACY = Regex("^hmac:v([1-9][0-9]*):([A-Za-z0-9+/]+={0,2})$")
    private const val LONGITUD_MAC = 32
    private const val MAXIMO_VERSION_CANONICA = 255

    /** Construye el sobre de un HMAC-SHA-256. */
    fun construir(
        generacion: Int,
        versionCanonica: Int,
        mac: ByteArray,
    ): String {
        require(generacion >= 1) { "generacion HMAC inválida" }
        require(versionCanonica in 1..MAXIMO_VERSION_CANONICA) { "cvM fuera de rango" }
        require(mac.size == LONGITUD_MAC) { "el HMAC debe tener 32 bytes" }
        val codificado = Base64.getEncoder().encodeToString(mac)
        return "hmac:v$generacion:cv$versionCanonica:$codificado"
    }

    /** Interpreta un sobre persistido. */
    fun parsear(texto: String): ResultadoSobre {
        VERSIONADO.matchEntire(texto)?.let { coincidencia ->
            return interpretarVersionado(coincidencia.groupValues)
        }
        LEGACY.matchEntire(texto)?.let { coincidencia ->
            return interpretarLegacy(coincidencia.groupValues)
        }
        return ResultadoSobre.Invalido(MotivoSobre.FORMATO_INVALIDO)
    }

    private fun interpretarVersionado(grupos: List<String>): ResultadoSobre {
        val generacion =
            grupos[1].toIntOrNull()
                ?: return ResultadoSobre.Invalido(MotivoSobre.FORMATO_INVALIDO)
        val version =
            grupos[2].toIntOrNull()
                ?: return ResultadoSobre.Invalido(MotivoSobre.FORMATO_INVALIDO)
        if (version !in 1..MAXIMO_VERSION_CANONICA) {
            return ResultadoSobre.Invalido(MotivoSobre.VERSION_FUERA_DE_RANGO)
        }
        val base64 = grupos[3]
        val mac =
            decodificarCanonico(base64)
                ?: return ResultadoSobre.Invalido(MotivoSobre.BASE64_NO_CANONICO)
        if (mac.size != LONGITUD_MAC) {
            return ResultadoSobre.Invalido(MotivoSobre.LONGITUD_INVALIDA)
        }
        return ResultadoSobre.Versionado(generacion, version, base64, mac)
    }

    private fun interpretarLegacy(grupos: List<String>): ResultadoSobre {
        val generacion =
            grupos[1].toIntOrNull()
                ?: return ResultadoSobre.Invalido(MotivoSobre.FORMATO_INVALIDO)
        val base64 = grupos[2]
        val mac =
            decodificarCanonico(base64)
                ?: return ResultadoSobre.Invalido(MotivoSobre.BASE64_NO_CANONICO)
        if (mac.size != LONGITUD_MAC) {
            return ResultadoSobre.Invalido(MotivoSobre.LONGITUD_INVALIDA)
        }
        return ResultadoSobre.Legacy(generacion, base64, mac)
    }

    private fun decodificarCanonico(base64: String): ByteArray? {
        val decodificado =
            try {
                Base64.getDecoder().decode(base64)
            } catch (_: IllegalArgumentException) {
                return null
            }
        val recodificado = Base64.getEncoder().encodeToString(decodificado)
        return if (recodificado == base64) decodificado else null
    }
}

/** Serialización de la entrada del HMAC de URL, versión de serialización `0x02`. */
object EntradaHmac {
    const val DOMINIO: String = "copernicotv/url/hmac"
    const val VERSION_SERIALIZACION: Byte = 0x02
    const val MAXIMO_VERSION_CANONICA: Int = 255

    /**
     * `DOMINIO || 0x00 || 0x02 || cv_id || campo_id || clase || uint32_be(len) || utf8(url)`.
     *
     * @throws IllegalArgumentException si la versión de canonicalización o la
     *   clase no son admitidas para el campo.
     */
    fun serializar(
        versionCanonica: Int,
        campo: CampoUrl,
        clase: ClaseUrl,
        urlCanonica: String,
    ): ByteArray {
        require(versionCanonica in 1..MAXIMO_VERSION_CANONICA) { "cvM fuera de rango" }
        require(clase in campo.clasesAdmitidas) { "clase no admitida para el campo" }
        return concatenar(
            listOf(
                utf8(DOMINIO),
                byteArrayOf(0x00),
                byteArrayOf(VERSION_SERIALIZACION),
                byteArrayOf(versionCanonica.toByte()),
                byteArrayOf(campo.id),
                byteArrayOf(clase.codigo),
                uint32Be(longitudUtf8(urlCanonica)),
                utf8(urlCanonica),
            ),
        )
    }
}

/** Utilidades de redacción para logs y diagnósticos (DC-04). */
object Redaccion {
    private const val LONGITUD_PREFIJO = 8

    /**
     * Prefijo de 8 caracteres ASCII de la porción Base64 de un sobre, o `null`
     * si el sobre no es interpretable. No expone la URL ni el HMAC completo.
     */
    fun prefijoHmac(sobre: String): String? =
        when (val resultado = SobreHmac.parsear(sobre)) {
            is ResultadoSobre.Versionado -> resultado.base64.take(LONGITUD_PREFIJO)
            is ResultadoSobre.Legacy -> resultado.base64.take(LONGITUD_PREFIJO)
            is ResultadoSobre.Invalido -> null
        }

    /** Texto de diagnóstico que nunca incluye la URL ni sus componentes. */
    fun motivoRechazo(
        campo: CampoUrl,
        error: ErrorUrl,
    ): String = "campo=${campo.name} codigo=${error.codigo.codigo}"
}
