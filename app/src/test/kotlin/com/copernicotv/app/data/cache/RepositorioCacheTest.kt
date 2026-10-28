package com.copernicotv.app.data.cache

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.cache.ClaveCache
import com.copernicotv.core.cache.Frescura
import com.copernicotv.core.cache.PresupuestoCache
import com.copernicotv.core.crypto.CifradoAes
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * Caché de transporte M3U/XMLTV (E2-T7).
 *
 * Cubre S51a (segunda lectura sin descarga), S51b (Actualizar reemplaza),
 * S51c (`expires_at`, frescura, fallo conserva la copia), S51d (archivos
 * opacos, invalidación, barrido y errores de borrado), S51e (frescura
 * separada del Estado de Actualización) y S51h (presupuesto y evicción).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RepositorioCacheTest {
    private lateinit var db: AppDatabase
    private lateinit var directorio: File
    private lateinit var almacen: AlmacenCache
    private lateinit var descargador: DescargadorContador
    private lateinit var repositorio: RepositorioCache
    private val claves = ClavesEnMemoria()

    private val t0: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        directorio = File(contexto.cacheDir, "cache-test-${UUID.randomUUID()}")
        almacen = AlmacenCache(directorio, claves)
        descargador = DescargadorContador()
        repositorio = RepositorioCache(db, almacen, descargador, claves)
    }

    @After
    fun cerrar() {
        db.close()
        directorio.deleteRecursively()
    }

    @Test
    fun `una segunda lectura dentro de la ventana no vuelve a descargar`() =
        runBlocking {
            val clave = claveM3U(listaId = 1)
            insertarLista(1)
            descargador.contenido = CONTENIDO_A

            val actualizacion = repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)

            assertTrue(actualizacion is ResultadoActualizacion.Reemplazada)
            assertEquals(1, descargador.descargas)
            assertArrayEquals(CONTENIDO_A, leerBytes(clave, t0.plusSeconds(60)))
            assertArrayEquals(CONTENIDO_A, leerBytes(clave, t0.plusSeconds(120)))
            assertEquals(1, descargador.descargas)
        }

    @Test
    fun `tras actualizar la lectura devuelve el contenido nuevo`() =
        runBlocking {
            val clave = claveM3U(listaId = 1)
            insertarLista(1)
            descargador.contenido = CONTENIDO_A
            repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)
            val primeraRef = unicaFila().archivoRef

            descargador.contenido = CONTENIDO_B
            val segunda = repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0.plusSeconds(600))

            assertTrue(segunda is ResultadoActualizacion.Reemplazada)
            assertArrayEquals(CONTENIDO_B, leerBytes(clave, t0.plusSeconds(700)))
            assertEquals(1L, db.cacheM3UDao().contar())
            assertFalse(almacen.existe(primeraRef))
            assertEquals(1, almacen.nombres().size)
        }

    @Test
    fun `expires_at es fetched_at mas el intervalo vigente`() =
        runBlocking {
            val clave = claveM3U(listaId = 1)
            insertarLista(1)

            repositorio.actualizar(clave, URL_M3U, Duration.ofMinutes(30), t0)

            val fila = unicaFila()
            assertEquals(t0, fila.fetchedAt)
            assertEquals(t0.plus(Duration.ofMinutes(30)), fila.expiresAt)
        }

    @Test
    fun `la frescura se evalua contra el expires_at almacenado`() =
        runBlocking {
            val clave = claveM3U(listaId = 1)
            insertarLista(1)
            repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)

            val dentro = repositorio.leer(clave, t0.plusSeconds(3599)) as ResultadoLecturaCache.Copia
            val vencida = repositorio.leer(clave, t0.plusSeconds(3601)) as ResultadoLecturaCache.Copia

            assertEquals(Frescura.VALIDA, dentro.frescura)
            assertEquals(Frescura.STALE, vencida.frescura)
            assertEquals(t0.plus(UNA_HORA), vencida.expiresAt)
            vencida.flujo.close()
            dentro.flujo.close()
        }

    @Test
    fun `sin copia previa un fallo es un error tipado`() =
        runBlocking {
            val clave = claveM3U(listaId = 1)
            insertarLista(1)
            descargador.fallo = "timeout"

            val resultado = repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)

            assertEquals(ResultadoActualizacion.Fallo("timeout", copiaConservada = false), resultado)
            assertEquals(ResultadoLecturaCache.SinCopia, repositorio.leer(clave, t0))
            assertEquals(0L, db.cacheM3UDao().contar())
        }

    @Test
    fun `un fallo conserva la ultima copia valida sin presentarla como exito`() =
        runBlocking {
            val clave = claveM3U(listaId = 1)
            insertarLista(1)
            descargador.contenido = CONTENIDO_A
            repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)
            val expiresAntes = unicaFila().expiresAt

            descargador.fallo = "http_5xx"
            val resultado = repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0.plusSeconds(3600))

            assertEquals(ResultadoActualizacion.Fallo("http_5xx", copiaConservada = true), resultado)
            assertEquals(expiresAntes, unicaFila().expiresAt)
            assertArrayEquals(CONTENIDO_A, leerBytes(clave, t0.plusSeconds(3600)))
        }

    @Test
    fun `el contenido se cifra y el nombre del archivo es opaco`() =
        runBlocking {
            val clave = claveM3U(listaId = 1)
            insertarLista(1)
            repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)

            val fila = unicaFila()
            assertTrue(fila.archivoRef.matches(Regex("[0-9a-f-]{36}\\.bin")))
            assertFalse(fila.archivoRef.contains("example"))
            val crudo = almacen.archivo(fila.archivoRef).readBytes()
            assertFalse(crudo.toString(Charsets.ISO_8859_1).contains("example.com"))
            assertFalse(crudo.toString(Charsets.ISO_8859_1).contains("PRIMERO"))
            assertFalse(fila.urlCifrada.contentEquals(URL_M3U.toByteArray()))
            assertEquals(
                CifradoAes.VERSION.toInt(),
                fila.urlCifrada[0].toInt(),
            )
        }

    @Test
    fun `cambiar la url invalida la copia anterior`() =
        runBlocking {
            insertarLista(1)
            val vieja = claveM3U(listaId = 1, urlHmac = HMAC_VIEJO)
            val nueva = claveM3U(listaId = 1, urlHmac = HMAC_NUEVO)
            descargador.contenido = CONTENIDO_A
            repositorio.actualizar(vieja, URL_M3U, UNA_HORA, t0)
            repositorio.actualizar(nueva, URL_M3U, UNA_HORA, t0.plusSeconds(10))
            val refVieja = db.cacheM3UDao().todas().first { it.urlHmac.contentEquals(bytes(HMAC_VIEJO)) }.archivoRef

            repositorio.invalidarOtrasUrls(nueva)

            val restantes = db.cacheM3UDao().todas()
            assertEquals(1, restantes.size)
            assertArrayEquals(bytes(HMAC_NUEVO), restantes.single().urlHmac)
            assertFalse(almacen.existe(refVieja))
        }

    @Test
    fun `invalidar por lista borra la fila y su archivo`() =
        runBlocking {
            insertarLista(1)
            val clave = claveM3U(listaId = 1)
            repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)
            val ref = unicaFila().archivoRef

            repositorio.invalidarPorLista(1)

            assertEquals(0L, db.cacheM3UDao().contar())
            assertFalse(almacen.existe(ref))
        }

    @Test
    fun `el barrido borra huerfanos vencidos y conserva los recientes`() =
        runBlocking {
            insertarLista(1)
            val clave = claveM3U(listaId = 1)
            repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)
            val referenciado = unicaFila().archivoRef
            val huerfanoViejo = almacen.nombreNuevo()
            almacen.archivo(huerfanoViejo).writeBytes(ByteArray(8))
            almacen.archivo(huerfanoViejo).setLastModified(t0.minus(Duration.ofHours(48)).toEpochMilli())
            val huerfanoNuevo = almacen.nombreNuevo()
            almacen.archivo(huerfanoNuevo).writeBytes(ByteArray(8))
            almacen.archivo(huerfanoNuevo).setLastModified(t0.toEpochMilli())

            val informe = repositorio.barrerHuerfanos(t0)

            assertEquals(1, informe.borrados)
            assertTrue(informe.errores.isEmpty())
            assertFalse(almacen.existe(huerfanoViejo))
            assertTrue(almacen.existe(huerfanoNuevo))
            assertTrue(almacen.existe(referenciado))
            assertEquals(1L, db.cacheM3UDao().contar())
        }

    @Test
    fun `el barrido registra el error de borrado`() =
        runBlocking {
            insertarLista(1)
            val huerfano = almacen.nombreNuevo()
            almacen.archivo(huerfano).writeBytes(ByteArray(8))
            almacen.archivo(huerfano).setLastModified(t0.minus(Duration.ofHours(48)).toEpochMilli())
            val conFallo =
                RepositorioCache(
                    db,
                    AlmacenCache(directorio, claves) { false },
                    descargador,
                    claves,
                )

            val informe = conFallo.barrerHuerfanos(t0)

            assertEquals(0, informe.borrados)
            assertEquals(listOf(huerfano), informe.errores)
            assertTrue(almacen.existe(huerfano))
        }

    @Test
    fun `el barrido purga filas cuyo archivo desaparecio`() =
        runBlocking {
            insertarLista(1)
            val clave = claveM3U(listaId = 1)
            repositorio.actualizar(clave, URL_M3U, UNA_HORA, t0)
            val ref = unicaFila().archivoRef
            assertTrue(almacen.borrar(ref))

            val informe = repositorio.barrerHuerfanos(t0)

            assertEquals(1, informe.filasSinArchivo)
            assertEquals(0L, db.cacheM3UDao().contar())
            assertEquals(ResultadoLecturaCache.SinCopia, repositorio.leer(clave, t0))
        }

    @Test
    fun `un archivo mayor que el limite por archivo no se almacena`() =
        runBlocking {
            insertarLista(1)
            val ajustado =
                RepositorioCache(
                    db,
                    almacen,
                    descargador,
                    claves,
                    PresupuestoCache(m3uPorArchivo = 50, m3uPorLista = 1_000, m3uTotal = 1_000, global = 10_000),
                )

            val resultado = ajustado.actualizar(claveM3U(listaId = 1), URL_M3U, UNA_HORA, t0)

            assertEquals(ResultadoActualizacion.Omitida("cache_omitida_por_tamano"), resultado)
            assertEquals(0L, db.cacheM3UDao().contar())
            assertTrue(almacen.nombres().isEmpty())
        }

    @Test
    fun `la eviccion prefiere la copia stale antes que la valida`() =
        runBlocking {
            insertarLista(1)
            insertarLista(2)
            insertarLista(3)
            val ajustado =
                RepositorioCache(
                    db,
                    almacen,
                    descargador,
                    claves,
                    PresupuestoCache(
                        m3uTotal = 140,
                        m3uPorLista = 140,
                        m3uPorArchivo = 140,
                        global = 10_000,
                        reservaLibre = 0,
                    ),
                )
            descargador.contenido = ByteArray(40) { 0x41 }
            ajustado.actualizar(claveM3U(listaId = 1), URL_M3U, UNA_HORA, t0)
            descargador.contenido = ByteArray(40) { 0x42 }
            ajustado.actualizar(claveM3U(listaId = 2), URL_M3U, UNA_HORA, t0.plusSeconds(7200))
            descargador.contenido = ByteArray(40) { 0x43 }

            val resultado =
                ajustado.actualizar(claveM3U(listaId = 3), URL_M3U, UNA_HORA, t0.plusSeconds(7200))

            assertTrue(resultado is ResultadoActualizacion.Reemplazada)
            val listas = db.cacheM3UDao().todas().map { it.listaId }.sorted()
            assertEquals(listOf(2L, 3L), listas)
        }

    @Test
    fun `una entrada en uso no se evicta y la escritura se rechaza`() =
        runBlocking {
            insertarLista(1)
            insertarLista(2)
            insertarLista(3)
            val ajustado =
                RepositorioCache(
                    db,
                    almacen,
                    descargador,
                    claves,
                    PresupuestoCache(
                        m3uTotal = 140,
                        m3uPorLista = 140,
                        m3uPorArchivo = 140,
                        global = 10_000,
                        reservaLibre = 0,
                    ),
                )
            descargador.contenido = ByteArray(40) { 0x41 }
            ajustado.actualizar(claveM3U(listaId = 1), URL_M3U, UNA_HORA, t0)
            descargador.contenido = ByteArray(40) { 0x42 }
            ajustado.actualizar(claveM3U(listaId = 2), URL_M3U, UNA_HORA, t0.plusSeconds(7200))
            val abierta = ajustado.leer(claveM3U(listaId = 1), t0.plusSeconds(7200)) as ResultadoLecturaCache.Copia

            descargador.contenido = ByteArray(60) { 0x43 }
            val resultado =
                ajustado.actualizar(claveM3U(listaId = 3), URL_M3U, UNA_HORA, t0.plusSeconds(7200))

            assertEquals(ResultadoActualizacion.Omitida("cache_sin_espacio"), resultado)
            assertEquals(2L, db.cacheM3UDao().contar())
            assertEquals(ByteArray(40) { 0x41 }.toList(), abierta.flujo.use { it.readBytes() }.toList())
        }

    @Test
    fun `la cache no escribe el estado de actualizacion`() =
        runBlocking {
            insertarLista(1)

            repositorio.actualizar(claveM3U(listaId = 1), URL_M3U, UNA_HORA, t0)
            repositorio.leer(claveM3U(listaId = 1), t0.plusSeconds(7200))

            assertEquals(0, db.estadoActualizacionDao().contar())
        }

    private suspend fun leerBytes(
        clave: ClaveCache,
        ahora: Instant,
    ): ByteArray {
        val copia = repositorio.leer(clave, ahora) as ResultadoLecturaCache.Copia
        return copia.flujo.use { it.readBytes() }
    }

    private suspend fun unicaFila() = requireNotNull(db.cacheM3UDao().todas().singleOrNull())

    private fun claveM3U(
        listaId: Long,
        urlHmac: String = HMAC_VIGENTE,
    ): ClaveCache.M3U = ClaveCache.M3U(fuenteId = FUENTE_ID, listaId = listaId, urlHmac = urlHmac)

    private suspend fun insertarLista(listaId: Long) {
        if (db.fuenteDao().porId(FUENTE_ID) == null) {
            db.fuenteDao().insertar(
                FuenteEntity(
                    id = FUENTE_ID,
                    nombre = "Fuente",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = t0,
                    actualizadoEn = t0,
                ),
            )
        }
        db.listaDao().insertar(
            ListaEntity(
                id = listaId,
                fuenteId = FUENTE_ID,
                nombre = "Lista $listaId",
                ubicacionCifrada = ByteArray(4),
                ubicacionHmac = ByteArray(4),
                esquema = "m3u",
                orden = 0,
                estado = "activa",
                creadoEn = t0,
                actualizadoEn = t0,
            ),
        )
    }

    private fun bytes(texto: String): ByteArray = texto.toByteArray(Charsets.UTF_8)

    private class DescargadorContador : Descargador {
        var descargas = 0
        var contenido: ByteArray = ByteArray(0)
        var fallo: String? = null

        override suspend fun descargar(
            url: String,
            destino: File,
        ): ResultadoDescarga {
            descargas++
            val codigo = fallo
            if (codigo != null) return ResultadoDescarga.Fallo(codigo)
            destino.writeBytes(contenido)
            return ResultadoDescarga.Ok(contenido.size.toLong())
        }
    }

    private companion object {
        const val FUENTE_ID = 100L
        const val URL_M3U = "https://example.com/lista.m3u"
        const val HMAC_VIGENTE = "hmac:v1:cv1:AAAA"
        const val HMAC_VIEJO = "hmac:v1:cv1:BBBB"
        const val HMAC_NUEVO = "hmac:v1:cv1:CCCC"

        val UNA_HORA: Duration = Duration.ofHours(1)
        val CONTENIDO_A: ByteArray = "PRIMERO: contenido viejo".toByteArray()
        val CONTENIDO_B: ByteArray = "SEGUNDO: contenido nuevo".toByteArray()
    }
}
