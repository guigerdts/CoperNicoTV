package com.copernicotv.app.p1

import android.os.Build
import android.os.Debug
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.core.m3u.ParserM3U
import com.copernicotv.core.m3u.ResultadoParserM3U
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max

/**
 * Medición P1 de E2-T5 en Android conforme a DC-10.
 *
 * Ejecuta el parser real de `:core` sobre el fixture de 10 000 entradas en un
 * emulador API 34. El protocolo es el de DC-10 §4 para Android: 5
 * calentamientos y 20 mediciones de tiempo con p95 por nearest-rank. El pico de
 * heap Java se muestrea con `Runtime.totalMemory() - freeMemory()` durante cada
 * parseo y se registra el máximo; la memoria nativa se informa aparte y no se
 * usa para el límite.
 *
 * La evidencia se escribe en el almacenamiento externo de la app para que el
 * workflow la extraiga con `adb pull` aunque el test falle. Un fixture alterado,
 * una API distinta de 34, menos de 20 mediciones o un límite excedido hacen
 * fallar el test: no se publica una corrida parcial como válida.
 */
@RunWith(AndroidJUnit4::class)
class MedicionP1AndroidTest {
    @Test
    fun mideElParseoDe10kEntradasEnAndroid() {
        assertEquals("La medición P1 exige API 34 (DC-10)", API_ESPERADA, Build.VERSION.SDK_INT)
        val bytes = leerFixture()
        assertEquals("El fixture Android no coincide con F-P1-10k.m3u", SHA256_ESPERADO, sha256(bytes))

        repeat(CALENTAMIENTOS) { parsear(bytes) }

        val tiempos = medirTiempos(bytes)
        val picosHeap = medirPicoHeap(bytes)
        val p95 = percentil95(tiempos)
        val mediana = mediana(tiempos)
        val maximo = tiempos.maxOrNull() ?: error("sin mediciones de tiempo")
        val picoHeapMiB = picosHeap.maxOrNull() ?: error("sin mediciones de heap")
        val cumple = p95 <= LIMITE_PARSEO_MS && picoHeapMiB <= LIMITE_HEAP_MIB

        escribirEvidencia(tiempos, picosHeap, mediana, p95, maximo, picoHeapMiB, cumple)

        assertEquals("Se exigen $MEDICIONES mediciones de tiempo", MEDICIONES, tiempos.size)
        assertEquals("Se exigen $MEDICIONES_HEAP mediciones de heap", MEDICIONES_HEAP, picosHeap.size)
        assertTrue(
            "p95 de parseo ${formatear(p95)} ms excede ${formatear(LIMITE_PARSEO_MS)} ms",
            p95 <= LIMITE_PARSEO_MS,
        )
        assertTrue(
            "pico de heap ${formatear(picoHeapMiB)} MiB excede ${formatear(LIMITE_HEAP_MIB)} MiB",
            picoHeapMiB <= LIMITE_HEAP_MIB,
        )
    }

    private fun leerFixture(): ByteArray {
        val contexto = InstrumentationRegistry.getInstrumentation().context
        return contexto.assets.open(FIXTURE).use { it.readBytes() }
    }

    private fun parsear(bytes: ByteArray): ResultadoParserM3U = ByteArrayInputStream(bytes).use { ParserM3U.parsear(it) }

    private fun medirTiempos(bytes: ByteArray): List<Double> {
        val muestras = mutableListOf<Double>()
        repeat(MEDICIONES) {
            val inicio = System.nanoTime()
            val resultado = parsear(bytes)
            val fin = System.nanoTime()
            assertEquals(ENTRADAS, resultado.entradas.size)
            muestras.add((fin - inicio) / 1_000_000.0)
        }
        return muestras
    }

