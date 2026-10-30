package com.copernicotv.app.p1

import android.os.Build
import android.os.Debug
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
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
 * Medición P1 de E3-T1 en Android conforme a DC-10: primera página de búsqueda
 * y página 2 sobre 10 000 Canales.
 *
 * La base se puebla con las 10 000 entradas de `F-P1-10k.m3u`; la medición usa
 * la misma consulta `PagingSource` que `BusquedaCatalogoRepositorio` entrega a
 * Paging 3, con tamaño de página 50. No mide Compose ni una lista precargada.
 * Protocolo Android: 5 calentamientos y 20 mediciones por escenario; p95
 * nearest-rank; pico de heap Java muestreado durante la consulta.
 */
@RunWith(AndroidJUnit4::class)
class MedicionP1BusquedaAndroidTest {
    private var db: AppDatabase? = null

    @After
    fun cerrar() {
        db?.close()
    }

    @Test
    fun midePrimeraPaginaYPagina2DeBusquedaEnAndroid() {
        assertEquals("La medición P1 exige API 34 (DC-10)", API_ESPERADA, Build.VERSION.SDK_INT)
        val bytes = leerFixture()
        assertEquals("El fixture Android no coincide con F-P1-10k.m3u", SHA256, sha256(bytes))
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

        val medianaPrimera = mediana(primera)
        val p95Primera = percentil95(primera)
        val maximoPrimera = primera.maxOrNull() ?: error("sin mediciones de primera página")
        val medianaPagina2 = mediana(pagina2)
        val p95Pagina2 = percentil95(pagina2)
        val maximoPagina2 = pagina2.maxOrNull() ?: error("sin mediciones de página 2")
        val picoHeap = max(picoPrimera, picoPagina2)

        val cumplePrimera = p95Primera <= LIMITE_PRIMERA_PAGINA_MS
        val cumplePagina2 = p95Pagina2 <= LIMITE_PAGINA_2_MS
        val cumpleHeap = picoHeap <= LIMITE_HEAP_MIB
        val cumple = cumplePrimera && cumplePagina2 && cumpleHeap

        escribirEvidencia(
            primera = primera,
            pagina2 = pagina2,
            picoPrimera = picoPrimera,
            picoPagina2 = picoPagina2,
            medianaPrimera = medianaPrimera,
            p95Primera = p95Primera,
            maximoPrimera = maximoPrimera,
            medianaPagina2 = medianaPagina2,
            p95Pagina2 = p95Pagina2,
            maximoPagina2 = maximoPagina2,
            cumplePrimera = cumplePrimera,
            cumplePagina2 = cumplePagina2,
            cumpleHeap = cumpleHeap,
            cumple = cumple,
        )

        assertTrue(
            "p95 de primera página ${formatear(p95Primera)} ms excede ${formatear(LIMITE_PRIMERA_PAGINA_MS)} ms",
            cumplePrimera,
        )
        assertTrue(
            "p95 de página 2 ${formatear(p95Pagina2)} ms excede ${formatear(LIMITE_PAGINA_2_MS)} ms",
            cumplePagina2,
        )
        assertTrue(
            "pico de heap ${formatear(picoHeap)} MiB excede ${formatear(LIMITE_HEAP_MIB)} MiB",
            cumpleHeap,
        )
    }

