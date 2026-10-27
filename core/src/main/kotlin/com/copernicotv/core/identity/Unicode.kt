package com.copernicotv.core.identity

import java.text.Normalizer
import java.util.Locale

/**
 * Versión Unicode normativa fijada por U0 para la identidad de CoperNicoTV.
 *
 * La implementación debe usar exactamente esta versión y nunca heredar la del
 * dispositivo. La incorporación de una tabla o dependencia que la provea
 * requiere autorización previa (DC-01, C-01/H-02).
 */
const val VERSION_UNICODE_NORMATIVA: String = "15.1.0"

/**
 * Caracteres con la propiedad Unicode `White_Space` (Unicode 15.1).
 *
 * El conjunto es estable desde Unicode 6.3 y no incluye `U+FEFF`. Se usa un
 * conjunto explícito para no depender de `Character.isWhitespace`, que además
 * incluye separadores que no son `White_Space`.
 */
private val ESPACIOS_UNICODE: Set<Char> =
    buildSet {
        for (c in 0x0009..0x000D) add(c.toChar())
        add('\u0020')
        add('\u0085')
        add('\u00A0')
        add('\u1680')
        for (c in 0x2000..0x200A) add(c.toChar())
        add('\u2028')
        add('\u2029')
        add('\u202F')
        add('\u205F')
        add('\u3000')
    }

/** Indica si un carácter pertenece a la propiedad Unicode `White_Space`. */
fun esEspacioUnicode(caracter: Char): Boolean = caracter in ESPACIOS_UNICODE

/** Recorta únicamente caracteres `White_Space` en los extremos. */
fun trimExtUnicode(valor: String): String {
    var inicio = 0
    var fin = valor.length
    while (inicio < fin && esEspacioUnicode(valor[inicio])) inicio++
    while (fin > inicio && esEspacioUnicode(valor[fin - 1])) fin--
    return valor.substring(inicio, fin)
}

/** NFC según [Normalizer]. */
fun nfc(valor: String): String = Normalizer.normalize(valor, Normalizer.Form.NFC)

/** Minúsculas con [Locale.ROOT]. */
fun lowerRoot(valor: String): String = valor.lowercase(Locale.ROOT)

/** Se lanza cuando una entrada contiene surrogates no emparejados. */
class SurrogateNoEmparejadoException(valor: String? = null) :
    IllegalArgumentException("entrada con surrogates no emparejados")

/** Rechaza surrogates no emparejados antes de cualquier codificación UTF-8. */
fun validarSurrogates(valor: String) {
    var indice = 0
    while (indice < valor.length) {
        val actual = valor[indice]
        when {
            Character.isHighSurrogate(actual) -> {
                val siguiente = if (indice + 1 < valor.length) valor[indice + 1] else null
                if (siguiente == null || !Character.isLowSurrogate(siguiente)) {
                    throw SurrogateNoEmparejadoException()
                }
                indice += 2
            }
            Character.isLowSurrogate(actual) -> throw SurrogateNoEmparejadoException()
            else -> indice += 1
        }
    }
}

/** Estrategia de case folding completo, inyectada por el consumidor. */
fun interface CaseFolder {
    /** Case folding completo sin locale (estados C y F de Unicode). */
    fun fold(valor: String): String
}

/**
 * Normalización canónica de `Perfil.nombre_normalizado` (DC-02).
 *
 * `NFC(casefold_completo(NFC(trim_ext_unicode(NFC(nombre)))))`.
 *
 * El case folding completo debe provenir de [VERSION_UNICODE_NORMATIVA]. Esta
 * clase no incluye una implementación por defecto: la tabla o dependencia que
 * la provee requiere autorización previa (DC-01, C-01/H-02). Sin un
 * [CaseFolder] conforme no se persiste ningún nombre normalizado, en lugar de
 * persistir un valor de una versión distinta.
 */
object PerfilNormalizacion {
    /** Se lanza cuando no hay [CaseFolder] conforme disponible. */
    class CaseFoldNoDisponibleException :
        IllegalStateException(
            "case folding Unicode $VERSION_UNICODE_NORMATIVA no disponible: " +
                "la tabla o dependencia requiere autorización previa",
        )

    /**
     * Normaliza un nombre de Perfil con el [caseFolder] recibido.
     *
     * @throws SurrogateNoEmparejadoException si el nombre contiene surrogates no emparejados.
     * @throws CaseFoldNoDisponibleException nunca: el folder es obligatorio.
     */
    fun normalizar(
        nombre: String,
        caseFolder: CaseFolder,
    ): String {
        validarSurrogates(nombre)
        val recortado = nfc(nombre)
        val sinEspacios = trimExtUnicode(recortado)
        val plegado = caseFolder.fold(sinEspacios)
        validarSurrogates(plegado)
        return nfc(plegado)
    }

    /** Indica si la plataforma puede producir la versión normativa sin ayuda externa. */
    fun disponibleEnPlataforma(): Boolean = UnicodePlataforma.soporta151()
}

/**
 * Detección de la versión Unicode efectiva de la plataforma.
 *
 * No se declara una versión: se comprueba si la plataforma conoce code points
 * asignados por Unicode 15.0 y 15.1. Un resultado `false` significa que la
 * plataforma no puede sostener el contrato sin una tabla o dependencia.
 */
object UnicodePlataforma {
    private const val CODE_POINT_15_0 = 0x1E030 // MODIFIER LETTER CYRILLIC SMALL A
    private const val CODE_POINT_15_1 = 0x2EBF0 // CJK UNIFIED IDEOGRAPH-2EBF0

    /** `true` solo si la plataforma conoce code points de 15.0 y de 15.1. */
    fun soporta151(): Boolean = Character.isDefined(CODE_POINT_15_0) && Character.isDefined(CODE_POINT_15_1)
}