    /**
     * Pico de heap Java por medición. DC-10 exige muestrear
     * `Runtime.totalMemory() - freeMemory()` durante la operación y registrar el
     * máximo. El GC previo a cada muestreador limpia el resultado del parseo
     * anterior: sin él, el pico acumularía basura y no la memoria del parseo
     * medido. La pausa deja que el GC iniciado se estabilice antes de muestrear.
     */
    @Suppress("ExplicitGarbageCollectionCall")
    private fun medirPicoHeap(bytes: ByteArray): List<Double> {
        val runtime = Runtime.getRuntime()
        val muestras = mutableListOf<Double>()
        repeat(MEDICIONES_HEAP) {
            System.gc()
            runBlocking { delay(PAUSA_GC_MS) }
            val pico = AtomicLong(runtime.totalMemory() - runtime.freeMemory())
            val alcance = CoroutineScope(Dispatchers.Default)
            val muestreador =
                alcance.launch {
                    while (isActive) {
                        pico.updateAndGet { actual -> max(actual, runtime.totalMemory() - runtime.freeMemory()) }
                        delay(INTERVALO_MUESTREO_MS)
                    }
                }
            try {
                val resultado = parsear(bytes)
                assertEquals(ENTRADAS, resultado.entradas.size)
            } finally {
                runBlocking { muestreador.cancelAndJoin() }
                alcance.cancel()
            }
            muestras.add(pico.get() / (1024.0 * 1024.0))
        }
        return muestras
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
        picosHeap: List<Double>,
        mediana: Double,
        p95: Double,
        maximo: Double,
        picoHeapMiB: Double,
        cumple: Boolean,
    ) {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        val raiz =
            contexto.getExternalFilesDir(DIRECTORIO_EVIDENCIA)
                ?: error("El almacenamiento externo de la app no está disponible")
        raiz.mkdirs()
        File(raiz, "mediciones.csv").writeText(csv(tiempos, "medicion,ms"))
        File(raiz, "mediciones-heap.csv").writeText(csv(picosHeap, "medicion,heapMiB"))
        File(raiz, "medicion.json").writeText(json(mediana, p95, maximo, picoHeapMiB, cumple))
    }

    private fun csv(
        muestras: List<Double>,
        encabezado: String,
    ): String =
        buildString {
            appendLine(encabezado)
            muestras.forEachIndexed { indice, valor -> appendLine("${indice + 1},${formatear(valor)}") }
        }

    private fun json(
        mediana: Double,
        p95: Double,
        maximo: Double,
        picoHeapMiB: Double,
        cumple: Boolean,
    ): String {
        val instrumentacion = InstrumentationRegistry.getInstrumentation()
        val argumentos = InstrumentationRegistry.getArguments()
        val contexto = instrumentacion.targetContext
        val metricas = contexto.resources.displayMetrics
        val commit = argumentos.getString("p1.commit") ?: DESCONOCIDO
        return buildString {
            appendLine("{")
            appendLine("  \"esquema\": \"p1-medicion-android/v1\",")
            appendLine("  \"fecha\": \"${Instant.now()}\",")
            appendLine("  \"commit\": \"${escapar(commit)}\",")
            appendLine("  \"entorno\": {")
            appendLine("    \"plataforma\": \"android\",")
            appendLine("    \"api\": ${Build.VERSION.SDK_INT},")
            appendLine("    \"release\": \"${escapar(Build.VERSION.RELEASE)}\",")
            appendLine("    \"modelo\": \"${escapar(Build.MODEL)}\",")
            appendLine("    \"dispositivo\": \"${escapar(Build.DEVICE)}\",")
            appendLine("    \"producto\": \"${escapar(Build.PRODUCT)}\",")
            appendLine("    \"hardware\": \"${escapar(Build.HARDWARE)}\",")
            appendLine("    \"fingerprint\": \"${escapar(Build.FINGERPRINT)}\",")
            appendLine("    \"anchoPx\": ${metricas.widthPixels},")
            appendLine("    \"altoPx\": ${metricas.heightPixels},")
            appendLine("    \"densidadDpi\": ${metricas.densityDpi},")
            appendLine("    \"heapMaximoBytes\": ${Runtime.getRuntime().maxMemory()},")
            appendLine("    \"memoriaNativaBytes\": ${Debug.getNativeHeapAllocatedSize()}")
            appendLine("  },")
            appendLine("  \"fixture\": {")
            appendLine("    \"ruta\": \"app/src/androidTest/assets/$FIXTURE\",")
            appendLine("    \"entradas\": $ENTRADAS,")
            appendLine("    \"sha256\": \"$SHA256_ESPERADO\"")
            appendLine("  },")
            appendLine("  \"metricas\": {")
            appendLine("    \"parseo\": {")
            appendLine("      \"calentamientos\": $CALENTAMIENTOS,")
            appendLine("      \"mediciones\": $MEDICIONES,")
            appendLine("      \"medicionesHeap\": $MEDICIONES_HEAP,")
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
    }

    private fun escapar(valor: String): String = valor.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }

    private fun formatear(valor: Double): String = String.format(Locale.ROOT, "%.3f", valor)

    private companion object {
        const val FIXTURE = "F-P1-10k.m3u"
        const val SHA256_ESPERADO = "45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c"
        const val ENTRADAS = 10_000
        const val CALENTAMIENTOS = 5
        const val MEDICIONES = 20
        const val MEDICIONES_HEAP = 20
        const val INTERVALO_MUESTREO_MS = 2L
        const val PAUSA_GC_MS = 50L
        const val PERCENTIL = 0.95
        const val LIMITE_PARSEO_MS = 2500.0
        const val LIMITE_HEAP_MIB = 384.0
        const val API_ESPERADA = 34
        const val DIRECTORIO_EVIDENCIA = "p1"
        const val DESCONOCIDO = "desconocido"
    }
}
