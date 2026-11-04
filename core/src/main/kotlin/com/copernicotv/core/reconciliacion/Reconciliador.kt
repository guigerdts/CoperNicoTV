package com.copernicotv.core.reconciliacion

import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.EstadoCanal

/**
 * Instantánea de la identidad de una fila `Canal` o de una declaración nueva.
 *
 * `urlStreamHmac` es el sobre HMAC persistido (`hmac:vN:cvM:...`), no la URL:
 * la reconciliación compara identidad, nunca texto en claro.
 */
data class IdentidadReconciliacion(
    val nombre: String?,
    val tvgId: String?,
    val claveCanonica: String?,
    val claveUnicidad: String?,
    val claveDerivable: Boolean,
    val urlStreamHmac: String,
)

/** Fila histórica de una Fuente tal como entra a la reconciliación. */
data class FilaHistorica(
    val id: Long,
    val identidad: IdentidadReconciliacion,
    val estado: EstadoCanal,
    val evidencia: String? = null,
    val decision: String? = null,
)

/** Declaración nueva de una importación, ya agrupada por identidad intra-importación. */
data class DeclaracionNueva(
    val indice: Int,
    val identidad: IdentidadReconciliacion,
)

/** Destino de una declaración que no corresponde a una fila histórica. */
enum class DestinoDeclaracion {
    /** La declaración se persiste como Canal nuevo. */
    CANAL_NUEVO,

    /**
     * La declaración colisiona con un conjunto B/C o con una convergencia: no
     * se asigna a ninguna fila y queda como Observación pendiente (ADR-0004:8).
     */
    OBSERVACION,
}

/** Plan de escritura para una fila histórica. */
data class FilaPlan(
    val historicoId: Long,
    val declaracionIndice: Int?,
    val identidad: IdentidadReconciliacion,
    val estado: EstadoCanal,
    val evidencia: String?,
    val decisionSuperada: Boolean,
)

/** Plan de escritura para una declaración sin fila histórica. */
data class DeclaracionPlan(
    val indice: Int,
    val destino: DestinoDeclaracion,
)

/**
 * Colisión de identidad detectada en la Actualización.
 *
 * `clave` es la clave que colisiona (canónica o de unicidad). `filas` conserva
 * las PK internas implicadas y `declaraciones` los índices de las declaraciones
 * que no se asignan implícitamente.
 */
data class ColisionIdentidad(
    val clave: String,
    val filas: List<Long>,
    val declaraciones: List<Int>,
)

/** Resultado completo y determinista de reconciliar una Fuente. */
data class ResultadoReconciliacion(
    val filas: List<FilaPlan>,
    val declaraciones: List<DeclaracionPlan>,
    val colisiones: List<ColisionIdentidad>,
    val ausencias: List<Long>,
    val cierresC: List<Long>,
) {
    /** `true` si la Actualización deja o mantiene un conjunto B/C. */
    val hayAmbiguedad: Boolean
        get() = colisiones.isNotEmpty() || filas.any { it.estado != EstadoCanal.A }
}

/**
 * Reconciliación entre versiones de una Lista (E4-T1/T2, ADR-0004).
 *
 * El motor es puro: no lee ni escribe la base. La capa de persistencia ejecuta
 * el plan en una única transacción y es la responsable del orden A→B (liberar
 * todas las `clave_unicidad` antes de escribir la evidencia).
 *
 * Reglas aplicadas:
 * - Coincidencia exacta por `clave_unicidad` no nula: misma identidad lógica.
 * - Los conjuntos C se cierran solo con correspondencia única por evidencia y
 *   claves finales distintas y no nulas; en caso contrario se conservan o
 *   vuelven a B si la colisión persiste.
 * - Una declaración sin fila que comparte la clave canónica con dos o más
 *   filas A las hace converger a B; nunca se fusiona nada automáticamente.
 * - Una declaración que colisiona con un conjunto B/C queda como Observación.
 * - La ausencia no borra: toda fila sin declaración se conserva y se informa.
 */
