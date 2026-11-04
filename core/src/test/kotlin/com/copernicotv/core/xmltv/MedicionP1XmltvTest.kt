package com.copernicotv.core.xmltv

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
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
 * Medición P1 de XMLTV para S24/E5-T1.
 *
 * Se ejecuta solo con `-Dp1.medir=true` mediante la tarea
 * `:core:p1XmltvPerformance`. Aplica el procedimiento de DC-10: 10
 * calentamientos, 30 mediciones, p95 nearest-rank y pico de heap bajo
 * `-Xmx512m`. Los límites absolutos son los de P1 hasta que exista un baseline
 * XMLTV aprobado; este test no declara un baseline nuevo.
 *
 * El fixture F24a está versionado en `core/src/test/resources/fixtures/` y
 * conserva la semántica determinista de 100 canales y 10 000 programas.
 */
class MedicionP1XmltvTest {
    @BeforeEach
    fun exigirModoMedicion() {
        assumeTrue(
            System.getProperty("p1.medir") == "true",
            "medición P1 XMLTV desactivada; usar la tarea :core:p1XmltvPerformance",
        )
    }

    @Test
    fun `mide el parseo de 10 000 programas XMLTV dentro de los limites de P1`() {
        val bytes = FixtureP1Xmltv.bytes()
        assertEquals(FixtureP1Xmltv.SHA256, FixtureP1Xmltv.sha256(bytes))
        val tiempos = medirTiempos(bytes)
        val picoHeapMiB = medirPicoHeap(bytes)
        val p95 = percentil95(tiempos)
        val mediana = mediana(tiempos)
        val maximo = tiempos.max()

        val cumple = p95 <= LIMITE_PARSEO_MS && picoHeapMiB <= LIMITE_HEAP_MIB
        escribirEvidencia(tiempos, mediana, p95, maximo, picoHeapMiB, cumple, bytes)

        assertTrue(
            p95 <= LIMITE_PARSEO_MS,
            "p95 de parseo XMLTV ${formatear(p95)} ms excede ${formatear(LIMITE_PARSEO_MS)} ms",
        )
        assertTrue(
            picoHeapMiB <= LIMITE_HEAP_MIB,
            "pico de heap XMLTV ${formatear(picoHeapMiB)} MiB excede ${formatear(LIMITE_HEAP_MIB)} MiB",
        )
    }

    private fun medirTiempos(bytes: ByteArray): List<Double> {
        repeat(CALENTAMIENTOS) { parsear(bytes) }
        val muestras = mutableListOf<Double>()
        repeat(MEDICIONES) {
            val inicio = System.nanoTime()
            val resultado = parsear(bytes)
            val fin = System.nanoTime()
            assertEquals(FixtureP1Xmltv.PROGRAMAS, resultado.programas)
            assertEquals(FixtureP1Xmltv.CANALES, resultado.canales)
            muestras.add((fin - inicio) / 1_000_000.0)
        }
        return muestras
    }

    @Suppress("ExplicitGarbageCollectionCall")
    private fun medirPicoHeap(bytes: ByteArray): Double {
        val runtime = Runtime.getRuntime()
        System.gc()
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
            repeat(MEDICIONES_HEAP) { parsear(bytes) }
            return pico.get() / (1024.0 * 1024.0)
        } finally {
            muestreador.cancel()
            alcance.cancel()
        }
    }

    private fun parsear(bytes: ByteArray): ResultadoParseoXmltv =
        runBlocking {
            ByteArrayInputStream(bytes).use { ParserXmltv().parsear(it) }
        }

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
        bytes: ByteArray,
    ) {
        val raiz = File(System.getProperty("repoRoot") ?: ".", "core/build/p1-xmltv")
        raiz.mkdirs()
        File(raiz, "mediciones.csv").writeText(csv(tiempos))
        File(raiz, "medicion.json").writeText(
            json(mediana, p95, maximo, picoHeapMiB, cumple, FixtureP1Xmltv.sha256(bytes)),
        )
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
        sha256: String,
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
            appendLine("    \"ruta\": \"core/src/test/resources${FixtureP1Xmltv.RUTA}\",")
            appendLine("    \"descripcion\": \"F24a XMLTV válido: 100 canales y 10 000 programas\",")
            appendLine("    \"canales\": ${FixtureP1Xmltv.CANALES},")
            appendLine("    \"programas\": ${FixtureP1Xmltv.PROGRAMAS},")
            appendLine("    \"sha256\": \"$sha256\"")
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
            appendLine("    \"heapMiB\": ${formatear(LIMITE_HEAP_MIB)},")
            appendLine("    \"baseline\": \"sin baseline XMLTV aprobado\"")
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
