package com.copernicotv.core.cache

import java.time.Duration
import java.time.Instant

/** Bytes de un mebibyte, unidad de los límites de DC-09. */
const val BYTES_POR_MIB: Long = 1024L * 1024L

/** Tipo de caché de transporte cubierta por DC-09. */
enum class TipoCache(val codigo: String) {
    M3U("m3u"),
    XMLTV("xmltv"),
}

/**
 * Clave lógica de una entrada de caché.
 *
 * La URL nunca se usa en claro: la clave incorpora su HMAC (DC-04). La
 * estructura reproduce la unicidad de `CacheM3U` y `CacheXmltv`.
 */
sealed interface ClaveCache {
    val tipo: TipoCache
    val urlHmac: String

    /** Clave M3U `(fuente_id, lista_id, "m3u", url_hmac)`. */
    data class M3U(
        val fuenteId: Long,
        val listaId: Long,
        override val urlHmac: String,
    ) : ClaveCache {
        override val tipo: TipoCache
            get() = TipoCache.M3U
    }

    /** Clave XMLTV `(fuente_guia_id, "xmltv", url_hmac)`. */
    data class Xmltv(
        val fuenteGuiaId: Long,
        override val urlHmac: String,
    ) : ClaveCache {
        override val tipo: TipoCache
            get() = TipoCache.XMLTV
    }
}

/** Propietario de una clave: la Lista en M3U y la FuenteGuia en XMLTV. */
fun propietarioDe(clave: ClaveCache): Long =
    when (clave) {
        is ClaveCache.M3U -> clave.listaId
        is ClaveCache.Xmltv -> clave.fuenteGuiaId
    }

/**
 * Frescura de una copia.
 *
 * [INVALIDA] cubre la fila sin archivo o con tamaño inconsistente; [STALE]
 * una copia vencida que todavía puede servirse marcada.
 */
enum class Frescura {
    VALIDA,
    STALE,
    INVALIDA,
}

/**
 * Presupuesto de DC-09, en bytes reales del archivo cifrado.
 *
 * Es independiente del presupuesto de la generación publicada de EPG y de la
 * ventana de retención: no se computa contra la generación publicada.
 */
data class PresupuestoCache(
    val m3uTotal: Long = 64L * BYTES_POR_MIB,
    val m3uPorLista: Long = 16L * BYTES_POR_MIB,
    val m3uPorArchivo: Long = 16L * BYTES_POR_MIB,
    val xmltvTotal: Long = 256L * BYTES_POR_MIB,
    val xmltvPorFuenteGuia: Long = 96L * BYTES_POR_MIB,
    val xmltvPorArchivo: Long = 96L * BYTES_POR_MIB,
    val global: Long = 320L * BYTES_POR_MIB,
    val reservaLibre: Long = 100L * BYTES_POR_MIB,
)

/**
 * Metadatos mínimos de una entrada existente para planificar la evicción.
 *
 * [tamanoBytes] es el tamaño real del archivo cifrado (`File.length()`), no
 * el tamaño lógico ni el número de entradas.
 */
data class EntradaCache(
    val id: Long,
    val clave: ClaveCache,
    val tamanoBytes: Long,
    val fetchedAt: Instant,
    val frescura: Frescura,
    val enUso: Boolean = false,
) {
    /** Propietario de la entrada: Lista para M3U, FuenteGuia para XMLTV. */
    val propietario: Long
        get() = propietarioDe(clave)
}

/** Resultado de planificar la escritura de una entrada de caché. */
sealed interface DecisionEscritura {
    /**
     * La escritura puede realizarse. [evictar] contiene los `id` a eliminar
     * antes de escribir, en el orden contractual (inválidas/stale primero,
     * luego `fetched_at` ascendente y `id` ascendente).
     */
    data class Almacenar(val evictar: List<Long>) : DecisionEscritura

    /**
     * El archivo excede el límite por archivo: no se almacena y la
     * importación continúa en streaming sin caché.
     */
    object OmitidaPorTamano : DecisionEscritura

