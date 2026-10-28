package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.HmacPendiente
import com.copernicotv.core.hmac.MotivoHmacPendiente
import com.copernicotv.core.hmac.MotivoTransicion
import com.copernicotv.core.hmac.RechazoIndiceHmac
import com.copernicotv.core.hmac.ResultadoTransicion
import com.copernicotv.core.hmac.VersionOrigenHmac
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Persistencia del índice HMAC (E2-T6).
 *
 * Verifica que cada transición de [com.copernicotv.core.hmac.MaquinaIndiceHmac]
 * se persiste tal como la decide `:core`, que una transición rechazada no escribe
 * nada y que el estado sobrevive a una nueva instancia del repositorio.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndiceHmacRepositorioTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: IndiceHmacRepositorio

    private val ahora: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repositorio = IndiceHmacRepositorio(db)
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `el estado inicial es operativo con hmac v1 y sin filas`() =
        runBlocking {
            val estado = repositorio.estado(ahora)

            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals("hmac:v1", estado.versionActiva)
            assertNull(estado.versionObjetivo)
            assertEquals(0L, estado.filasTotales)
            assertEquals(0L, estado.filasPendientes)
            assertEquals(1, db.estadoIndiceHmacDao().contar())
        }

    @Test
    fun `detectar la perdida de clave persiste recuperacion pendiente`() =
        runBlocking {
            val resultado = repositorio.detectarPerdidaClave(ahora)

            assertTrue(resultado is ResultadoTransicion.Ok)
            assertEquals(FaseIndiceHmac.RECUPERACION_PENDIENTE, repositorio.estado(ahora).fase)
        }

    @Test
    fun `iniciar la reconstruccion persiste la generacion objetivo`() =
        runBlocking {
            repositorio.detectarPerdidaClave(ahora)

            val resultado = repositorio.iniciarReconstruccion(2, ahora)

            assertTrue(resultado is ResultadoTransicion.Ok)
            val estado = repositorio.estado(ahora)
            assertEquals(FaseIndiceHmac.RECUPERACION_EN_CURSO, estado.fase)
            assertEquals("hmac:v1", estado.versionActiva)
            assertEquals("hmac:v2", estado.versionObjetivo)
        }

    @Test
    fun `un avance con pendientes deja el indice bloqueado parcial`() =
        runBlocking {
            enReconstruccion()

            val resultado = repositorio.registrarAvance(10, 7, 3, ahora)

            assertTrue(resultado is ResultadoTransicion.Ok)
            val estado = repositorio.estado(ahora)
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estado.fase)
            assertEquals(10L, estado.filasTotales)
            assertEquals(7L, estado.filasReconstruidas)
            assertEquals(3L, estado.filasPendientes)
        }

    @Test
    fun `un avance completo cierra la reconstruccion en operativo con la generacion objetivo`() =
        runBlocking {
            enReconstruccion()

            val resultado = repositorio.registrarAvance(10, 10, 0, ahora)

            assertTrue(resultado is ResultadoTransicion.Ok)
            val estado = repositorio.estado(ahora)
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals("hmac:v2", estado.versionActiva)
            assertNull(estado.versionObjetivo)
        }

    @Test
    fun `resolver pendientes con filas pendientes se rechaza y no persiste`() =
        runBlocking {
            enReconstruccion()
            repositorio.registrarAvance(10, 7, 3, ahora)

            val resultado = repositorio.resolverPendientes(10, 7, 3, ahora)

            assertEquals(
                ResultadoTransicion.Rechazada(MotivoTransicion.FILAS_PENDIENTES_NO_RESUELTAS),
                resultado,
            )
            val estado = repositorio.estado(ahora)
            assertEquals(FaseIndiceHmac.BLOQUEADO_PARCIAL, estado.fase)
            assertEquals(3L, estado.filasPendientes)
        }

    @Test
    fun `resolver pendientes sin filas pendientes vuelve a operativo`() =
        runBlocking {
            enReconstruccion()
            repositorio.registrarAvance(10, 7, 3, ahora)

            val resultado = repositorio.resolverPendientes(10, 10, 0, ahora)

            assertTrue(resultado is ResultadoTransicion.Ok)
            val estado = repositorio.estado(ahora)
            assertEquals(FaseIndiceHmac.OPERATIVO, estado.fase)
            assertEquals("hmac:v2", estado.versionActiva)
        }

    @Test
    fun `el bloqueo de igualdad solo es nulo en operativo`() =
        runBlocking {
            assertNull(repositorio.bloqueoIgualdad(ahora))

            repositorio.detectarPerdidaClave(ahora)
            val bloqueo = repositorio.bloqueoIgualdad(ahora)

            assertNotNull(bloqueo)
            assertEquals(RechazoIndiceHmac.CODIGO, bloqueo?.codigo)
        }

    @Test
    fun `registrar una fila pendiente dos veces no duplica y actualiza el motivo`() =
        runBlocking {
            val primera =
                HmacPendiente(
                    entidad = "Canal",
                    entidadId = 7,
                    columna = "url_stream_hmac",
                    versionOrigen = VersionOrigenHmac.Cv(1),
                    motivo = MotivoHmacPendiente.CIPHERTEXT_CORRUPTO,
                )
            repositorio.registrarPendiente(primera, ahora)

            val segunda = primera.copy(motivo = MotivoHmacPendiente.CLAVE_AES_NO_DISPONIBLE)
            repositorio.registrarPendiente(segunda, ahora.plusSeconds(60))

            val pendientes = repositorio.pendientes()
            assertEquals(1, pendientes.size)
            assertEquals(MotivoHmacPendiente.CLAVE_AES_NO_DISPONIBLE, pendientes.single().motivo)
            assertEquals(1L, repositorio.contarPendientes())
        }

    @Test
    fun `una transicion rechazada no modifica el estado persistido`() =
        runBlocking {
            val resultado = repositorio.iniciarReconstruccion(2, ahora)

            assertEquals(
                ResultadoTransicion.Rechazada(MotivoTransicion.TRANSICION_NO_PERMITIDA),
                resultado,
            )
            assertEquals(FaseIndiceHmac.OPERATIVO, repositorio.estado(ahora).fase)
        }

    @Test
    fun `el estado persiste entre instancias del repositorio`() =
        runBlocking {
            repositorio.detectarPerdidaClave(ahora)
            repositorio.iniciarReconstruccion(2, ahora)

            val otraInstancia = IndiceHmacRepositorio(db)
            val estado = otraInstancia.estado(ahora)

            assertEquals(FaseIndiceHmac.RECUPERACION_EN_CURSO, estado.fase)
            assertEquals("hmac:v2", estado.versionObjetivo)
        }

    private suspend fun enReconstruccion() {
        repositorio.detectarPerdidaClave(ahora)
        repositorio.iniciarReconstruccion(2, ahora)
    }
}
