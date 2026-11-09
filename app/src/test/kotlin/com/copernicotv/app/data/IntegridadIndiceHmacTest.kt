package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.core.crypto.CifradoAes
import com.copernicotv.core.crypto.HmacUrl
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.identity.CampoUrl
import com.copernicotv.core.identity.CanonicalizadorUrl
import com.copernicotv.core.identity.ResultadoCanonizacion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Regresión de los dos defectos del PR #22 (DC-04 §7, E2-T6).
 *
 * - Defecto 1: `bloqueoIgualdad()` solo miraba la fase; con la fila en
 *   `operativo` y la clave HMAC borrada permitía comparar. Caso A.
 * - Defecto 2: la guardia de arranque leía fila y clave en pasos separados, y
 *   una base virgen podía quedar `operativo` sin clave (o declararse pérdida
 *   falsamente). Casos B, C y D.
 *
 * La clave se borra con el doble de pruebas ([ClavesEnMemoria.borrarClaveHmac]),
 * que reproduce la semántica de Keystore: una lectura nunca la regenera.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IntegridadIndiceHmacTest {
    private lateinit var db: AppDatabase
    private lateinit var claves: ClavesEnMemoria
    private lateinit var indice: IndiceHmacRepositorio
    private lateinit var guardia: GuardiaArranqueIndiceHmac

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        claves = ClavesEnMemoria()
        indice = IndiceHmacRepositorio(db, claves)
        guardia = GuardiaArranqueIndiceHmac(indice)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `caso A perdida con indice operativo bloquea igualdad sin regenerar`() =
        runBlocking {
            assertEquals(FaseIndiceHmac.OPERATIVO, indice.estado(ahora).fase)
            val canalId = sembrarCanal(URL_CANAL)
            val antes = requireNotNull(db.canalDao().porId(canalId))
            val cifradoAntes = antes.urlStreamCifrada.copyOf()
            val sobreAntes = antes.urlStreamHmac.copyOf()

            claves.borrarClaveHmac(1)
            assertFalse(claves.contieneClaveHmac(1))

            val bloqueo = indice.bloqueoIgualdad(ahora)

            assertNotNull("la igualdad debe bloquearse al perder la clave", bloqueo)
            assertEquals(RechazoIndiceHmac.CODIGO, bloqueo?.codigo)
            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, indice.estado(ahora).fase)
            assertFalse("no debe regenerarse la clave", claves.contieneClaveHmac(1))
            assertNull(claves.claveHmac(1))
            val despues = requireNotNull(db.canalDao().porId(canalId))
            assertArrayEquals("el ciphertext no cambia", cifradoAntes, despues.urlStreamCifrada)
            assertArrayEquals("el sobre HMAC no cambia", sobreAntes, despues.urlStreamHmac)
        }

    @Test
    fun `caso B virgen con inicializacion guardia e igualdad concurrentes`() =
        runBlocking {
            val barrera = CyclicBarrier(3)
            val resultado =
                runBlocking(Dispatchers.IO) {
                    val inicializacion =
                        async(Dispatchers.IO) {
                            barrera.await(30, TimeUnit.SECONDS)
                            indice.inicializarLegitimamente(ahora)
                        }
                    val arranque =
                        async(Dispatchers.IO) {
                            barrera.await(30, TimeUnit.SECONDS)
                            guardia.evaluar(ahora)
                        }
                    val igualdad =
                        async(Dispatchers.IO) {
                            barrera.await(30, TimeUnit.SECONDS)
                            indice.bloqueoIgualdad(ahora)
                        }
                    Triple(inicializacion.await(), arranque.await(), igualdad.await())
                }

            val (estadoInicial, resultadoGuardia, bloqueo) = resultado
            assertEquals("una sola fila de estado", 1, db.estadoIndiceHmacDao().contar())
            assertEquals(FaseIndiceHmac.OPERATIVO, estadoInicial.fase)
            assertEquals("hmac:v1", estadoInicial.versionActiva)
            assertEquals(FaseIndiceHmac.OPERATIVO, indice.estado(ahora).fase)
            assertTrue(claves.contieneClaveHmac(1))
            assertEquals("una única materialización de la clave", 1, claves.creacionesHmac(1))
            assertNull("la instalación virgen no puede bloquear la igualdad", bloqueo)
            assertTrue(
                "la guardia debe ver la instalación válida: $resultadoGuardia",
                resultadoGuardia == GuardiaArranqueIndiceHmac.ResultadoArranque.Inicializado ||
                    resultadoGuardia == GuardiaArranqueIndiceHmac.ResultadoArranque.Operativo,
            )
        }

    @Test
    fun `caso C perdida de clave durante la concurrencia no permite igualdad`() =
        runBlocking {
            assertEquals(FaseIndiceHmac.OPERATIVO, indice.estado(ahora).fase)
            val canalId = sembrarCanal(URL_CANAL)
            val antes = requireNotNull(db.canalDao().porId(canalId))
            val cifradoAntes = antes.urlStreamCifrada.copyOf()
            val sobreAntes = antes.urlStreamHmac.copyOf()

            // Punto controlado por la prueba: la clave desaparece antes de que
            // las operaciones concurrentes entren a la sección crítica.
            claves.borrarClaveHmac(1)

            val bloqueos =
                runBlocking(Dispatchers.IO) {
                    val barrera = CyclicBarrier(12)
                    (1..12)
                        .map {
                            async(Dispatchers.IO) {
                                barrera.await(30, TimeUnit.SECONDS)
                                indice.bloqueoIgualdad(ahora)
                            }
                        }.awaitAll()
                }

            assertTrue(
                "ninguna operación puede permitir igualdad tras la pérdida",
                bloqueos.all { it != null },
            )
            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, indice.estado(ahora).fase)
            assertEquals("no se pierden ni duplican actualizaciones", 1, db.estadoIndiceHmacDao().contar())
            assertFalse("la clave no reaparece", claves.contieneClaveHmac(1))
            val despues = requireNotNull(db.canalDao().porId(canalId))
            assertArrayEquals(cifradoAntes, despues.urlStreamCifrada)
            assertArrayEquals(sobreAntes, despues.urlStreamHmac)
        }

    @Test
    fun `caso D la primera inicializacion crea la clave y una fila con sobres no`() =
        runBlocking {
            val primera = guardia.evaluar(ahora)

            assertEquals(GuardiaArranqueIndiceHmac.ResultadoArranque.Inicializado, primera)
            assertEquals(FaseIndiceHmac.OPERATIVO, indice.estado(ahora).fase)
            assertTrue(claves.contieneClaveHmac(1))
            assertEquals(1, db.estadoIndiceHmacDao().contar())

            val canalId = sembrarCanal(URL_CANAL)
            claves.borrarClaveHmac(1)

            val segunda = guardia.evaluar(ahora)

            assertEquals(GuardiaArranqueIndiceHmac.ResultadoArranque.PerdidaDetectada, segunda)
            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, indice.estado(ahora).fase)
            assertFalse(
                "una fila con sobres nunca se autocorrige creando una clave nueva",
                claves.contieneClaveHmac(1),
            )
            assertNull(claves.claveHmac(1))
            assertNotNull("los datos se preservan", db.canalDao().porId(canalId))
        }

    @Test
    fun `inicializar legitimamente no crea clave cuando hay sobres y la clave falta`() =
        runBlocking {
            indice.estado(ahora)
            sembrarCanal(URL_CANAL)
            claves.borrarClaveHmac(1)

            val estado = indice.inicializarLegitimamente(ahora)

            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, estado.fase)
            assertFalse(claves.contieneClaveHmac(1))
        }

    @Test
    fun `una base sin datos inicializa la clave al comprobar igualdad`() =
        runBlocking {
            assertNull(indice.bloqueoIgualdad(ahora))

            assertEquals(FaseIndiceHmac.OPERATIVO, indice.estado(ahora).fase)
            assertTrue(claves.contieneClaveHmac(1))
            assertEquals(1, db.estadoIndiceHmacDao().contar())
        }

    /** Siembra un canal con su ciphertext AES-GCM y su sobre HMAC `v1`. */
    private suspend fun sembrarCanal(url: String): Long {
        val fuenteId =
            db
                .fuenteDao()
                .insertar(
                    FuenteEntity(
                        nombre = "Fuente",
                        estado = "activa",
                        usarIntervaloGlobal = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
        return db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = "Canal",
                urlStreamCifrada =
                    CifradoAes.cifrar(
                        claves.claveAes,
                        CifradoAes.aad("Canal", "url_stream_cifrada"),
                        url.toByteArray(),
                    ),
                urlStreamHmac = sobreV1(url),
                claveDerivable = true,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    }

    private fun sobreV1(url: String): ByteArray {
        val canonica = CanonicalizadorUrl.canonizar(url, CampoUrl.CANAL_URL_STREAM) as ResultadoCanonizacion.Ok
        return HmacUrl
            .calcularSobre(
                clave = claves.crearClaveHmac(1),
                generacion = 1,
                campo = CampoUrl.CANAL_URL_STREAM,
                clase = canonica.clase,
                urlCanonica = canonica.urlCanonica,
            ).toByteArray(Charsets.UTF_8)
    }

    private companion object {
        const val URL_CANAL = "https://example.com/canal.m3u8"
    }
}
