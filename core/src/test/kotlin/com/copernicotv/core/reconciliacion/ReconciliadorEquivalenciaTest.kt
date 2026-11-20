package com.copernicotv.core.reconciliacion

import com.copernicotv.core.identity.ClaveCanal
import com.copernicotv.core.identity.EstadoCanal
import kotlin.random.Random
import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Equivalencia diferencial entre el motor indexado y el algoritmo anterior.
 *
 * El legado se conserva aquí como oráculo: cualquier cambio de resultado
 * (asignaciones, estados, evidencia, colisiones, ausencias u orden de los
 * cierres C) aparece como una diferencia estructural en cientos de catálogos
 * aleatorios. No se usa en producción.
 */
class ReconciliadorEquivalenciaTest {
    @Test
    fun `el motor indexado reproduce al legado en catalogos aleatorios`() {
        val nombres = listOf("Canal", "canal", "Otro", null)
        val tvgIds = listOf("uno", "dos", null)
        val canonicas = listOf("canon:a", "canon:b", "canon:c", null)
        val unicidades = listOf("tvg:uno", "tvg:dos", "tvg:tres", null)
        val hmacs = listOf("hmac:a", "hmac:b", "", "")
        for (semilla in 0 until 400) {
            val random = Random(semilla.toLong())
            val historicas =
                List(random.nextInt(0, 24)) { posicion ->
                    FilaHistorica(
                        id = posicion.toLong() + 1,
                        identidad =
                            IdentidadReconciliacion(
                                nombre = nombres.random(random),
                                tvgId = tvgIds.random(random),
                                claveCanonica = canonicas.random(random),
                                claveUnicidad = unicidades.random(random),
                                claveDerivable = random.nextBoolean(),
                                urlStreamHmac = hmacs.random(random),
                            ),
                        estado = EstadoCanal.entries.random(random),
                        evidencia = if (random.nextBoolean()) "evidencia:$posicion" else null,
                        decision = if (random.nextBoolean()) "decision:$posicion" else null,
                    )
                }
            val declaraciones =
                List(random.nextInt(0, 24)) { posicion ->
                    DeclaracionNueva(
                        indice = posicion,
                        identidad =
                            IdentidadReconciliacion(
                                nombre = nombres.random(random),
                                tvgId = tvgIds.random(random),
                                claveCanonica = canonicas.random(random),
                                claveUnicidad = unicidades.random(random),
                                claveDerivable = random.nextBoolean(),
                                urlStreamHmac = hmacs.random(random),
                            ),
                    )
                }

            val esperado = ReconciliadorLegado.reconciliar(historicas, declaraciones)
            val obtenido = Reconciliador.reconciliar(historicas, declaraciones)

            assertEquals(esperado, obtenido, "diferencia con semilla=$semilla")
        }
    }
}

/**
 * Copia fiel del motor anterior (pre-P0). Se mantiene solo como oráculo de
 * equivalencia; cualquier optimización futura debe seguir pasando esta prueba.
 */
private object ReconciliadorLegado {
    private const val MAXIMO_CONJUNTO_C: Int = 16

    @Suppress("LongMethod", "CyclomaticComplexMethod")
    fun reconciliar(
        historicas: List<FilaHistorica>,
        declaraciones: List<DeclaracionNueva>,
    ): ResultadoReconciliacion {
        val usadas = mutableSetOf<Int>()
        val asignadas = linkedMapOf<Long, Int>()

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
            val reaparece =
                clave.startsWith("#sin-clave:").not() &&
                    declaraciones.any { it.indice !in usadas && it.identidad.claveCanonica == clave }
            if (reaparece) {
                for (fila in filas) superadas += fila.id
            }
        }

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

    private fun compartenEvidencia(
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

    private fun coincidenciaUnica(
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

    private fun evidenciaColision(
        clave: String?,
        filas: List<Long>,
    ): String =
        buildString {
            append("reconciliacion:v1;motivo=colision_canonica")
            clave?.let { append(";clave=").append(it) }
            append(";filas=").append(filas.joinToString(","))
        }
}
