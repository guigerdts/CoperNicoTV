package com.copernicotv.core.orden

import com.copernicotv.core.identity.Nfc151
import com.copernicotv.core.identity.lowerRoot
import com.copernicotv.core.identity.nfc.BusquedaTablaNfc

/** Modo de ordenación del catálogo. `MANUAL` conserva el orden de Apariencia. */
enum class ModoOrden(val codigo: String) {
    MANUAL("manual"),
    NOMBRE("nombre"),
    GRUPO("grupo"),
    RECIENTES("recientes"),
}

/** Dirección de la ordenación. */
enum class DireccionOrden(val codigo: String) {
    ASCENDENTE("asc"),
    DESCENDENTE("desc"),
}

/** Preferencia de ordenación persistida por Lista. */
data class PreferenciaOrden(
    val modo: ModoOrden = ModoOrden.MANUAL,
    val direccion: DireccionOrden = DireccionOrden.ASCENDENTE,
)

/** Campos mínimos que la ordenación necesita de un Canal. */
interface CanalOrdenable {
    val nombre: String?
    val grupoNombre: String?
    val ultimaReproduccionEpochMs: Long?
    val aparienciaId: Long
    val ordenManual: Int
}

/**
 * Ordenación determinista del catálogo.
 *
 * La clave primaria usa NFD de Unicode 15.1.0, minúsculas con `Locale.ROOT`,
 * una tabla fija para las letras españolas y el code point para lo no
 * alfabético. `ñ` ocupa el lugar entre `n` y `o`; los acentos no cambian la
 * clave primaria y se desempatan con la clave secundaria NFD. La PK de
 * Apariencia desempata siempre de forma ascendente.
 */
object OrdenCatalogo {
    fun <T : CanalOrdenable> ordenar(
        filas: List<T>,
        modo: ModoOrden,
        direccion: DireccionOrden,
    ): List<T> =
        when (modo) {
            ModoOrden.MANUAL -> ordenarManual(filas, direccion)
            ModoOrden.NOMBRE -> ordenarNombre(filas, direccion)
            ModoOrden.GRUPO -> ordenarGrupo(filas, direccion)
            ModoOrden.RECIENTES -> ordenarRecientes(filas, direccion)
        }

    private fun <T : CanalOrdenable> ordenarManual(
        filas: List<T>,
        direccion: DireccionOrden,
    ): List<T> {
        val comparador: Comparator<T> =
            if (direccion == DireccionOrden.ASCENDENTE) {
                compareBy<T> { it.ordenManual }
            } else {
                compareByDescending<T> { it.ordenManual }
            }
        return filas.sortedWith(comparador.thenBy { it.aparienciaId })
    }

    private fun <T : CanalOrdenable> ordenarNombre(
        filas: List<T>,
        direccion: DireccionOrden,
    ): List<T> {
        val claves =
            filas.map { fila ->
                ClaveNombre(fila, clavePrimaria(fila.nombre), claveSecundaria(fila.nombre))
            }
        val comparador = Comparator<ClaveNombre<T>> { a, b -> compararNombre(a, b, direccion) }
        return claves.sortedWith(comparador).map { it.fila }
    }

    private fun <T : CanalOrdenable> ordenarGrupo(
        filas: List<T>,
        direccion: DireccionOrden,
    ): List<T> {
        val claves =
            filas.map { fila ->
                ClaveGrupo(
                    fila = fila,
                    grupoPrimaria = clavePrimaria(fila.grupoNombre),
                    grupoSecundaria = claveSecundaria(fila.grupoNombre),
                    nombrePrimaria = clavePrimaria(fila.nombre),
                    nombreSecundaria = claveSecundaria(fila.nombre),
                )
            }
        val comparador = Comparator<ClaveGrupo<T>> { a, b -> compararGrupo(a, b, direccion) }
        return claves.sortedWith(comparador).map { it.fila }
    }