    /**
     * Ni siquiera evictando todo lo evictable se cumplen los límites. No se
     * evicta nada: se conserva la copia anterior.
     */
    object SinEspacio : DecisionEscritura
}

/**
 * Política pura de presupuesto, evicción y frescura de DC-09.
 *
 * No toca disco ni Room: recibe los metadatos y el espacio libre observado,
 * y devuelve el plan determinista. La escritura atómica, el cifrado y la
 * actualización de la fila pertenecen a la capa de persistencia.
 */
object PoliticaCache {
    /** Límite por archivo del tipo indicado. */
    fun limitePorArchivo(
        tipo: TipoCache,
        presupuesto: PresupuestoCache,
    ): Long {
        return when (tipo) {
            TipoCache.M3U -> presupuesto.m3uPorArchivo
            TipoCache.XMLTV -> presupuesto.xmltvPorArchivo
        }
    }

    /** Límite total de la caché del tipo indicado. */
    fun limiteTotal(
        tipo: TipoCache,
        presupuesto: PresupuestoCache,
    ): Long {
        return when (tipo) {
            TipoCache.M3U -> presupuesto.m3uTotal
            TipoCache.XMLTV -> presupuesto.xmltvTotal
        }
    }

    /** Límite por propietario (Lista o FuenteGuia) del tipo indicado. */
    fun limitePorPropietario(
        tipo: TipoCache,
        presupuesto: PresupuestoCache,
    ): Long {
        return when (tipo) {
            TipoCache.M3U -> presupuesto.m3uPorLista
            TipoCache.XMLTV -> presupuesto.xmltvPorFuenteGuia
        }
    }

    /**
     * Planifica la escritura de [nueva] con [tamanoNuevo] bytes reales.
     *
     * [libreBytes] es el espacio libre observado antes de escribir. Una
     * entrada con la misma clave se considera reemplazada de forma atómica:
     * su tamaño deja de computar en los totales. Si esa copia está en uso, la
     * escritura falla con [DecisionEscritura.SinEspacio].
     */
    @Suppress("ReturnCount")
    fun planificarEscritura(
        nueva: ClaveCache,
        tamanoNuevo: Long,
        existentes: List<EntradaCache>,
        libreBytes: Long,
        presupuesto: PresupuestoCache = PresupuestoCache(),
    ): DecisionEscritura {
        val tipo = nueva.tipo
        if (tamanoNuevo > limitePorArchivo(tipo, presupuesto)) return DecisionEscritura.OmitidaPorTamano
        val reemplazo = existentes.firstOrNull { it.clave == nueva }
        if (reemplazo?.enUso == true) return DecisionEscritura.SinEspacio
        val limitePropietario = limitePorPropietario(tipo, presupuesto)
        if (!cabeEnAlgunEscenario(nueva, tamanoNuevo, presupuesto, limitePropietario)) {
            return DecisionEscritura.SinEspacio
        }

        val base = existentes.filter { it.clave != nueva }
        val uso = Uso.de(base, nueva, tamanoNuevo, libreBytes + (reemplazo?.tamanoBytes ?: 0L))
        val evictar = mutableListOf<Long>()
        for (candidata in candidatasDeEviccion(base)) {
            if (uso.cabe(presupuesto, limitePropietario)) break
            evictar += candidata.id
            uso.liberar(candidata)
        }
        if (!uso.cabe(presupuesto, limitePropietario)) return DecisionEscritura.SinEspacio
        return DecisionEscritura.Almacenar(evictar)
    }

    /**
     * Orden de evicción: inválidas o stale antes que válidas; dentro de cada
     * grupo, `fetched_at` ascendente y luego `id` ascendente. Las entradas en
     * uso nunca son candidatas.
     */
    fun candidatasDeEviccion(existentes: List<EntradaCache>): List<EntradaCache> {
        return existentes
            .filterNot { it.enUso }
            .sortedWith(
                compareBy<EntradaCache> { if (it.frescura == Frescura.VALIDA) 1 else 0 }
                    .thenBy { it.fetchedAt }
                    .thenBy { it.id },
            )
    }

