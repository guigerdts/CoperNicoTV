package com.copernicotv.core.m3u

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import java.io.ByteArrayInputStream
import java.io.File
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Medición P1 de E2-T5 conforme a DC-10.
 *
 * Solo se ejecuta con `-Dp1.medir=true`, que activa la tarea
 * `:core:p1Performance` en el runner de referencia. El resto de las corridas de
 * `:core:test` la marca como omitida para no mezclar verificación funcional con
 * medición.
 *
 * La medición cubre la métrica de parseo de 10 000 entradas (p95 ≤ 1200 ms y
 * pico de heap ≤ 256 MiB bajo `-Xmx512m`). Las métricas de primera página de
 * búsqueda y consulta paginada dependen de la consulta de E3 y no se miden aquí.
 */
class MedicionP1Test {
    @BeforeEach
    fun exigirModoMedicion() {
        assumeTrue(
            System.getProperty("p1.medir") == "true",
            "medición P1 desactivada; usar la tarea :core:p1Performance",
        )
    }

    @Test
    fun `mide el parseo de 10 000 entradas dentro de los limites de DC-10`() {
        val bytes = FixtureP1.bytes()
        assertEquals(FixtureP1.SHA256, FixtureP1.sha256(bytes))

        val tiempos = medirTiempos(bytes)
        val picoHeapMiB = medirPicoHeap(bytes)
        val p95 = percentil95(tiempos)
        val mediana = mediana(tiempos)
        val maximo = tiempos.max()

        val cumple = p95 <= LIMITE_PARSEO_MS && picoHeapMiB <= LIMITE_HEAP_MIB
        escribirEvidencia(tiempos, mediana, p95, maximo, picoHeapMiB, cumple)

        assertTrue(
            p95 <= LIMITE_PARSEO_MS,
            "p95 de parseo ${formatear(p95)} ms excede ${formatear(LIMITE_PARSEO_MS)} ms",
        )
        assertTrue(
            picoHeapMiB <= LIMITE_HEAP_MIB,
            "pico de heap ${formatear(picoHeapMiB)} MiB excede ${formatear(LIMITE_HEAP_MIB)} MiB",
        )
    }

    private fun medirTiempos(bytes: ByteArray): List<Double> {
        repeat(CALENTAMIENTOS) { parsear(bytes) }
        val muestras = mutableListOf<Double>()
        repeat(MEDICIONES) {
            val inicio = System.nanoTime()
            val resultado = parsear(bytes)
            val fin = System.nanoTime()
            assertEquals(FixtureP1.ENTRADAS, resultado.entradas.size)
            muestras.add((fin - inicio) / 1_000_000.0)
        }
        return muestras
    }

    /**
     * Pico de heap muestreado durante el parseo. DC-10 define la memoria JVM
     * como el pico de heap bajo `-Xmx512m`, así que se muestrea
     * `totalMemory - freeMemory` mientras el parser trabaja. La llamada
     * explícita al GC limpia la línea base antes de medir; sin ella el pico
     * incluiría basura de los calentamientos y no la del parseo medido.
     */
    @Suppress("ExplicitGarbageCollectionCall")
    private fun medirPicoHeap(bytes: ByteArray): Double {
        val runtime = Runtime.getRuntime()
        val pico = AtomicLong(0)
        val alcance = CoroutineScope(Dispatchers.Default)
        val muestreador =
            alcance.launch {
                while (isActive) {
                    pico.updateAndGet { actual -> max(actual, runtime.totalMemory() - runtime.freeMemory()) }
                    delay(INTERVALO_MUESTREO_MS)
                }
            }
        try {
            System.gc()
            repeat(MEDICIONES_HEAP) { parsear(bytes) }
            return pico.get() / (1024.0 * 1024.0)
        } finally {
            muestreador.cancel()
            alcance.cancel()
        }
    }

    private fun parsear(bytes: ByteArray): ResultadoParserM3U = ByteArrayInputStream(bytes).use { ParserM3U.parsear(it) }