object Reconciliador {
    /** Máximo de filas/declaraciones de un conjunto C para probar unicidad. */
    private const val MAXIMO_CONJUNTO_C: Int = 16

    /*
     * El algoritmo es una secuencia de fases numeradas sobre estado compartido
     * (asignaciones, conjuntos C, convergencias). Extraer cada fase a una
     * función obligaría a pasar y devolver ese estado mutable en cada paso, lo
     * que oscurece el orden contractual de ADR-0004 en lugar de aclararlo.
     */
    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun reconciliar(
        historicas: List<FilaHistorica>,
        declaraciones: List<DeclaracionNueva>,
    ): ResultadoReconciliacion {
        val usadas = mutableSetOf<Int>()
        val asignadas = linkedMapOf<Long, Int>()

        // 1. Coincidencia exacta por clave de unicidad no nula.
        val porClave =
            declaraciones
                .groupBy { it.identidad.claveUnicidad }
                .filterKeys { it != null }
        for (fila in historicas.filter { it.estado == EstadoCanal.A }) {
            val clave = fila.identidad.claveUnicidad ?: continue
            val candidata = porClave[clave]?.firstOrNull { it.indice !in usadas }
            if (candidata != null) {
                asignadas[fila.id] = candidata.indice
                usadas += candidata.indice
            }
        }

        val superadas = mutableSetOf<Long>()
        val cierresC = mutableListOf<Long>()

        // 2. Conjuntos C: cierre solo con correspondencia única por evidencia.
        val conjuntosC =
            historicas
                .filter { it.estado == EstadoCanal.C && it.id !in asignadas }
                .groupBy { it.identidad.claveCanonica ?: "#sin-clave:${it.id}" }
        for ((clave, filas) in conjuntosC) {
            val candidatas =
                declaraciones.filter { it.indice !in usadas && filas.any { f -> compartenEvidencia(f.identidad, it.identidad) } }
            val correspondencia = coincidenciaUnica(filas, candidatas)
            if (correspondencia != null && clavesFinalesDistintas(correspondencia, declaraciones)) {
                for ((filaId, indice) in correspondencia) {
                    asignadas[filaId] = indice
                    usadas += indice
                    superadas += filaId
                    cierresC += filaId
                }
                continue
            }
            // La colisión persiste: C→B si la clave del conjunto reaparece.
            val reaparece =
                clave.startsWith("#sin-clave:").not() &&
                    declaraciones.any { it.indice !in usadas && it.identidad.claveCanonica == clave }
            if (reaparece) {
                for (fila in filas) superadas += fila.id
            }
        }

        // 3. Convergencia: una declaración sin fila comparte clave canónica con
        //    dos o más filas A no asignadas. Nunca se fusiona automáticamente.
        val filasA = historicas.filter { it.estado == EstadoCanal.A }
        val convergentes = linkedMapOf<Int, ColisionIdentidad>()
        for (declaracion in declaraciones.filter { it.indice !in usadas }) {
            val clave = declaracion.identidad.claveCanonica ?: continue
            val afectadas =
                filasA.filter { fila ->
                    fila.id !in asignadas &&
                        fila.identidad.claveCanonica == clave &&
                        fila.identidad.claveUnicidad != declaracion.identidad.claveUnicidad
                }
            if (afectadas.size >= 2) {
                val ids = afectadas.map { it.id }
                val colision = ColisionIdentidad(clave = clave, filas = ids, declaraciones = listOf(declaracion.indice))
                convergentes[declaracion.indice] = colision
                for (id in ids) superadas += id
            }
        }

        // 4. Declaraciones sin fila: colisión con B/C o Canal nuevo.
        val clavesAmbiguas = mutableSetOf<String>()
        historicas
            .filter { it.estado != EstadoCanal.A }
            .forEach { fila ->
                fila.identidad.claveCanonica?.let(clavesAmbiguas::add)
                fila.identidad.claveUnicidad?.let(clavesAmbiguas::add)
            }
        convergentes.values.forEach { clavesAmbiguas += it.clave }
        val destinos = mutableListOf<DeclaracionPlan>()
        for (declaracion in declaraciones) {
            if (declaracion.indice in usadas) continue
            val colisiona =
                convergentes.containsKey(declaracion.indice) ||
                    declaracion.identidad.claveCanonica?.let { it in clavesAmbiguas } == true
            // Una declaración que comparte evidencia con una fila histórica pero
            // no se pudo asignar (correspondencia no única) tampoco crea un Canal
            // nuevo: queda como Observación para resolución explícita.
            val comparteConHistorica =
                historicas.any { compartenEvidencia(it.identidad, declaracion.identidad) }
            destinos +=
                DeclaracionPlan(
                    indice = declaracion.indice,
                    destino =
                        if (colisiona || comparteConHistorica) {
                            DestinoDeclaracion.OBSERVACION
                        } else {
                            DestinoDeclaracion.CANAL_NUEVO
                        },
                )
        }

        // 5. Plan por fila: identidad efectiva, estado y evidencia.
        val colisiones = convergentes.values.toMutableList()
        val filasPlan = mutableListOf<FilaPlan>()
        for (fila in historicas) {
            val indice = asignadas[fila.id]
            val declaracion = indice?.let { i -> declaraciones.first { it.indice == i } }
            val identidad = declaracion?.identidad ?: fila.identidad
            var estado = fila.estado
            var evidencia = fila.evidencia
            var superada = fila.id in superadas

            if (fila.id in cierresC) {
                estado = EstadoCanal.A
                // La colisión que mantenía el conjunto C quedó resuelta: la
                // evidencia vieja ya no describe el estado actual.
                evidencia = null
            } else if (fila.estado == EstadoCanal.C && superada) {
                estado = EstadoCanal.B
                evidencia = evidenciaColision(identidad.claveCanonica ?: identidad.claveUnicidad, listOf(fila.id))
            } else if (convergentes.values.any { fila.id in it.filas }) {
                estado = EstadoCanal.B
                evidencia = evidenciaColision(identidad.claveCanonica, listOf(fila.id))
                superada = true
            } else if (fila.estado == EstadoCanal.B) {
                estado = EstadoCanal.B
            }

            val identidadFinal =
                if (estado == EstadoCanal.B || estado == EstadoCanal.C) {
                    identidad.copy(claveUnicidad = null)
                } else {
                    identidad
                }
            filasPlan +=
                FilaPlan(
                    historicoId = fila.id,
                    declaracionIndice = indice,
                    identidad = identidadFinal,
                    estado = estado,
                    evidencia = evidencia,
                    decisionSuperada = superada,
                )
        }

        // 6. Colisión por convergencia: se informa una sola vez por clave.
        val yaInformadas = colisiones.map { it.clave }.toMutableSet()
        for (fila in filasPlan.filter { it.estado == EstadoCanal.B }) {
            val clave = fila.identidad.claveCanonica ?: continue
            if (yaInformadas.add(clave)) {
                val ids =
                    filasPlan
                        .filter { it.estado == EstadoCanal.B && it.identidad.claveCanonica == clave }
                        .map { it.historicoId }
                if (ids.size > 1) {
                    colisiones += ColisionIdentidad(clave, ids, emptyList())
                }
            }
        }

        val ausencias =
            historicas
                .filter { fila -> declaraciones.none { compartenEvidencia(fila.identidad, it.identidad) } }
                .map { it.id }
        return ResultadoReconciliacion(
            filas = filasPlan,
            declaraciones = destinos,
            colisiones = colisiones,
            ausencias = ausencias,
            cierresC = cierresC,
        )
    }

