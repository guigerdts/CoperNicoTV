package com.copernicotv.app.p1

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.CanalBusqueda
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.m3u.ParserM3U
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancel
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.ByteArrayInputStream
import java.io.File
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.concurrent.atomic.AtomicLong
import kotlin.math.ceil
import kotlin.math.max

/**
 * Medición P1 de E3-T1 conforme a DC-10: primera página de búsqueda y página 2.
 *
 * Se ejecuta solo con `-Dp1.medir=true`, que activa la tarea
 * `:app:testDebugUnitTest` con `-Pp1.busqueda=true`.
 * La base se puebla con las 10 000 entradas de `F-P1-10k.m3u`; la medición usa
 * la misma consulta `PagingSource` que entrega `BusquedaCatalogoRepositorio` a
 * Paging 3, con tamaño de página 50. No mide Compose ni una lista precargada.
 *
 * La primera página se mide desde el inicio de `load(Refresh)` hasta que la
 * página completa de 50 resultados está disponible. La página 2 precarga la
 * página 1 fuera del cronómetro y mide solo `load(Append)`, como exige DC-10.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class MedicionP1BusquedaTest {
    private var db: AppDatabase? = null

    @Before
    fun exigirModoMedicion() {
        assumeTrue(
            "medición P1 de búsqueda desactivada; usar :app:testDebugUnitTest -Pp1.busqueda=true",
            System.getProperty("p1.medir") == "true",
        )
    }

    @After
    fun cerrar() {
        db?.close()
    }

    @Test
    fun `mide la primera pagina y la pagina 2 de busqueda dentro de los limites de DC-10`() {
        val bytes = fixtureBytes()
        assertEquals("El fixture P1 no coincide con el hash versionado", SHA256, sha256(bytes))
        val base = abrirBase()
        db = base

        runBlocking { sembrar(base, bytes) }
        verificarPaginasIniciales(base)

        val primera = medirTiempos { cargarPrimeraPagina(base) }
        val pagina2 = medirTiempos { cargarPagina2(base) }
        val picoPrimera = medirPicoPrimeraPagina(base)
        val picoPagina2 = medirPicoPagina2(base)

        assertEquals("Se exigen $MEDICIONES mediciones de primera página", MEDICIONES, primera.size)
        assertEquals("Se exigen $MEDICIONES mediciones de página 2", MEDICIONES, pagina2.size)

        val p95Primera = percentil95(primera)
        val p95Pagina2 = percentil95(pagina2)
        val metricasPrimera =
            MetricasEscenario(
                muestras = primera,
                picoHeapMiB = picoPrimera,
                medianaMs = mediana(primera),
                p95Ms = p95Primera,
                maximoMs = primera.maxOrNull() ?: error("sin mediciones de primera página"),
                cumple = p95Primera <= LIMITE_PRIMERA_PAGINA_MS,
            )
        val metricasPagina2 =
            MetricasEscenario(
                muestras = pagina2,
                picoHeapMiB = picoPagina2,
                medianaMs = mediana(pagina2),
                p95Ms = p95Pagina2,
                maximoMs = pagina2.maxOrNull() ?: error("sin mediciones de página 2"),
                cumple = p95Pagina2 <= LIMITE_PAGINA_2_MS,
            )
        val picoHeap = max(metricasPrimera.picoHeapMiB, metricasPagina2.picoHeapMiB)
        val cumpleHeap = picoHeap <= LIMITE_HEAP_MIB
        val cumple = metricasPrimera.cumple && metricasPagina2.cumple && cumpleHeap

        escribirEvidencia(
            primera = metricasPrimera,
            pagina2 = metricasPagina2,
            cumpleHeap = cumpleHeap,
            cumple = cumple,
        )

        assertTrue(
            "p95 de primera página ${formatear(metricasPrimera.p95Ms)} ms excede ${formatear(LIMITE_PRIMERA_PAGINA_MS)} ms",
            metricasPrimera.cumple,
        )
        assertTrue(
            "p95 de página 2 ${formatear(metricasPagina2.p95Ms)} ms excede ${formatear(LIMITE_PAGINA_2_MS)} ms",
            metricasPagina2.cumple,
        )
        assertTrue(
            "pico de heap ${formatear(picoHeap)} MiB excede ${formatear(LIMITE_HEAP_MIB)} MiB",
            cumpleHeap,
        )
    }

    private fun abrirBase(): AppDatabase {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        return Room
            .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    private suspend fun sembrar(
        base: AppDatabase,
        bytes: ByteArray,
    ) {
        val resultado = ParserM3U.parsear(ByteArrayInputStream(bytes))
        assertEquals(ENTRADAS, resultado.entradas.size)
        assertTrue("El fixture P1 no debe tener errores de parseo", resultado.errores.isEmpty())

        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        val fuenteId =
            base.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente P1 E3",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        val listaId =
            base.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "Lista activa E3",
                    ubicacionCifrada = byteArrayOf(0x01),
                    ubicacionHmac = byteArrayOf(0x02),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )

        val nombresGrupo = resultado.entradas.map { it.groupTitle ?: SIN_GRUPO }.distinct()
        val idsGrupo =
            base.grupoDao().insertarTodos(
                nombresGrupo.map { nombre -> GrupoEntity(listaId = listaId, nombre = nombre) },
            )
        val grupoPorNombre = nombresGrupo.zip(idsGrupo).toMap()

        val idsCanal =
            base.canalDao().insertarTodos(
                resultado.entradas.map { entrada ->
                    val tvgId = entrada.tvgId ?: "p1-${entrada.ordinal}"
                    CanalEntity(
                        fuenteId = fuenteId,
                        nombre = entrada.nombre,
                        urlStreamCifrada = byteArrayOf(0x01),
                        urlStreamHmac = "hmac-${entrada.ordinal}".toByteArray(Charsets.UTF_8),
                        tvgId = tvgId,
                        claveCanonica = tvgId,
                        claveUnicidad = tvgId,
                        claveDerivable = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    )
                },
            )

        base.aparienciaDao().insertarTodas(
            resultado.entradas.mapIndexed { indice, entrada ->
                AparienciaEntity(
                    listaId = listaId,
                    canalId = idsCanal[indice],
                    grupoId = grupoPorNombre[entrada.groupTitle ?: SIN_GRUPO],
                    orden = entrada.ordinal.toInt(),
                    groupTitle = entrada.groupTitle,
                    tvgName = entrada.tvgName,
                    clavePresentacion = "presentacion:v2:${entrada.ordinal}",
                    repeticiones = 1,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                )
            },
        )
    }

    private fun verificarPaginasIniciales(base: AppDatabase) {
        val primera = cargarPrimeraPagina(base).first
        val segunda = cargarPagina2(base).first
        verificarPagina(primera)
        verificarPagina(segunda)
        assertTrue(
            "La página 2 no debe repetir IDs de la página 1",
            primera.map { it.id }.intersect(segunda.map { it.id }.toSet()).isEmpty(),
        )
    }

    private fun medirTiempos(accion: () -> Pair<List<CanalBusqueda>, Double>): List<Double> {
        repeat(CALENTAMIENTOS) {
            val (datos, _) = accion()
            verificarPagina(datos)
        }
        return buildList {
            repeat(MEDICIONES) {
                val (datos, milisegundos) = accion()
                verificarPagina(datos)
                add(milisegundos)
            }
        }
    }

    private fun cargarPrimeraPagina(base: AppDatabase): Pair<List<CanalBusqueda>, Double> {
        val fuente = nuevaFuente(base)
        val inicio = System.nanoTime()
        val datos = cargar(fuente, PagingSource.LoadParams.Refresh(key = 0, loadSize = TAMANO, placeholdersEnabled = false))
        return datos to ((System.nanoTime() - inicio) / 1_000_000.0)
    }

    private fun cargarPagina2(base: AppDatabase): Pair<List<CanalBusqueda>, Double> {
        val fuente = nuevaFuente(base)
        val primera =
            cargarPagina(
                fuente,
                PagingSource.LoadParams.Refresh(key = 0, loadSize = TAMANO, placeholdersEnabled = false),
            )
        verificarPagina(primera.data)
        val clave = requireNotNull(primera.nextKey) { "La búsqueda debe tener una página 2" }
        val inicio = System.nanoTime()
        val datos =
            cargar(
                fuente,
                PagingSource.LoadParams.Append(key = clave, loadSize = TAMANO, placeholdersEnabled = false),
            )
        return datos to ((System.nanoTime() - inicio) / 1_000_000.0)
    }

    private fun cargarPrimeraPaginaSinTiempo(base: AppDatabase) {
        val datos =
            cargar(
                nuevaFuente(base),
                PagingSource.LoadParams.Refresh(key = 0, loadSize = TAMANO, placeholdersEnabled = false),
            )
        verificarPagina(datos)
    }

    private fun nuevaFuente(base: AppDatabase): PagingSource<Int, CanalBusqueda> =
        base.canalDao().buscarEnListaActiva(
            texto = TEXTO,
            patron = "%$TEXTO%",
            grupoId = null,
            soloFavoritos = false,
        )

    private fun cargar(
        fuente: PagingSource<Int, CanalBusqueda>,
        parametros: PagingSource.LoadParams<Int>,
    ): List<CanalBusqueda> = cargarPagina(fuente, parametros).data

    private fun cargarPagina(
        fuente: PagingSource<Int, CanalBusqueda>,
        parametros: PagingSource.LoadParams<Int>,
    ): PagingSource.LoadResult.Page<Int, CanalBusqueda> {
        val resultado = runBlocking { fuente.load(parametros) }
        assertTrue("La consulta debía devolver una página", resultado is PagingSource.LoadResult.Page)
        return resultado as PagingSource.LoadResult.Page<Int, CanalBusqueda>
    }

    private fun verificarPagina(pagina: List<CanalBusqueda>) {
        assertEquals("La página debe tener $TAMANO resultados", TAMANO, pagina.size)
        assertEquals("La página no debe tener IDs duplicados", TAMANO, pagina.map { it.id }.distinct().size)
    }

    private fun medirPicoPrimeraPagina(base: AppDatabase): Double {
        var pico = 0.0
        repeat(MEDICIONES_HEAP) {
            pico = max(pico, medirPicoHeap { cargarPrimeraPaginaSinTiempo(base) })
        }
        return pico
    }

    private fun medirPicoPagina2(base: AppDatabase): Double {
        var pico = 0.0
        repeat(MEDICIONES_HEAP) {
            val fuente = nuevaFuente(base)
            val primera =
                cargarPagina(
                    fuente,
                    PagingSource.LoadParams.Refresh(key = 0, loadSize = TAMANO, placeholdersEnabled = false),
                )
            verificarPagina(primera.data)
            val clave = requireNotNull(primera.nextKey) { "La búsqueda debe tener una página 2" }
            pico =
                max(
                    pico,
                    medirPicoHeap {
                        val datos =
                            cargar(
                                fuente,
                                PagingSource.LoadParams.Append(
                                    key = clave,
                                    loadSize = TAMANO,
                                    placeholdersEnabled = false,
                                ),
                            )
                        verificarPagina(datos)
                    },
                )
        }
        return pico
    }

    /**
     * Pico de heap Java durante una operación concreta. DC-10 exige muestrear
     * `Runtime.totalMemory() - freeMemory()` y registrar el máximo. El GC previo
     * limpia la línea base para que el pico pertenezca a la consulta medida.
     */
    @Suppress("ExplicitGarbageCollectionCall")
    private fun medirPicoHeap(accion: () -> Unit): Double {
        val runtime = Runtime.getRuntime()
        System.gc()
        val pico = AtomicLong(runtime.totalMemory() - runtime.freeMemory())
        val alcance = CoroutineScope(Dispatchers.Default)
        val muestreador =
            alcance.launch {
                while (isActive) {
                    pico.updateAndGet { actual -> max(actual, runtime.totalMemory() - runtime.freeMemory()) }
                    delay(INTERVALO_MUESTREO_MS)
                }
            }
        return try {
            accion()
            pico.get() / (1024.0 * 1024.0)
        } finally {
            runBlocking { muestreador.cancelAndJoin() }
            alcance.cancel()
        }
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

    private fun fixtureBytes(): ByteArray =
        requireNotNull(javaClass.getResourceAsStream(FIXTURE_RUTA)) {
            "no se encontró el recurso $FIXTURE_RUTA"
        }.use { it.readBytes() }

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { byte -> String.format(Locale.ROOT, "%02x", byte.toInt() and 0xff) }

    private fun escribirEvidencia(
        primera: MetricasEscenario,
        pagina2: MetricasEscenario,
        cumpleHeap: Boolean,
        cumple: Boolean,
    ) {
        val raiz = File(System.getProperty("repoRoot") ?: ".", "app/build/p1-busqueda")
        raiz.mkdirs()
        File(raiz, "primera-pagina.csv").writeText(csv(primera.muestras))
        File(raiz, "pagina-2.csv").writeText(csv(pagina2.muestras))
        File(raiz, "heap.csv").writeText(heapCsv(primera.picoHeapMiB, pagina2.picoHeapMiB))
        File(raiz, "medicion.json").writeText(
            json(
                primera = primera,
                pagina2 = pagina2,
                cumpleHeap = cumpleHeap,
                cumple = cumple,
            ),
        )
    }

    private fun csv(muestras: List<Double>): String =
        buildString {
            appendLine("medicion,ms")
            muestras.forEachIndexed { indice, valor -> appendLine("${indice + 1},${formatear(valor)}") }
        }

    private fun heapCsv(
        picoPrimera: Double,
        picoPagina2: Double,
    ): String =
        buildString {
            appendLine("escenario,heapMiB")
            appendLine("primeraPagina,${formatear(picoPrimera)}")
            appendLine("pagina2,${formatear(picoPagina2)}")
        }

    private fun json(
        primera: MetricasEscenario,
        pagina2: MetricasEscenario,
        cumpleHeap: Boolean,
        cumple: Boolean,
    ): String =
        buildString {
            appendLine("{")
            appendLine("  \"esquema\": \"p1-medicion-busqueda/v1\",")
            appendLine("  \"fecha\": \"${Instant.now()}\",")
            appendLine("  \"commit\": \"${System.getProperty("p1.commit") ?: DESCONOCIDO}\",")
            appendLine("  \"entorno\": {")
            appendLine("    \"plataforma\": \"jvm\",")
            appendLine("    \"runner\": \"${System.getenv("ImageOS") ?: "local"}\",")
            appendLine("    \"arquitectura\": \"${System.getenv("RUNNER_ARCH") ?: System.getProperty("os.arch")}\",")
            appendLine("    \"sistema\": \"${System.getProperty("os.name")}\",")
            appendLine("    \"jdk\": \"${System.getProperty("java.version")}\",")
            appendLine("    \"robolectricSdk\": $SDK_ROBOLECTRIC,")
            appendLine("    \"heapMaximoBytes\": ${Runtime.getRuntime().maxMemory()}")
            appendLine("  },")
            appendLine("  \"fixture\": {")
            appendLine("    \"ruta\": \"core/src/test/resources$FIXTURE_RUTA\",")
            appendLine("    \"entradas\": $ENTRADAS,")
            appendLine("    \"bytes\": $FIXTURE_BYTES,")
            appendLine("    \"sha256\": \"$SHA256\"")
            appendLine("  },")
            appendLine("  \"procedimiento\": {")
            appendLine("    \"texto\": \"$TEXTO\",")
            appendLine("    \"tamanoPagina\": $TAMANO,")
            appendLine("    \"calentamientos\": $CALENTAMIENTOS,")
            appendLine("    \"mediciones\": $MEDICIONES,")
            appendLine("    \"medicionesHeap\": $MEDICIONES_HEAP,")
            appendLine("    \"estadistico\": \"p95 nearest-rank\",")
            appendLine("    \"ruta\": \"CanalDao.buscarEnListaActiva -> PagingSource -> Room\"")
            appendLine("  },")
            appendLine("  \"escenarios\": {")
            appendLine("    \"primeraPagina\": {")
            appendLine("      \"calentamientos\": $CALENTAMIENTOS,")
            appendLine("      \"mediciones\": ${primera.muestras.size},")
            appendLine("      \"medianaMs\": ${formatear(primera.medianaMs)},")
            appendLine("      \"p95Ms\": ${formatear(primera.p95Ms)},")
            appendLine("      \"maximoMs\": ${formatear(primera.maximoMs)},")
            appendLine("      \"picoHeapMiB\": ${formatear(primera.picoHeapMiB)},")
            appendLine("      \"muestrasMs\": ${arrayJson(primera.muestras)}")
            appendLine("    },")
            appendLine("    \"pagina2\": {")
            appendLine("      \"calentamientos\": $CALENTAMIENTOS,")
            appendLine("      \"mediciones\": ${pagina2.muestras.size},")
            appendLine("      \"medianaMs\": ${formatear(pagina2.medianaMs)},")
            appendLine("      \"p95Ms\": ${formatear(pagina2.p95Ms)},")
            appendLine("      \"maximoMs\": ${formatear(pagina2.maximoMs)},")
            appendLine("      \"picoHeapMiB\": ${formatear(pagina2.picoHeapMiB)},")
            appendLine("      \"muestrasMs\": ${arrayJson(pagina2.muestras)}")
            appendLine("    }")
            appendLine("  },")
            appendLine("  \"limites\": {")
            appendLine("    \"primeraPaginaP95Ms\": ${formatear(LIMITE_PRIMERA_PAGINA_MS)},")
            appendLine("    \"pagina2P95Ms\": ${formatear(LIMITE_PAGINA_2_MS)},")
            appendLine("    \"heapMiB\": ${formatear(LIMITE_HEAP_MIB)}")
            appendLine("  },")
            appendLine("  \"cumplimiento\": {")
            appendLine("    \"primeraPagina\": ${primera.cumple},")
            appendLine("    \"pagina2\": ${pagina2.cumple},")
            appendLine("    \"heap\": $cumpleHeap")
            appendLine("  },")
            appendLine("  \"cumple\": $cumple,")
            appendLine("  \"descartes\": []")
            appendLine("}")
        }

    private fun arrayJson(valores: List<Double>): String = valores.joinToString(prefix = "[", postfix = "]") { formatear(it) }

    private fun formatear(valor: Double): String = String.format(Locale.ROOT, "%.3f", valor)

    private data class MetricasEscenario(
        val muestras: List<Double>,
        val picoHeapMiB: Double,
        val medianaMs: Double,
        val p95Ms: Double,
        val maximoMs: Double,
        val cumple: Boolean,
    )

    private companion object {
        const val FIXTURE_RUTA = "/fixtures/F-P1-10k.m3u"
        const val SHA256 = "45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c"
        const val ENTRADAS = 10_000
        const val FIXTURE_BYTES = 1_940_008
        const val TAMANO = 50
        const val TEXTO = "Canal P1"
        const val SIN_GRUPO = "Sin grupo"
        const val CALENTAMIENTOS = 10
        const val MEDICIONES = 30
        const val MEDICIONES_HEAP = 3
        const val INTERVALO_MUESTREO_MS = 2L
        const val PERCENTIL = 0.95
        const val LIMITE_PRIMERA_PAGINA_MS = 150.0
        const val LIMITE_PAGINA_2_MS = 80.0
        const val LIMITE_HEAP_MIB = 256.0
        const val SDK_ROBOLECTRIC = 34
        const val DESCONOCIDO = "desconocido"
    }
}
