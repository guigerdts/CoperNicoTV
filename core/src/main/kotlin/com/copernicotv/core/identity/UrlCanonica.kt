package com.copernicotv.core.identity

import java.util.Locale

/** Clase del contrato HMAC (DC-04). */
enum class ClaseUrl(val codigo: Byte) {
    HTTP_HTTPS(0x01),
    SAF(0x02),
    RTSP(0x03),
    RTMP(0x04),
}

/** Campos con columna de URL en el esquema v1 (DC-04). */
enum class CampoUrl(val id: Byte, val clasesAdmitidas: Set<ClaseUrl>) {
    LISTA_UBICACION(0x01, setOf(ClaseUrl.HTTP_HTTPS, ClaseUrl.SAF)),
    CANAL_URL_STREAM(0x02, setOf(ClaseUrl.HTTP_HTTPS, ClaseUrl.RTSP, ClaseUrl.RTMP)),
    CANAL_LOGO_URL(0x03, setOf(ClaseUrl.HTTP_HTTPS)),
    OBSERVACION_URL(0x04, setOf(ClaseUrl.HTTP_HTTPS, ClaseUrl.RTSP, ClaseUrl.RTMP)),
    OBSERVACION_LOGO_URL(0x05, setOf(ClaseUrl.HTTP_HTTPS)),
    CACHE_M3U_URL(0x06, setOf(ClaseUrl.HTTP_HTTPS)),
    CACHE_XMLTV_URL(0x07, setOf(ClaseUrl.HTTP_HTTPS)),
    FUENTE_GUIA_URL(0x08, setOf(ClaseUrl.HTTP_HTTPS)),
}

/** Códigos tipados de rechazo de URL (DC-04/DP-03). */
enum class CodigoErrorUrl(val codigo: String) {
    URL_AUSENTE("url_ausente"),
    URL_MALFORMADA("url_malformada"),
    URL_ESQUEMA_NO_ADMITIDO("url_esquema_no_admitido"),
    URL_AUTORIDAD_INVALIDA("url_autoridad_invalida"),
    URL_PERCENT_ENCODING_INVALIDO("url_percent_encoding_invalido"),
    URL_NO_CANONICALIZABLE("url_no_canonicalizable"),
}

/**
 * Error de canonicalización sin datos sensibles.
 *
 * Nunca contiene la URL completa, `userinfo`, host, path, query, fragmento,
 * credenciales ni tokens. Solo el código tipado y, cuando aplica, el esquema
 * rechazado (DC-04, DP-03).
 */
data class ErrorUrl(
    val codigo: CodigoErrorUrl,
    val esquema: String? = null,
) {
    override fun toString(): String =
        if (esquema == null) {
            "ErrorUrl(${codigo.codigo})"
        } else {
            "ErrorUrl(${codigo.codigo}, esquema=$esquema)"
        }
}

/** Resultado de canonicalizar una URL. */
sealed interface ResultadoCanonizacion {
    data class Ok(
        val urlCanonica: String,
        val clase: ClaseUrl,
    ) : ResultadoCanonizacion

    data class Rechazada(val error: ErrorUrl) : ResultadoCanonizacion
}

/**
 * Canonicalización de URL, versión `cv1` (DC-04, L31).
 *
 * Reglas aplicadas por esquema:
 * - HTTP/HTTPS: esquema y host en minúsculas con [Locale.ROOT], puerto
 *   predeterminado eliminado, path vacío como `/`, `%HH` en mayúscula y solo
 *   se decodifican caracteres no reservados, query y fragmento conservados.
 * - RTSP/RTMP: igual que HTTP pero el path vacío se conserva vacío y el puerto
 *   predeterminado es `554`/`1935`.
 * - SAF `content://`: solo se normaliza el esquema; el resto se conserva byte
 *   a byte.
 */
object CanonicalizadorUrl {
    private val ESQUEMA = Regex("^[A-Za-z][A-Za-z0-9+.-]*$")
    private val CARACTERES_PROHIBIDOS = Regex("[\\u0000-\\u0020\\u007F]")