    /**
     * Evidencia estable compartida entre una fila histórica y una declaración.
     *
     * Nunca usa la posición ni el orden. La URL se compara por HMAC, la clave
     * por su representación persistida y el nombre por su forma normalizada.
     */
    internal fun compartenEvidencia(
        historica: IdentidadReconciliacion,
        nueva: IdentidadReconciliacion,
    ): Boolean {
        if (historica.urlStreamHmac.isNotEmpty() && historica.urlStreamHmac == nueva.urlStreamHmac) return true
        if (historica.claveCanonica != null && historica.claveCanonica == nueva.claveCanonica) return true
        if (historica.tvgId != null && historica.tvgId == nueva.tvgId) return true
        val nombreHistorico = historica.nombre?.let(ClaveCanal::normalizarNombre)
        val nombreNuevo = nueva.nombre?.let(ClaveCanal::normalizarNombre)
        return nombreHistorico != null && nombreHistorico == nombreNuevo
    }

    /**
     * Correspondencia única que satura todas las filas de [filas].
     *
     * Devuelve `null` si alguna fila no tiene candidata, si hay más de una
     * asignación posible o si el conjunto excede el límite seguro. Un fallo de
     * unicidad nunca se resuelve por orden ni por conteo.
     */
    internal fun coincidenciaUnica(
        filas: List<FilaHistorica>,
        declaraciones: List<DeclaracionNueva>,
    ): Map<Long, Int>? {
        if (filas.isEmpty()) return emptyMap()
        if (filas.size > MAXIMO_CONJUNTO_C || declaraciones.size > MAXIMO_CONJUNTO_C) return null
        val candidatos =
            filas.associate { fila ->
                fila.id to
                    declaraciones
                        .filter { compartenEvidencia(fila.identidad, it.identidad) }
                        .map { it.indice }
            }
        if (candidatos.values.any { it.isEmpty() }) return null
        val bordes = candidatos.values.sumOf { it.size }
        if (bordes > MAXIMO_CONJUNTO_C * MAXIMO_CONJUNTO_C) return null
        val (conteo, primera) = contarEmparejamientos(filas.map { it.id }, candidatos, limite = 2)
        return if (conteo == 1) primera else null
    }

