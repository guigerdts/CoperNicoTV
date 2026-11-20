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
 *
 * El motor está pensado para catálogos de cientos de miles de filas: las
 * búsquedas de evidencia se resuelven con índices y conjuntos, no con barridos
 * cuadráticos. Las estructuras se construyen solo cuando la fase las necesita.
 */
object Reconciliador {
    /** Máximo de filas/declaraciones de un conjunto C para probar unicidad. */
    private const val MAXIMO_CONJUNTO_C: Int = 16

    /**
     * Por debajo de este producto se prefiere el barrido directo de claves
     * canónicas (comparación barata); por encima se agrupa por clave.
     */
    private const val UMBRAL_CANONICA_DIRECTA: Long = 2_000_000

    /**
     * Por debajo de este producto se prefiere el barrido directo de evidencia.
     * Es más bajo que el de claves porque cada par normaliza dos nombres.
     */
    private const val UMBRAL_EVIDENCIA_DIRECTA: Long = 50_000

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
        val usadas = HashSet<Int>(declaraciones.size)
        val asignadas = HashMap<Long, DeclaracionNueva>(historicas.size)

        // 1. Coincidencia exacta por clave de unicidad no nula.
        val porClave = IndiceClaveUnica(declaraciones.size)
        for (declaracion in declaraciones) {
            val clave = declaracion.identidad.claveUnicidad ?: continue
            porClave.agregar(clave, declaracion)
        }
        for (fila in historicas) {
            if (fila.estado != EstadoCanal.A) continue
            val clave = fila.identidad.claveUnicidad ?: continue
            val candidata = porClave.primero(clave, usadas) ?: continue
            asignadas[fila.id] = candidata
            usadas += candidata.indice
        }

        val superadas = HashSet<Long>()
        val cierresC = LinkedHashSet<Long>()