    private fun <T : CanalOrdenable> ordenarRecientes(
        filas: List<T>,
        direccion: DireccionOrden,
    ): List<T> {
        val claves =
            filas.map { fila ->
                ClaveRecencia(
                    fila = fila,
                    fecha = fila.ultimaReproduccionEpochMs,
                    nombrePrimaria = clavePrimaria(fila.nombre),
                    nombreSecundaria = claveSecundaria(fila.nombre),
                )
            }
        val comparador = Comparator<ClaveRecencia<T>> { a, b -> compararRecencia(a, b, direccion) }
        return claves.sortedWith(comparador).map { it.fila }
    }

    private fun <T : CanalOrdenable> compararNombre(
        a: ClaveNombre<T>,
        b: ClaveNombre<T>,
        direccion: DireccionOrden,
    ): Int {
        if ((a.primaria == null) != (b.primaria == null)) {
            return compararClaves(a.primaria, b.primaria)
        }
        val primaria = compararClaves(a.primaria, b.primaria)
        if (primaria != 0) return aplicarDireccion(primaria, direccion)
        val secundaria = compararClaves(a.secundaria, b.secundaria)
        if (secundaria != 0) return aplicarDireccion(secundaria, direccion)
        return a.fila.aparienciaId.compareTo(b.fila.aparienciaId)
    }

    private fun <T : CanalOrdenable> compararGrupo(
        a: ClaveGrupo<T>,
        b: ClaveGrupo<T>,
        direccion: DireccionOrden,
    ): Int {
        val grupoPrimaria = compararClaves(a.grupoPrimaria, b.grupoPrimaria)
        if ((a.grupoPrimaria == null) != (b.grupoPrimaria == null)) return grupoPrimaria
        if (grupoPrimaria != 0) return aplicarDireccion(grupoPrimaria, direccion)
        val grupoSecundaria = compararClaves(a.grupoSecundaria, b.grupoSecundaria)
        if (grupoSecundaria != 0) return aplicarDireccion(grupoSecundaria, direccion)
        return compararNombreDeGrupo(a, b, direccion)
    }

    private fun <T : CanalOrdenable> compararNombreDeGrupo(
        a: ClaveGrupo<T>,
        b: ClaveGrupo<T>,
        direccion: DireccionOrden,
    ): Int {
        val nombrePrimaria = compararClaves(a.nombrePrimaria, b.nombrePrimaria)
        if ((a.nombrePrimaria == null) != (b.nombrePrimaria == null)) return nombrePrimaria
        if (nombrePrimaria != 0) return aplicarDireccion(nombrePrimaria, direccion)
        val nombreSecundaria = compararClaves(a.nombreSecundaria, b.nombreSecundaria)
        if (nombreSecundaria != 0) return aplicarDireccion(nombreSecundaria, direccion)
        return a.fila.aparienciaId.compareTo(b.fila.aparienciaId)
    }

    private fun <T : CanalOrdenable> compararRecencia(
        a: ClaveRecencia<T>,
        b: ClaveRecencia<T>,
        direccion: DireccionOrden,
    ): Int {
        val fecha = compararFechas(a.fecha, b.fecha, direccion)
        if (fecha != 0) return fecha
        val nombrePrimaria = compararClaves(a.nombrePrimaria, b.nombrePrimaria)
        if ((a.nombrePrimaria == null) != (b.nombrePrimaria == null)) return nombrePrimaria
        if (nombrePrimaria != 0) return aplicarDireccion(nombrePrimaria, direccion)
        val nombreSecundaria = compararClaves(a.nombreSecundaria, b.nombreSecundaria)
        if (nombreSecundaria != 0) return aplicarDireccion(nombreSecundaria, direccion)
        return a.fila.aparienciaId.compareTo(b.fila.aparienciaId)
    }