    private fun abrirBase(): AppDatabase {
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
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
                    nombre = "Fuente P1 E3 Android",
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
                    nombre = "Lista activa E3 Android",
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
            incluirOcultos = false,
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

    @Suppress("ExplicitGarbageCollectionCall")
    private fun medirPicoHeap(accion: () -> Unit): Double {
        val runtime = Runtime.getRuntime()
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

    private fun leerFixture(): ByteArray {
        val contexto = InstrumentationRegistry.getInstrumentation().context
        return contexto.assets.open(FIXTURE).use { it.readBytes() }
    }

    private fun escribirEvidencia(
        primera: List<Double>,
        pagina2: List<Double>,
        picoPrimera: Double,
        picoPagina2: Double,
        medianaPrimera: Double,
        p95Primera: Double,
        maximoPrimera: Double,
        medianaPagina2: Double,
        p95Pagina2: Double,
        maximoPagina2: Double,
        cumplePrimera: Boolean,
        cumplePagina2: Boolean,
        cumpleHeap: Boolean,
        cumple: Boolean,
    ) {
        val raiz = directorioEvidencia()
        raiz.mkdirs()
        File(raiz, "busqueda-primera-pagina.csv").writeText(csv(primera))
        File(raiz, "busqueda-pagina-2.csv").writeText(csv(pagina2))
        File(raiz, "busqueda-heap.csv").writeText(heapCsv(picoPrimera, picoPagina2))
        File(raiz, "busqueda-medicion.json").writeText(
            json(
                primera = primera,
                pagina2 = pagina2,
                picoPrimera = picoPrimera,
                picoPagina2 = picoPagina2,
                medianaPrimera = medianaPrimera,
                p95Primera = p95Primera,
                maximoPrimera = maximoPrimera,
                medianaPagina2 = medianaPagina2,
                p95Pagina2 = p95Pagina2,
                maximoPagina2 = maximoPagina2,
                cumplePrimera = cumplePrimera,
                cumplePagina2 = cumplePagina2,
                cumpleHeap = cumpleHeap,
                cumple = cumple,
            ),
        )
    }

    private fun directorioEvidencia(): File {
        val rutaAdicional =
            InstrumentationRegistry.getArguments().getString(ARGUMENTO_SALIDA_ADICIONAL)
        if (!rutaAdicional.isNullOrBlank()) {
            return File(rutaAdicional)
        }
        val contexto = InstrumentationRegistry.getInstrumentation().targetContext
        return contexto.getExternalFilesDir(DIRECTORIO_EVIDENCIA)
            ?: error("El almacenamiento externo de la app no está disponible")
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
        primera: List<Double>,
        pagina2: List<Double>,
        picoPrimera: Double,
        picoPagina2: Double,
        medianaPrimera: Double,
        p95Primera: Double,
        maximoPrimera: Double,
        medianaPagina2: Double,
        p95Pagina2: Double,
        maximoPagina2: Double,
        cumplePrimera: Boolean,
        cumplePagina2: Boolean,
        cumpleHeap: Boolean,
        cumple: Boolean,
    ): String {
        val instrumentacion = InstrumentationRegistry.getInstrumentation()
        val argumentos = InstrumentationRegistry.getArguments()
        val contexto = instrumentacion.targetContext
        val metricas = contexto.resources.displayMetrics
        val commit = argumentos.getString("p1.commit") ?: DESCONOCIDO
        return buildString {
            appendLine("{")
            appendLine("  \"esquema\": \"p1-medicion-busqueda-android/v1\",")
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
            appendLine("      \"mediciones\": ${primera.size},")
            appendLine("      \"medianaMs\": ${formatear(medianaPrimera)},")
            appendLine("      \"p95Ms\": ${formatear(p95Primera)},")
            appendLine("      \"maximoMs\": ${formatear(maximoPrimera)},")
            appendLine("      \"picoHeapMiB\": ${formatear(picoPrimera)},")
            appendLine("      \"muestrasMs\": ${arrayJson(primera)}")
            appendLine("    },")
            appendLine("    \"pagina2\": {")
            appendLine("      \"calentamientos\": $CALENTAMIENTOS,")
            appendLine("      \"mediciones\": ${pagina2.size},")
            appendLine("      \"medianaMs\": ${formatear(medianaPagina2)},")
            appendLine("      \"p95Ms\": ${formatear(p95Pagina2)},")
            appendLine("      \"maximoMs\": ${formatear(maximoPagina2)},")
            appendLine("      \"picoHeapMiB\": ${formatear(picoPagina2)},")
            appendLine("      \"muestrasMs\": ${arrayJson(pagina2)}")
            appendLine("    }")
            appendLine("  },")
            appendLine("  \"limites\": {")
            appendLine("    \"primeraPaginaP95Ms\": ${formatear(LIMITE_PRIMERA_PAGINA_MS)},")
            appendLine("    \"pagina2P95Ms\": ${formatear(LIMITE_PAGINA_2_MS)},")
            appendLine("    \"heapMiB\": ${formatear(LIMITE_HEAP_MIB)}")
            appendLine("  },")
            appendLine("  \"cumplimiento\": {")
            appendLine("    \"primeraPagina\": $cumplePrimera,")
            appendLine("    \"pagina2\": $cumplePagina2,")
            appendLine("    \"heap\": $cumpleHeap")
            appendLine("  },")
            appendLine("  \"cumple\": $cumple,")
            appendLine("  \"descartes\": []")
            appendLine("}")
        }
    }

    private fun arrayJson(valores: List<Double>): String = valores.joinToString(prefix = "[", postfix = "]") { formatear(it) }

    private fun escapar(valor: String): String = valor.replace("\\", "\\\\").replace("\"", "\\\"")

    private fun sha256(bytes: ByteArray): String =
        MessageDigest
            .getInstance("SHA-256")
            .digest(bytes)
            .joinToString("") { String.format(Locale.ROOT, "%02x", it.toInt() and 0xff) }

    private fun formatear(valor: Double): String = String.format(Locale.ROOT, "%.3f", valor)

    private companion object {
        const val FIXTURE = "F-P1-10k.m3u"
        const val SHA256 = "45c489a557efcafa53af5bffd9a001aeb2cdc1616c40a4f823e2f39817ddd04c"
        const val FIXTURE_BYTES = 1_940_008
        const val ENTRADAS = 10_000
        const val TAMANO = 50
        const val TEXTO = "Canal P1"
        const val SIN_GRUPO = "Sin grupo"
        const val CALENTAMIENTOS = 5
        const val MEDICIONES = 20
        const val MEDICIONES_HEAP = 3
        const val INTERVALO_MUESTREO_MS = 2L
        const val PAUSA_GC_MS = 50L
        const val PERCENTIL = 0.95
        const val LIMITE_PRIMERA_PAGINA_MS = 300.0
        const val LIMITE_PAGINA_2_MS = 150.0
        const val LIMITE_HEAP_MIB = 384.0
        const val API_ESPERADA = 34
        const val DIRECTORIO_EVIDENCIA = "p1-busqueda"
        const val ARGUMENTO_SALIDA_ADICIONAL = "additionalTestOutputDir"
        const val DESCONOCIDO = "desconocido"
    }
}
