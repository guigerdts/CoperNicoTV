package com.copernicotv.core.epg

import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import com.copernicotv.core.identity.concatenar
import com.copernicotv.core.identity.esEspacioUnicode
import com.copernicotv.core.identity.hexMinuscula
import com.copernicotv.core.identity.hmacSha256
import com.copernicotv.core.identity.longitudUtf8
import com.copernicotv.core.identity.lowerRoot
import com.copernicotv.core.identity.nfc
import com.copernicotv.core.identity.sha256
import com.copernicotv.core.identity.trimExtUnicode
import com.copernicotv.core.identity.uint32Be
import com.copernicotv.core.identity.utf8
import com.copernicotv.core.identity.validarSurrogates
import javax.crypto.SecretKey

/** Resultado de calcular la huella `huella:v2` de DC-06. */
data class ResultadoHuella(
    /** `huella:v2:<hex>` o `null` si no hay señal suficiente o falta la clave HMAC. */
    val huella: String?,
    val senales: SenalesFuertes,
    val iconosExcluidos: Int,
    /** `true` si hay iconos pero no hay clave HMAC: no se calcula ni compara huella. */
    val requiereClaveHmac: Boolean,
)

/**
 * Huella de Asociación `huella:v2` (DC-06).
 *
 * Reglas:
 * - `normalizar_epg(s) = NFC(lower_root(NFC(colapsar_ws(trim_ext_unicode(NFC(s))))))`.
 * - Nombres e iconos se tratan como conjuntos: se deduplican y ordenan por
 *   code point, nunca por orden de aparición.
 * - El icono se canonicaliza con las reglas HTTP/HTTPS de DC-04 y la huella
 *   elimina query y fragmento; el path se conserva.
 * - `H_icono = lowercase_hex(HMAC-SHA-256(clave_hmac, DOMINIO_ICONO || 0x00 ||
 *   0x01 || uint32_be(len(icono)) || utf8(icono)))`.
 * - `ENTRADA = DOMINIO_HUELLA || 0x00 || 0x02 || CAMPO(0x01, nombres) ||
 *   CAMPO(0x02, iconos)` con `CAMPO = id(1) || 0x02 || uint32_be(len) ||
 *   contenido` (la etiqueta `T` del contrato DC-06 usa el valor `0x02`).
 * - Con iconos presentes y sin clave HMAC no se calcula huella nueva.
 */
object HuellaAsociacion {
    const val VERSION: Int = 2
    const val PREFIJO: String = "huella:v2:"
    const val DOMINIO_HUELLA: String = "copernicotv/epg/huella_asociacion"
    const val DOMINIO_ICONO: String = "copernicotv/epg/icono"

    private const val ID_NOMBRES: Byte = 0x01
    private const val ID_ICONOS: Byte = 0x02
    private const val TIPO_TEXTO: Byte = 0x02 // etiqueta 'T' del contrato DC-06
    private const val VERSION_SERIALIZACION: Byte = 0x02

    /** Orden lexicográfico por code point, estable para conjuntos. */
    internal val ORDEN_CODEPOINT: Comparator<String> =
        Comparator { a, b ->
            var i = 0
            var j = 0
            while (i < a.length && j < b.length) {
                val codePointA = a.codePointAt(i)
                val codePointB = b.codePointAt(j)
                if (codePointA != codePointB) return@Comparator codePointA.compareTo(codePointB)
                i += Character.charCount(codePointA)
                j += Character.charCount(codePointB)
            }
            (a.length - i).compareTo(b.length - j)
        }

    /**
     * Calcula la huella y las señales fuertes persistibles.
     *
     * @param claveHmac clave de Keystore o `null` si no está disponible.
     */
    fun calcular(
        nombres: List<String>,
        iconos: List<String>,
        claveHmac: SecretKey?,
    ): ResultadoHuella {
        val nombresNormalizados =
            nombres
                .map(::normalizarEpg)
                .filter { it.isNotEmpty() }
                .distinct()
                .sortedWith(ORDEN_CODEPOINT)

        val iconosCanonicos = iconos.mapNotNull(::canonicalizarIcono)
        val iconosExcluidos = iconos.size - iconosCanonicos.size
        val iconosUnicos = iconosCanonicos.distinct().sortedWith(ORDEN_CODEPOINT)
        val requiereClave = iconosUnicos.isNotEmpty() && claveHmac == null

        val iconosHex =
            if (claveHmac == null) {
                emptyList()
            } else {
                iconosUnicos
                    .map { hexMinuscula(hmacSha256(claveHmac, entradaIcono(it))) }
                    .sortedWith(ORDEN_CODEPOINT)
            }

        val senales = SenalesFuertes(nombres = nombresNormalizados, iconos = iconosHex)
        if (requiereClave || (nombresNormalizados.isEmpty() && iconosHex.isEmpty())) {
            return ResultadoHuella(
                huella = null,
                senales = senales,
                iconosExcluidos = iconosExcluidos,
                requiereClaveHmac = requiereClave,
            )
        }

        val digest = sha256(serializarEntrada(nombresNormalizados, iconosHex))
        return ResultadoHuella(
            huella = PREFIJO + hexMinuscula(digest),
            senales = senales,
            iconosExcluidos = iconosExcluidos,
            requiereClaveHmac = false,
        )
    }