    private fun compararFechas(
        a: Long?,
        b: Long?,
        direccion: DireccionOrden,
    ): Int =
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> aplicarDireccion(a.compareTo(b), direccion)
        }

    private fun compararClaves(
        a: IntArray?,
        b: IntArray?,
    ): Int =
        when {
            a == null && b == null -> 0
            a == null -> 1
            b == null -> -1
            else -> compararArreglos(a, b)
        }

    private fun compararArreglos(
        a: IntArray,
        b: IntArray,
    ): Int {
        val limite = minOf(a.size, b.size)
        for (indice in 0 until limite) {
            val comparacion = a[indice].compareTo(b[indice])
            if (comparacion != 0) return comparacion
        }
        return a.size.compareTo(b.size)
    }

    private fun aplicarDireccion(
        comparacion: Int,
        direccion: DireccionOrden,
    ): Int = if (direccion == DireccionOrden.ASCENDENTE) comparacion else -comparacion

    private fun clavePrimaria(nombre: String?): IntArray? {
        if (nombre == null) return null
        val puntos = lowerRoot(Nfc151.descomponer(nombre)).codePoints().toArray()
        val claves = IntArray(puntos.size)
        var cantidad = 0
        var indice = 0
        while (indice < puntos.size) {
            val punto = puntos[indice]
            when {
                punto == PUNTO_N && indice + 1 < puntos.size && puntos[indice + 1] == PUNTO_TILDE -> {
                    claves[cantidad++] = RANGO_ENIE
                    indice += 2
                }
                BusquedaTablaNfc.ccc(punto) != 0 -> indice++
                punto in PUNTO_A..PUNTO_Z -> {
                    claves[cantidad++] = rangoLetra(punto)
                    indice++
                }
                else -> {
                    val base = LETRAS_BASE[punto]
                    claves[cantidad++] =
                        if (base != null) rangoLetra(base.code) else RANGO_NO_ALFABETICO + punto
                    indice++
                }
            }
        }
        return claves.copyOf(cantidad)
    }

    private fun claveSecundaria(nombre: String?): IntArray? {
        if (nombre == null) return null
        return lowerRoot(Nfc151.descomponer(nombre)).codePoints().toArray()
    }

    private fun rangoLetra(punto: Int): Int =
        when {
            punto == PUNTO_N -> 14
            punto > PUNTO_N -> punto - PUNTO_A + 2
            else -> punto - PUNTO_A + 1
        }

    private data class ClaveNombre<T : CanalOrdenable>(
        val fila: T,
        val primaria: IntArray?,
        val secundaria: IntArray?,
    )

    private data class ClaveGrupo<T : CanalOrdenable>(
        val fila: T,
        val grupoPrimaria: IntArray?,
        val grupoSecundaria: IntArray?,
        val nombrePrimaria: IntArray?,
        val nombreSecundaria: IntArray?,
    )

    private data class ClaveRecencia<T : CanalOrdenable>(
        val fila: T,
        val fecha: Long?,
        val nombrePrimaria: IntArray?,
        val nombreSecundaria: IntArray?,
    )

    private const val PUNTO_A = 'a'.code
    private const val PUNTO_Z = 'z'.code
    private const val PUNTO_N = 'n'.code
    private const val PUNTO_TILDE = 0x0303
    private const val RANGO_ENIE = 15
    private const val RANGO_NO_ALFABETICO = 1_000

    /**
     * Letras que no se descomponen canónicamente en una letra base + marca.
     * Se asignan a su letra española más cercana para que no queden después de
     * `z` como si fueran símbolos.
     */
    private val LETRAS_BASE: Map<Int, Char> =
        mapOf(
            // æ
            0x00E6 to 'a',
            // œ
            0x0153 to 'o',
            // ø
            0x00F8 to 'o',
            // ł
            0x0142 to 'l',
            // đ
            0x0111 to 'd',
            // ð
            0x00F0 to 'd',
            // þ
            0x00FE to 't',
            // ß
            0x00DF to 's',
            // ħ
            0x0127 to 'h',
            // ı
            0x0131 to 'i',
            // ŋ
            0x014B to 'n',
        )
}