    private fun clavesFinalesDistintas(
        correspondencia: Map<Long, Int>,
        declaraciones: List<DeclaracionNueva>,
    ): Boolean {
        val claves = correspondencia.values.map { indice -> declaraciones.first { it.indice == indice }.identidad.claveUnicidad }
        return claves.all { it != null } && claves.toSet().size == claves.size
    }

    private fun contarEmparejamientos(
        ids: List<Long>,
        candidatos: Map<Long, List<Int>>,
        limite: Int,
    ): Pair<Int, Map<Long, Int>?> {
        var conteo = 0
        var primera: Map<Long, Int>? = null
        val usados = mutableSetOf<Int>()
        val actual = mutableMapOf<Long, Int>()

        fun retroceder(posicion: Int): Boolean {
            if (posicion == ids.size) {
                conteo += 1
                if (primera == null) primera = actual.toMap()
                return conteo >= limite
            }
            for (indice in candidatos.getValue(ids[posicion])) {
                if (!usados.add(indice)) continue
                actual[ids[posicion]] = indice
                val detener = retroceder(posicion + 1)
                actual.remove(ids[posicion])
                usados.remove(indice)
                if (detener) return true
            }
            return false
        }

        retroceder(0)
        return conteo to if (conteo == 1) primera else null
    }

    /** Formato estable de evidencia de colisión, sin datos sensibles. */
    fun evidenciaColision(
        clave: String?,
        filas: List<Long>,
    ): String =
        buildString {
            append("reconciliacion:v1;motivo=colision_canonica")
            clave?.let { append(";clave=").append(it) }
            append(";filas=").append(filas.joinToString(","))
        }
}