        // 2. Conjuntos C: cierre solo con correspondencia única por evidencia.
        val conjuntosC = agruparConjuntosC(historicas, asignadas)
        for ((clave, filas) in conjuntosC) {
            val candidatas = candidatasDeConjuntoC(filas, declaraciones, usadas)
            val correspondencia = coincidenciaUnica(filas, candidatas)
            if (correspondencia != null && clavesFinalesDistintas(correspondencia)) {
                for ((filaId, declaracion) in correspondencia) {
                    asignadas[filaId] = declaracion
                    usadas += declaracion.indice
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
        val noAsignadas = declaraciones.filter { it.indice !in usadas }
        val filasA = historicas.filter { it.estado == EstadoCanal.A }
        val convergentes = linkedMapOf<Int, ColisionIdentidad>()
        if (noAsignadas.isNotEmpty() && filasA.isNotEmpty()) {
            val barridoDirecto =
                noAsignadas.size.toLong() * filasA.size.toLong() <= UMBRAL_CANONICA_DIRECTA
            val porCanonicaA = if (barridoDirecto) null else agruparPorCanonica(filasA)
            for (declaracion in noAsignadas) {
                val clave = declaracion.identidad.claveCanonica ?: continue
                val afectadas =
                    if (porCanonicaA != null) {
                        porCanonicaA[clave].orEmpty().filter { fila ->
                            fila.id !in asignadas &&
                                fila.identidad.claveUnicidad != declaracion.identidad.claveUnicidad
                        }
                    } else {
                        filasA.filter { fila ->
                            fila.id !in asignadas &&
                                fila.identidad.claveCanonica == clave &&
                                fila.identidad.claveUnicidad != declaracion.identidad.claveUnicidad
                        }
                    }
                if (afectadas.size >= 2) {
                    val ids = afectadas.map { it.id }
                    convergentes[declaracion.indice] =
                        ColisionIdentidad(clave = clave, filas = ids, declaraciones = listOf(declaracion.indice))
                    for (id in ids) superadas += id
                }
            }
        }

        // 4. Declaraciones sin fila: colisión con B/C o Canal nuevo.
        val clavesAmbiguas = HashSet<String>()
        for (fila in historicas) {
            if (fila.estado == EstadoCanal.A) continue
            fila.identidad.claveCanonica?.let(clavesAmbiguas::add)
            fila.identidad.claveUnicidad?.let(clavesAmbiguas::add)
        }
        convergentes.values.forEach { clavesAmbiguas += it.clave }
        val compartenConHistorica =
            if (noAsignadas.isEmpty()) {
                BooleanArray(0)
            } else {
                marcarCompartidas(
                    test = noAsignadas.map { it.identidad },
                    pool = historicas.map { it.identidad },
                )
            }
        val destinos = ArrayList<DeclaracionPlan>(noAsignadas.size)
        for ((posicion, declaracion) in noAsignadas.withIndex()) {
            val colisiona =
                convergentes.containsKey(declaracion.indice) ||
                    declaracion.identidad.claveCanonica?.let { it in clavesAmbiguas } == true
            destinos +=
                DeclaracionPlan(
                    indice = declaracion.indice,
                    destino =
                        if (colisiona || compartenConHistorica[posicion]) {
                            DestinoDeclaracion.OBSERVACION
                        } else {
                            DestinoDeclaracion.CANAL_NUEVO
                        },
                )
        }

        // 5. Plan por fila: identidad efectiva, estado y evidencia.
        val colisiones = convergentes.values.toMutableList()
        val filasConvergentes = HashSet<Long>()
        convergentes.values.forEach { filasConvergentes += it.filas }
        val filasPlan = ArrayList<FilaPlan>(historicas.size)
        for (fila in historicas) {
            val declaracion = asignadas[fila.id]
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
            } else if (fila.id in filasConvergentes) {
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
                    declaracionIndice = declaracion?.indice,
                    identidad = identidadFinal,
                    estado = estado,
                    evidencia = evidencia,
                    decisionSuperada = superada,
                )
        }

        // 6. Colisión por convergencia: se informa una sola vez por clave.
        val yaInformadas = HashSet<String>()
        colisiones.forEach { yaInformadas += it.clave }
        val porCanonicaB = linkedMapOf<String, MutableList<Long>>()
        for (fila in filasPlan) {
            if (fila.estado != EstadoCanal.B) continue
            val clave = fila.identidad.claveCanonica ?: continue
            porCanonicaB.getOrPut(clave) { ArrayList(1) } += fila.historicoId
        }
        for ((clave, ids) in porCanonicaB) {
            if (ids.size > 1 && yaInformadas.add(clave)) {
                colisiones += ColisionIdentidad(clave, ids, emptyList())
            }
        }

        // 7. Ausencias: filas sin ninguna evidencia compartida con lo declarado.
        val ausencias =
            if (declaraciones.isEmpty()) {
                historicas.map { it.id }
            } else {
                val compartidas =
                    marcarCompartidas(
                        test = historicas.map { it.identidad },
                        pool = declaraciones.map { it.identidad },
                    )
                val resultado = ArrayList<Long>(historicas.size)
                for (posicion in historicas.indices) {
                    if (!compartidas[posicion]) resultado += historicas[posicion].id
                }
                resultado
            }
        return ResultadoReconciliacion(
            filas = filasPlan,
            declaraciones = destinos,
            colisiones = colisiones,
            ausencias = ausencias,
            cierresC = cierresC.toList(),
        )
    }

    /**
     * Evidencia estable compartida entre una fila histórica y una declaración.
     *
     * Nunca usa la posición ni el orden. La URL se compara por HMAC, la clave
     * por su representación persistida y el nombre por su forma normalizada.
     * La relación es simétrica: intercambiar los argumentos no cambia el
     * resultado.
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
    ): Map<Long, DeclaracionNueva>? {
        if (filas.isEmpty()) return emptyMap()
        if (filas.size > MAXIMO_CONJUNTO_C || declaraciones.size > MAXIMO_CONJUNTO_C) return null
        val candidatos =
            filas.associate { fila ->
                fila.id to
                    declaraciones
                        .filter { compartenEvidencia(fila.identidad, it.identidad) }
            }
        if (candidatos.values.any { it.isEmpty() }) return null
        val bordes = candidatos.values.sumOf { it.size }
        if (bordes > MAXIMO_CONJUNTO_C * MAXIMO_CONJUNTO_C) return null
        val (conteo, primera) = contarEmparejamientos(filas.map { it.id }, candidatos, limite = 2)
        return if (conteo == 1) primera else null
    }

    private fun clavesFinalesDistintas(correspondencia: Map<Long, DeclaracionNueva>): Boolean {
        val claves = correspondencia.values.map { it.identidad.claveUnicidad }
        return claves.all { it != null } && claves.toSet().size == claves.size
    }

    private fun contarEmparejamientos(
        ids: List<Long>,
        candidatos: Map<Long, List<DeclaracionNueva>>,
        limite: Int,
    ): Pair<Int, Map<Long, DeclaracionNueva>?> {
        var conteo = 0
        var primera: Map<Long, DeclaracionNueva>? = null
        val usados = mutableSetOf<Int>()
        val actual = mutableMapOf<Long, DeclaracionNueva>()

        fun retroceder(posicion: Int): Boolean {
            if (posicion == ids.size) {
                conteo += 1
                if (primera == null) primera = actual.toMap()
                return conteo >= limite
            }
            for (declaracion in candidatos.getValue(ids[posicion])) {
                if (!usados.add(declaracion.indice)) continue
                actual[ids[posicion]] = declaracion
                val detener = retroceder(posicion + 1)
                actual.remove(ids[posicion])
                usados.remove(declaracion.indice)
                if (detener) return true
            }
            return false
        }

        retroceder(0)
        return conteo to if (conteo == 1) primera else null
    }

    /**
     * Marca qué identidades de [test] comparten evidencia con al menos una de
     * [pool]. Para catálogos grandes resuelve cada campo en una pasada con un
     * conjunto temporal; así el pico de memoria es el de un solo campo y no el
     * de cuatro índices simultáneos.
     */
    private fun marcarCompartidas(
        test: List<IdentidadReconciliacion>,
        pool: List<IdentidadReconciliacion>,
    ): BooleanArray {
        val resultado = BooleanArray(test.size)
        if (test.isEmpty() || pool.isEmpty()) return resultado
        if (test.size.toLong() * pool.size.toLong() <= UMBRAL_EVIDENCIA_DIRECTA) {
            for (posicion in test.indices) {
                val identidad = test[posicion]
                resultado[posicion] = pool.any { compartenEvidencia(identidad, it) }
            }
            return resultado
        }
        var pendientes: List<Int> = List(test.size) { it }
        if (pendientes.isNotEmpty()) {
            pendientes = marcarPorHmac(pendientes, test, pool, resultado)
        }
        if (pendientes.isNotEmpty()) {
            pendientes = marcarPorCanonica(pendientes, test, pool, resultado)
        }
        if (pendientes.isNotEmpty()) {
            pendientes = marcarPorTvgId(pendientes, test, pool, resultado)
        }
        if (pendientes.isNotEmpty()) {
            marcarPorNombre(pendientes, test, pool, resultado)
        }
        return resultado
    }

    private fun marcarPorHmac(
        pendientes: List<Int>,
        test: List<IdentidadReconciliacion>,
        pool: List<IdentidadReconciliacion>,
        resultado: BooleanArray,
    ): List<Int> {
        val valores = HashSet<String>(pool.size)
        for (identidad in pool) {
            if (identidad.urlStreamHmac.isNotEmpty()) valores.add(identidad.urlStreamHmac)
        }
        val restantes =
            filtrarPendientes(pendientes, test, resultado) { identidad ->
                identidad.urlStreamHmac.isNotEmpty() && identidad.urlStreamHmac in valores
            }
        valores.clear()
        return restantes
    }

    private fun marcarPorCanonica(
        pendientes: List<Int>,
        test: List<IdentidadReconciliacion>,
        pool: List<IdentidadReconciliacion>,
        resultado: BooleanArray,
    ): List<Int> {
        val valores = HashSet<String>()
        for (identidad in pool) identidad.claveCanonica?.let(valores::add)
        val restantes =
            filtrarPendientes(pendientes, test, resultado) { identidad ->
                identidad.claveCanonica != null && identidad.claveCanonica in valores
            }
        valores.clear()
        return restantes
    }

    private fun marcarPorTvgId(
        pendientes: List<Int>,
        test: List<IdentidadReconciliacion>,
        pool: List<IdentidadReconciliacion>,
        resultado: BooleanArray,
    ): List<Int> {
        val valores = HashSet<String>()
        for (identidad in pool) identidad.tvgId?.let(valores::add)
        val restantes =
            filtrarPendientes(pendientes, test, resultado) { identidad ->
                identidad.tvgId != null && identidad.tvgId in valores
            }
        valores.clear()
        return restantes
    }

    private fun marcarPorNombre(
        pendientes: List<Int>,
        test: List<IdentidadReconciliacion>,
        pool: List<IdentidadReconciliacion>,
        resultado: BooleanArray,
    ) {
        val valores = HashSet<String>()
        for (identidad in pool) {
            identidad.nombre?.let { valores.add(ClaveCanal.normalizarNombre(it)) }
        }
        filtrarPendientes(pendientes, test, resultado) { identidad ->
            val nombre = identidad.nombre?.let(ClaveCanal::normalizarNombre)
            nombre != null && nombre in valores
        }
        valores.clear()
    }

    private inline fun filtrarPendientes(
        pendientes: List<Int>,
        test: List<IdentidadReconciliacion>,
        resultado: BooleanArray,
        comparte: (IdentidadReconciliacion) -> Boolean,
    ): MutableList<Int> {
        val restantes = ArrayList<Int>(pendientes.size)
        for (posicion in pendientes) {
            if (comparte(test[posicion])) resultado[posicion] = true else restantes += posicion
        }
        return restantes
    }

    private fun agruparConjuntosC(
        historicas: List<FilaHistorica>,
        asignadas: Map<Long, DeclaracionNueva>,
    ): Map<String, List<FilaHistorica>> {
        val grupos = linkedMapOf<String, MutableList<FilaHistorica>>()
        for (fila in historicas) {
            if (fila.estado != EstadoCanal.C || fila.id in asignadas) continue
            val clave = fila.identidad.claveCanonica ?: "#sin-clave:${fila.id}"
            grupos.getOrPut(clave) { ArrayList(1) } += fila
        }
        return grupos
    }

    private fun candidatasDeConjuntoC(
        filas: List<FilaHistorica>,
        declaraciones: List<DeclaracionNueva>,
        usadas: Set<Int>,
    ): List<DeclaracionNueva> {
        if (usadas.size == declaraciones.size) return emptyList()
        val sonda = SondaEvidencia(filas)
        return declaraciones.filter { it.indice !in usadas && sonda.comparte(it.identidad) }
    }

    private fun agruparPorCanonica(filas: List<FilaHistorica>): Map<String, List<FilaHistorica>> {
        val grupos = HashMap<String, MutableList<FilaHistorica>>()
        for (fila in filas) {
            val clave = fila.identidad.claveCanonica ?: continue
            grupos.getOrPut(clave) { ArrayList(1) } += fila
        }
        return grupos
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

    /**
     * Índice de declaraciones por `clave_unicidad`.
     *
     * La mayoría de las claves tiene una sola declaración; solo las claves
     * repetidas pagan una lista y un cursor. Así la fase 1 no asigna 147k
     * listas de un elemento ni vuelve a recorrer una lista ya consumida.
     */
    private class IndiceClaveUnica(capacidad: Int) {
        private val unicas = HashMap<String, DeclaracionNueva>(capacidad)
        private val multiples = HashMap<String, MutableList<DeclaracionNueva>>()
        private val cursores = HashMap<String, Int>()

        fun agregar(clave: String, declaracion: DeclaracionNueva) {
            val existente = unicas[clave]
            if (existente == null) {
                unicas[clave] = declaracion
            } else {
                multiples.getOrPut(clave) { mutableListOf(existente) } += declaracion
            }
        }

        fun primero(clave: String, usadas: Set<Int>): DeclaracionNueva? {
            val lista = multiples[clave]
            if (lista != null) {
                var posicion = cursores[clave] ?: 0
                while (posicion < lista.size) {
                    val candidata = lista[posicion]
                    posicion += 1
                    if (candidata.indice !in usadas) {
                        cursores[clave] = posicion
                        return candidata
                    }
                }
                cursores[clave] = posicion
                return null
            }
            return unicas[clave]?.takeIf { it.indice !in usadas }
        }
    }

    /**
     * Sonda de evidencia de un conjunto C (como máximo unas pocas filas).
     * Evita normalizar nombres por cada declaración del catálogo.
     */
    private class SondaEvidencia(filas: List<FilaHistorica>) {
        private val hmacs = HashSet<String>()
        private val canonicas = HashSet<String>()
        private val tvgIds = HashSet<String>()
        private val nombres = HashSet<String>()

        init {
            for (fila in filas) {
                val identidad = fila.identidad
                if (identidad.urlStreamHmac.isNotEmpty()) hmacs.add(identidad.urlStreamHmac)
                identidad.claveCanonica?.let(canonicas::add)
                identidad.tvgId?.let(tvgIds::add)
                identidad.nombre?.let { nombres.add(ClaveCanal.normalizarNombre(it)) }
            }
        }

        fun comparte(nueva: IdentidadReconciliacion): Boolean {
            if (nueva.urlStreamHmac.isNotEmpty() && nueva.urlStreamHmac in hmacs) return true
            if (nueva.claveCanonica != null && nueva.claveCanonica in canonicas) return true
            if (nueva.tvgId != null && nueva.tvgId in tvgIds) return true
            val nombre = nueva.nombre?.let(ClaveCanal::normalizarNombre)
            return nombre != null && nombre in nombres
        }
    }
}
