package com.copernicotv.core.identity

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

/**
 * NFC según Unicode 15.1.0, la versión fijada por [VERSION_UNICODE_NORMATIVA].
 *
 * Delega en [Nfc151], que usa tablas generadas desde el UCD 15.1.0 y viaja con
 * el binario. No usa `java.text.Normalizer` ni hereda la versión Unicode de la
 * plataforma: en JDK 17 la plataforma implementa Unicode 13.0 y trata las
 * marcas asignadas después como starters (ccc 0), de modo que la entrada
 * `0061 10EFD 0301` queda igual, mientras 15.1 la compone en `00E1 10EFD`.
 *
 * Esta función es el único punto de normalización canónica de las rutas de
 * identidad: [ClaveCanal], [ClavePresentacion] y [PerfilNormalizacion] la usan
 * y no deben llamar a `java.text.Normalizer` por su cuenta.
 */
fun nfc(valor: String): String = Nfc151.normalizar(valor)

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

/**
 * Estrategia de case folding completo, inyectada por el consumidor.
 *
 * La implementación conforme a [VERSION_UNICODE_NORMATIVA] es
 * [CaseFolderCompleto]. La inyección se mantiene obligatoria para que ningún
 * consumidor use por accidente `String.lowercase` o la versión del dispositivo.
 */
fun interface CaseFolder {
    /** Case folding completo sin locale (estados C y F de Unicode). */
    fun fold(valor: String): String
}

/**
 * Normalización canónica de `Perfil.nombre_normalizado` (DC-02).
 *
 * `NFC(casefold_completo(NFC(trim_ext_unicode(NFC(nombre)))))`.
 *
 * El case folding completo debe provenir de [VERSION_UNICODE_NORMATIVA]; la
 * implementación conforme es [CaseFolderCompleto] (tabla generada desde
 * `CaseFolding.txt` 15.1.0, sin depender de la plataforma). Esta clase no elige
 * una implementación por defecto: la inyección es obligatoria para que ningún
 * consumidor herede el case folding del dispositivo. Sin un [CaseFolder]
 * conforme no se persiste ningún nombre normalizado, en lugar de persistir un
 * valor de una versión distinta.
 *
 * Los pasos NFC usan [nfc], conforme a Unicode 15.1.0 vía [Nfc151]; el case
 * folding completo usa [CaseFolderCompleto]. Ninguno de los dos depende de la
 * versión Unicode de la plataforma.
 */
object PerfilNormalizacion {
    /** Se lanza cuando no hay [CaseFolder] conforme disponible. */
    class CaseFoldNoDisponibleException :
        IllegalStateException(
            "case folding Unicode $VERSION_UNICODE_NORMATIVA no disponible: " +
                "inyecte CaseFolderCompleto",
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