    private fun percentil95(muestras: List<Double>): Double {
        val ordenadas = muestras.sorted()
        val indice = ceil(PERCENTIL * ordenadas.size).toInt() - 1
        return ordenadas[indice]
    }

    private fun mediana(muestras: List<Double>): Double {
        val ordenadas = muestras.sorted()
        val medio = ordenadas.size / 2
        return if (ordenadas.size % 2 == 0) {
            (ordenadas[medio - 1] + ordenadas[medio]) / 2
        } else {
            ordenadas[medio]
        }
    }

    private fun escribirEvidencia(
        tiempos: List<Double>,
        mediana: Double,
        p95: Double,
        maximo: Double,
        picoHeapMiB: Double,
        cumple: Boolean,
    ) {
        val raiz = File(System.getProperty("repoRoot") ?: ".", "core/build/p1")
        raiz.mkdirs()
        File(raiz, "mediciones.csv").writeText(csv(tiempos))
        File(raiz, "medicion.json").writeText(json(mediana, p95, maximo, picoHeapMiB, cumple))
    }

    private fun csv(tiempos: List<Double>): String =
        buildString {
            appendLine("medicion,ms")
            tiempos.forEachIndexed { indice, valor -> appendLine("${indice + 1},${formatear(valor)}") }
        }

    private fun json(
        mediana: Double,
        p95: Double,
        maximo: Double,
        picoHeapMiB: Double,
        cumple: Boolean,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"esquema\": \"p1-medicion/v1\",")
            appendLine("  \"fecha\": \"${Instant.now()}\",")
            appendLine("  \"commit\": \"${System.getProperty("p1.commit") ?: DESCONOCIDO}\",")
            appendLine("  \"entorno\": {")
            appendLine("    \"runner\": \"${System.getenv("ImageOS") ?: "local"}\",")
            appendLine("    \"arquitectura\": \"${System.getenv("RUNNER_ARCH") ?: System.getProperty("os.arch")}\",")
            appendLine("    \"jdk\": \"${System.getProperty("java.version")}\",")
            appendLine("    \"heapMaximoBytes\": ${Runtime.getRuntime().maxMemory()}")
            appendLine("  },")
            appendLine("  \"fixture\": {")
            appendLine("    \"ruta\": \"core/src/test/resources${FixtureP1.RUTA}\",")
            appendLine("    \"entradas\": ${FixtureP1.ENTRADAS},")
            appendLine("    \"sha256\": \"${FixtureP1.SHA256}\"")
            appendLine("  },")
            appendLine("  \"metricas\": {")
            appendLine("    \"parseo\": {")
            appendLine("      \"calentamientos\": $CALENTAMIENTOS,")
            appendLine("      \"mediciones\": $MEDICIONES,")
            appendLine("      \"medianaMs\": ${formatear(mediana)},")
            appendLine("      \"p95Ms\": ${formatear(p95)},")
            appendLine("      \"maximoMs\": ${formatear(maximo)},")
            appendLine("      \"picoHeapMiB\": ${formatear(picoHeapMiB)}")
            appendLine("    }")
            appendLine("  },")
            appendLine("  \"limites\": {")
            appendLine("    \"parseoP95Ms\": ${formatear(LIMITE_PARSEO_MS)},")
            appendLine("    \"heapMiB\": ${formatear(LIMITE_HEAP_MIB)}")
            appendLine("  },")
            appendLine("  \"cumple\": $cumple")
            appendLine("}")
        }

    private fun formatear(valor: Double): String = String.format(Locale.ROOT, "%.3f", valor)

    private companion object {
        const val CALENTAMIENTOS = 10
        const val MEDICIONES = 30
        const val MEDICIONES_HEAP = 3
        const val INTERVALO_MUESTREO_MS = 2L
        const val PERCENTIL = 0.95
        const val LIMITE_PARSEO_MS = 1200.0
        const val LIMITE_HEAP_MIB = 256.0
        const val DESCONOCIDO = "desconocido"
    }
}