    /**
     * Recalcula la huella a partir de señales ya persistidas.
     *
     * Las señales guardan los HMAC de icono, no las URLs: la reconfirmación
     * manual no necesita volver a leer el documento XMLTV ni la clave HMAC.
     */
    fun calcularDesdeSenales(senales: SenalesFuertes): String? {
        if (senales.nombres.isEmpty() && senales.iconos.isEmpty()) return null
        val nombres = senales.nombres.distinct().sortedWith(ORDEN_CODEPOINT)
        val iconos = senales.iconos.distinct().sorted()
        return PREFIJO + hexMinuscula(sha256(serializarEntrada(nombres, iconos)))
    }

    /** Variante con clave en bytes, útil para pruebas y para JVM sin Keystore. */
    fun calcular(
        nombres: List<String>,
        iconos: List<String>,
        claveHmac: ByteArray?,
    ): ResultadoHuella {
        val clave =
            claveHmac?.let {
                object : SecretKey {
                    private val material = it.copyOf()

                    override fun getAlgorithm(): String = "HmacSHA256"

                    override fun getFormat(): String = "RAW"

                    override fun getEncoded(): ByteArray = material.copyOf()
                }
            }
        return calcular(nombres, iconos, clave)
    }

    /** `NFC(lower_root(NFC(colapsar_ws(trim_ext_unicode(NFC(s))))))`. */
    fun normalizarEpg(valor: String): String {
        validarSurrogates(valor)
        val paso1 = nfc(valor)
        val paso2 = trimExtUnicode(paso1)
        val paso3 = nfc(colapsarEspacios(paso2))
        val paso4 = lowerRoot(paso3)
        return nfc(paso4)
    }

    /**
     * Canonicaliza un icono para la huella.
     *
     * Usa DC-04 para esquema, host, puerto, path y percent-encoding; luego
     * elimina query y fragmento. Devuelve `null` si el icono no es una URL
     * HTTP/HTTPS válida.
     */
    fun canonicalizarIcono(icono: String): String? {
        val canonica =
            when (val resultado = CanonicalizadorUrl.canonizar(icono, CampoUrl.CANAL_LOGO_URL)) {
                is ResultadoCanonizacion.Ok -> resultado.urlCanonica
                is ResultadoCanonizacion.Rechazada -> return null
            }
        return canonica.substringBefore('#').substringBefore('?')
    }

    /** Entrada exacta del HMAC de icono. */
    internal fun entradaIcono(iconoCanonico: String): ByteArray =
        concatenar(
            listOf(
                utf8(DOMINIO_ICONO),
                byteArrayOf(0x00),
                byteArrayOf(0x01),
                uint32Be(longitudUtf8(iconoCanonico)),
                utf8(iconoCanonico),
            ),
        )

    /** Entrada exacta de la huella. */
    internal fun serializarEntrada(
        nombres: List<String>,
        iconosHex: List<String>,
    ): ByteArray =
        concatenar(
            listOf(
                utf8(DOMINIO_HUELLA),
                byteArrayOf(0x00),
                byteArrayOf(VERSION_SERIALIZACION),
                campo(ID_NOMBRES, nombres),
                campo(ID_ICONOS, iconosHex),
            ),
        )

    private fun campo(
        id: Byte,
        valores: List<String>,
    ): ByteArray {
        val contenido =
            concatenar(
                listOf(uint32Be(valores.size)) +
                    valores.flatMap { listOf(uint32Be(longitudUtf8(it)), utf8(it)) },
            )
        return concatenar(
            listOf(
                byteArrayOf(id),
                byteArrayOf(TIPO_TEXTO),
                uint32Be(contenido.size),
                contenido,
            ),
        )
    }

    private fun colapsarEspacios(valor: String): String {
        val constructor = StringBuilder(valor.length)
        var enEspacio = false
        for (caracter in valor) {
            if (esEspacioUnicode(caracter)) {
                if (!enEspacio) {
                    constructor.append(' ')
                    enEspacio = true
                }
            } else {
                constructor.append(caracter)
                enEspacio = false
            }
        }
        return constructor.toString()
    }
}