    /**
     * Canoniza [original] para el [campo] indicado.
     *
     * @return [ResultadoCanonizacion.Ok] con la URL canónica y su clase, o
     *   [ResultadoCanonizacion.Rechazada] con un [ErrorUrl] tipado.
     */
    fun canonizar(
        original: String?,
        campo: CampoUrl,
    ): ResultadoCanonizacion {
        if (original == null || original.isBlank()) {
            return ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_AUSENTE))
        }
        if (CARACTERES_PROHIBIDOS.containsMatchIn(original)) {
            return ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_MALFORMADA))
        }
        val separador = original.indexOf(':')
        if (separador <= 0) {
            return ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_MALFORMADA))
        }
        val esquemaBruto = original.substring(0, separador)
        if (!ESQUEMA.matches(esquemaBruto)) {
            return ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_MALFORMADA))
        }
        val esquema = esquemaBruto.lowercase(Locale.ROOT)
        val resto = original.substring(separador + 1)
        return when (esquema) {
            "http" -> httpOGenerico(resto, esquema, campo, principal = true)
            "https" -> httpOGenerico(resto, esquema, campo, principal = true)
            "rtsp" -> httpOGenerico(resto, esquema, campo, principal = false)
            "rtmp" -> httpOGenerico(resto, esquema, campo, principal = false)
            "content" -> saf(resto, campo)
            else ->
                ResultadoCanonizacion.Rechazada(
                    ErrorUrl(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, esquema),
                )
        }
    }

    private fun claseDe(esquema: String): ClaseUrl =
        when (esquema) {
            "http", "https" -> ClaseUrl.HTTP_HTTPS
            "rtsp" -> ClaseUrl.RTSP
            "rtmp" -> ClaseUrl.RTMP
            else -> ClaseUrl.SAF
        }

    private fun puertoPredeterminado(esquema: String): Int =
        when (esquema) {
            "http" -> 80
            "https" -> 443
            "rtsp" -> 554
            "rtmp" -> 1935
            else -> -1
        }

    private fun httpOGenerico(
        resto: String,
        esquema: String,
        campo: CampoUrl,
        principal: Boolean,
    ): ResultadoCanonizacion {
        val clase = claseDe(esquema)
        if (clase !in campo.clasesAdmitidas) {
            return ResultadoCanonizacion.Rechazada(
                ErrorUrl(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, esquema),
            )
        }
        if (!resto.startsWith("//")) {
            return ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_MALFORMADA))
        }
        return when (val parseo = parsearComponentes(resto.substring(2))) {
            is ResultadoComponentes.Ok -> serializar(esquema, clase, parseo.componentes, principal)
            is ResultadoComponentes.AutoridadInvalida ->
                ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_AUTORIDAD_INVALIDA))
            is ResultadoComponentes.Malformada ->
                ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_MALFORMADA))
        }
    }

    private fun saf(
        resto: String,
        campo: CampoUrl,
    ): ResultadoCanonizacion {
        if (ClaseUrl.SAF !in campo.clasesAdmitidas) {
            return ResultadoCanonizacion.Rechazada(
                ErrorUrl(CodigoErrorUrl.URL_ESQUEMA_NO_ADMITIDO, "content"),
            )
        }
        if (!resto.startsWith("//")) {
            return ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_MALFORMADA))
        }
        val trasAutoridad = resto.substring(2)
        val finAutoridad = trasAutoridad.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val autoridad = if (finAutoridad < 0) trasAutoridad else trasAutoridad.substring(0, finAutoridad)
        if (autoridad.isEmpty()) {
            return ResultadoCanonizacion.Rechazada(ErrorUrl(CodigoErrorUrl.URL_AUTORIDAD_INVALIDA))
        }
        return ResultadoCanonizacion.Ok("content:$resto", ClaseUrl.SAF)
    }

    private data class Componentes(
        val userinfo: String?,
        val host: String,
        val puerto: Int?,
        val path: String,
        val query: String?,
        val fragmento: String?,
    )

    private sealed interface ResultadoComponentes {
        data class Ok(val componentes: Componentes) : ResultadoComponentes

        data object AutoridadInvalida : ResultadoComponentes

        data object Malformada : ResultadoComponentes
    }

    private data class PartesHost(val host: String, val puertoTexto: String?)

    private sealed interface ResultadoPuerto {
        data object Ausente : ResultadoPuerto

        data class Ok(val numero: Int) : ResultadoPuerto

        data object Invalido : ResultadoPuerto
    }

    private fun parsearComponentes(texto: String): ResultadoComponentes {
        val finAutoridad = texto.indexOfFirst { it == '/' || it == '?' || it == '#' }
        val autoridad = if (finAutoridad < 0) texto else texto.substring(0, finAutoridad)
        val restante = if (finAutoridad < 0) "" else texto.substring(finAutoridad)
        if (autoridad.isEmpty()) return ResultadoComponentes.AutoridadInvalida

        val arroba = autoridad.lastIndexOf('@')
        val userinfo = if (arroba >= 0) autoridad.substring(0, arroba) else null
        val hostPuerto = if (arroba >= 0) autoridad.substring(arroba + 1) else autoridad

        val partes = dividirHostPuerto(hostPuerto) ?: return ResultadoComponentes.Malformada
        if (partes.host.isEmpty()) return ResultadoComponentes.AutoridadInvalida
        if (!hostValido(partes.host)) return ResultadoComponentes.Malformada

        val resultadoPuerto = parsearPuerto(partes.puertoTexto)
        if (resultadoPuerto is ResultadoPuerto.Invalido) {
            return ResultadoComponentes.Malformada
        }
        val puerto = (resultadoPuerto as? ResultadoPuerto.Ok)?.numero

        val (path, query, fragmento) = separarPathQueryFragmento(restante)
        return ResultadoComponentes.Ok(Componentes(userinfo, partes.host, puerto, path, query, fragmento))
    }

    private fun dividirHostPuerto(hostPuerto: String): PartesHost? {
        if (!hostPuerto.startsWith("[")) {
            val dosPuntos = hostPuerto.indexOf(':')
            return if (dosPuntos < 0) {
                PartesHost(hostPuerto, null)
            } else {
                PartesHost(hostPuerto.substring(0, dosPuntos), hostPuerto.substring(dosPuntos + 1))
            }
        }
        val cierre = hostPuerto.indexOf(']')
        if (cierre < 0) return null
        val host = hostPuerto.substring(0, cierre + 1)
        val despues = hostPuerto.substring(cierre + 1)
        return when {
            despues.isEmpty() -> PartesHost(host, null)
            despues.startsWith(":") -> PartesHost(host, despues.substring(1))
            else -> null
        }
    }

    private fun hostValido(host: String): Boolean {
        if (!host.contains('[') && !host.contains(']')) return true
        if (!(host.startsWith("[") && host.endsWith("]"))) return false
        return host.length > 2
    }

    private fun parsearPuerto(texto: String?): ResultadoPuerto {
        if (texto == null) return ResultadoPuerto.Ausente
        if (texto.isEmpty() || !texto.all { it in '0'..'9' }) return ResultadoPuerto.Invalido
        val numero = texto.toIntOrNull() ?: return ResultadoPuerto.Invalido
        if (numero !in 0..65535) return ResultadoPuerto.Invalido
        return ResultadoPuerto.Ok(numero)
    }

    private fun separarPathQueryFragmento(restante: String): Triple<String, String?, String?> {
        var path = restante
        var query: String? = null
        var fragmento: String? = null

        val numeral = path.indexOf('#')
        if (numeral >= 0) {
            fragmento = path.substring(numeral + 1)
            path = path.substring(0, numeral)
        }
        val interrogacion = path.indexOf('?')
        if (interrogacion >= 0) {
            query = path.substring(interrogacion + 1)
            path = path.substring(0, interrogacion)
        }
        return Triple(path, query, fragmento)
    }

    private data class ComponentesNormalizados(
        val path: String,
        val query: String?,
        val fragmento: String?,
    )

    private sealed interface ResultadoNormalizacion {
        data class Ok(val componentes: ComponentesNormalizados) : ResultadoNormalizacion

        data object PercentInvalido : ResultadoNormalizacion
    }

    private fun normalizarComponentes(
        componentes: Componentes,
        principal: Boolean,
    ): ResultadoNormalizacion {
        val path = normalizarPercent(componentes.path) ?: return ResultadoNormalizacion.PercentInvalido
        val query =
            componentes.query?.let {
                normalizarPercent(it) ?: return ResultadoNormalizacion.PercentInvalido
            }
        val fragmento =
            componentes.fragmento?.let {
                normalizarPercent(it) ?: return ResultadoNormalizacion.PercentInvalido
            }
        val pathFinal = if (principal && path.isEmpty()) "/" else path
        return ResultadoNormalizacion.Ok(ComponentesNormalizados(pathFinal, query, fragmento))
    }

    private fun serializar(
        esquema: String,
        clase: ClaseUrl,
        componentes: Componentes,
        principal: Boolean,
    ): ResultadoCanonizacion {
        val normalizados =
            when (val resultado = normalizarComponentes(componentes, principal)) {
                is ResultadoNormalizacion.Ok -> resultado.componentes
                is ResultadoNormalizacion.PercentInvalido ->
                    return ResultadoCanonizacion.Rechazada(
                        ErrorUrl(CodigoErrorUrl.URL_PERCENT_ENCODING_INVALIDO),
                    )
            }
        val puertoFinal =
            if (componentes.puerto != null && componentes.puerto != puertoPredeterminado(esquema)) {
                componentes.puerto.toString()
            } else {
                null
            }

        val constructor = StringBuilder()
        constructor.append(esquema).append("://")
        componentes.userinfo?.let { constructor.append(it).append('@') }
        constructor.append(componentes.host.lowercase(Locale.ROOT))
        puertoFinal?.let { constructor.append(':').append(it) }
        constructor.append(normalizados.path)
        normalizados.query?.let { constructor.append('?').append(it) }
        normalizados.fragmento?.let { constructor.append('#').append(it) }
        return ResultadoCanonizacion.Ok(constructor.toString(), clase)
    }

    private fun valorUnreserved(byte: Int): Boolean =
        when (byte) {
            in 'A'.code..'Z'.code, in 'a'.code..'z'.code, in '0'.code..'9'.code -> true
            '-'.code, '.'.code, '_'.code, '~'.code -> true
            else -> false
        }

    /**
     * Normaliza percent-encoding: `%HH` en mayúscula y decodificación solo de
     * caracteres no reservados. Devuelve `null` si un `%` no va seguido de dos
     * dígitos hexadecimales.
     */
    private fun normalizarPercent(valor: String): String? {
        val constructor = StringBuilder(valor.length)
        var indice = 0
        while (indice < valor.length) {
            val actual = valor[indice]
            if (actual != '%') {
                constructor.append(actual)
                indice += 1
                continue
            }
            if (indice + 2 >= valor.length) return null
            val alto = digitoHex(valor[indice + 1]) ?: return null
            val bajo = digitoHex(valor[indice + 2]) ?: return null
            val byte = (alto shl 4) or bajo
            if (valorUnreserved(byte)) {
                constructor.append(byte.toChar())
            } else {
                constructor.append('%')
                constructor.append(CHARS_HEX[alto])
                constructor.append(CHARS_HEX[bajo])
            }
            indice += 3
        }
        return constructor.toString()
    }

    private val CHARS_HEX = "0123456789ABCDEF".toCharArray()

    private fun digitoHex(caracter: Char): Int? =
        when (caracter) {
            in '0'..'9' -> caracter - '0'
            in 'a'..'f' -> caracter - 'a' + 10
            in 'A'..'F' -> caracter - 'A' + 10
            else -> null
        }
}