    private fun cabeEnAlgunEscenario(
        nueva: ClaveCache,
        tamanoNuevo: Long,
        presupuesto: PresupuestoCache,
        limitePropietario: Long,
    ): Boolean {
        val porPropietario = tamanoNuevo <= limitePropietario
        val porTotal = tamanoNuevo <= limiteTotal(nueva.tipo, presupuesto)
        val porGlobal = tamanoNuevo <= presupuesto.global
        return porPropietario && porTotal && porGlobal
    }

    private class Uso(
        val tipo: TipoCache,
        val propietarioClave: Long,
        var m3u: Long,
        var xmltv: Long,
        var global: Long,
        var propietario: Long,
        var libre: Long,
    ) {
        fun cabe(
            presupuesto: PresupuestoCache,
            limitePropietario: Long,
        ): Boolean {
            val porTipo = m3u <= presupuesto.m3uTotal && xmltv <= presupuesto.xmltvTotal
            val porGlobal = global <= presupuesto.global
            val porPropietarioYDisco = propietario <= limitePropietario && libre >= presupuesto.reservaLibre
            return porTipo && porGlobal && porPropietarioYDisco
        }

        fun liberar(entrada: EntradaCache) {
            when (entrada.clave.tipo) {
                TipoCache.M3U -> m3u -= entrada.tamanoBytes
                TipoCache.XMLTV -> xmltv -= entrada.tamanoBytes
            }
            global -= entrada.tamanoBytes
            if (entrada.clave.tipo == tipo && entrada.propietario == propietarioClave) {
                propietario -= entrada.tamanoBytes
            }
            libre += entrada.tamanoBytes
        }

        companion object {
            fun de(
                existentes: List<EntradaCache>,
                nueva: ClaveCache,
                tamanoNuevo: Long,
                libreConReemplazo: Long,
            ): Uso {
                val m3u = existentes.filter { it.clave.tipo == TipoCache.M3U }.sumOf { it.tamanoBytes }
                val xmltv = existentes.filter { it.clave.tipo == TipoCache.XMLTV }.sumOf { it.tamanoBytes }
                val propietario =
                    existentes
                        .filter { it.clave.tipo == nueva.tipo && it.propietario == propietarioDe(nueva) }
                        .sumOf { it.tamanoBytes }
                return Uso(
                    tipo = nueva.tipo,
                    propietarioClave = propietarioDe(nueva),
                    m3u = m3u + if (nueva.tipo == TipoCache.M3U) tamanoNuevo else 0L,
                    xmltv = xmltv + if (nueva.tipo == TipoCache.XMLTV) tamanoNuevo else 0L,
                    global = m3u + xmltv + tamanoNuevo,
                    propietario = propietario + tamanoNuevo,
                    libre = libreConReemplazo - tamanoNuevo,
                )
            }
        }
    }
}

/**
 * Frescura contractual de una copia.
 *
 * `expires_at = fetched_at + intervalo vigente al momento de la descarga`.
 * Cambiar el intervalo después no reescribe entradas existentes: la frescura
 * se evalúa siempre contra el `expires_at` almacenado. `expires_at` indica
 * frescura y no inicia descargas por sí mismo.
 */
object FrescuraCache {
    /** Calcula `expires_at` al momento de la descarga. */
    fun expiracion(
        fetchedAt: Instant,
        intervalo: Duration,
    ): Instant {
        return fetchedAt.plus(intervalo)
    }

    /** Evalúa la frescura contra el `expires_at` almacenado. */
    fun evaluar(
        expiresAt: Instant,
        ahora: Instant,
    ): Frescura {
        return if (ahora.isBefore(expiresAt)) Frescura.VALIDA else Frescura.STALE
    }
}
