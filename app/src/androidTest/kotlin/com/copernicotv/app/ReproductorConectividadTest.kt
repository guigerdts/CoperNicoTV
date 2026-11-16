package com.copernicotv.app

import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.copernicotv.app.data.CatalogoRepositorio
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.player.EstadoReproductor
import com.copernicotv.app.player.EstadoSesion
import com.copernicotv.app.ui.TAG_REPRODUCTOR_ERROR
import com.copernicotv.app.ui.TAG_REPRODUCTOR_RECUPERADA
import com.copernicotv.app.ui.TAG_REPRODUCTOR_REINTENTO
import dagger.hilt.android.testing.HiltAndroidRule
import dagger.hilt.android.testing.HiltAndroidTest
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicBoolean
import javax.inject.Inject

/**
 * Conectividad y reposición por estabilidad de la recuperación (E7-T10, S4).
 *
 * La red la controla [ConectividadPrueba]: una desconexión NO consume
 * reintentos ni genera peticiones; la espera suspende a la recuperación en
 * ESPERANDO_CONEXION hasta que vuelva la red. Las esperas de backoff siguen
 * pasando por [PuertaReintentos] y la ventana de estabilidad por
 * [PuertaEstabilidad], así que todo el flujo es determinista sin dormir.
 */
@HiltAndroidTest
class ReproductorConectividadTest {
    @get:Rule(order = 0)
    val hiltRule = HiltAndroidRule(this)

    @get:Rule(order = 1)
    val composeRule = createAndroidComposeRule<MainActivity>()

    @Inject
    lateinit var db: AppDatabase

    @Inject
    lateinit var catalogo: CatalogoRepositorio

    private lateinit var servidor: ServidorMedios

    @Before
    fun preparar() {
        hiltRule.inject()
        runBlocking { db.clearAllTables() }
        FixturesF15.verificar(InstrumentationRegistry.getInstrumentation().context.assets)
        PuertaReintentos.reiniciar()
        PuertaEstabilidad.reiniciar()
        ConectividadPrueba.conectar()
    }

    @After
    fun cerrar() {
        if (::servidor.isInitialized) servidor.close()
    }

    @Test
    fun laPerdidaDeConectividadEsperaSinConsumirReintentos() {
        servidor = servidorConFallos { ruta, _ -> if (ruta == FixturesF15.MP4) 500 else null }
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))

        composeRule.abrirReproductor()

        esperarSesion("primer reintento") { it.estado == EstadoReproductor.REINTENTANDO && it.reintento == 1 }
        assertEquals(1, servidor.peticionesDe(FixturesF15.MP4))

        // Se corta la red antes de liberar la espera de backoff: la
        // re-comprobación posterior debe suspender en ESPERANDO_CONEXION.
        ConectividadPrueba.desconectar()
        PuertaReintentos.liberar()

        val enEspera = esperarSesion("esperando conexión") { it.estado == EstadoReproductor.ESPERANDO_CONEXION }
        assertEquals(1, enEspera.reintento)
        assertEquals(1, PuertaReintentos.esperasRegistradas)
        assertEquals(1, servidor.peticionesDe(FixturesF15.MP4))
        composeRule.esperarTag(TAG_REPRODUCTOR_REINTENTO)

        // Vuelve la red: la recuperación continúa sin repetir el backoff y
        // prepara una vez; el nuevo fallo abre el reintento 2.
        ConectividadPrueba.conectar()

        esperarSesion("reintento 2 tras reconectar") {
            it.estado == EstadoReproductor.REINTENTANDO && it.reintento == 2
        }
        assertEquals(2, servidor.peticionesDe(FixturesF15.MP4))
        composeRule.waitUntil(ESPERA_TEST_MS) { PuertaReintentos.esperasRegistradas == 2 }
    }

    @Test
    fun laFluctuacionRapidaDuranteLaEsperaGeneraUnSoloPrepare() {
        servidor = servidorConFallos { ruta, _ -> if (ruta == FixturesF15.MP4) 500 else null }
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))

        composeRule.abrirReproductor()

        esperarSesion("primer reintento") { it.estado == EstadoReproductor.REINTENTANDO && it.reintento == 1 }

        // Fluctuación completa durante la espera de backoff: el último estado
        // es desconectado y solo él debe decidir el rumbo.
        ConectividadPrueba.desconectar()
        ConectividadPrueba.conectar()
        ConectividadPrueba.desconectar()
        ConectividadPrueba.conectar()
        ConectividadPrueba.desconectar()
        PuertaReintentos.liberar()

        esperarSesion("esperando conexión tras la fluctuación") { it.estado == EstadoReproductor.ESPERANDO_CONEXION }
        assertEquals(1, servidor.peticionesDe(FixturesF15.MP4))
        assertEquals(1, PuertaReintentos.esperasRegistradas)

        ConectividadPrueba.conectar()

        esperarSesion("reintento 2 tras la fluctuación") {
            it.estado == EstadoReproductor.REINTENTANDO && it.reintento == 2
        }
        // Un solo prepare para toda la fluctuación: ninguna de las vueltas
        // rápidas disparó una petición extra.
        assertEquals(2, servidor.peticionesDe(FixturesF15.MP4))
    }

    @Test
    fun laRecuperacionTrasEstabilidadReponeElPresupuesto() {
        val fallar = AtomicBoolean(true)
        servidor =
            servidorConFallos { ruta, _ ->
                if (ruta == FixturesF15.MP4 && fallar.get()) 500 else null
            }
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))

        composeRule.abrirReproductor()

        repeat(5) { indice ->
            val numero = indice + 1
            esperarSesion("reintento $numero antes del éxito") {
                it.estado == EstadoReproductor.REINTENTANDO && it.reintento == numero
            }
            PuertaReintentos.liberar()
        }
        fallar.set(false)
        PuertaReintentos.liberar()

        esperarSesion("recuperación con evidencia estable") {
            it.estado == EstadoReproductor.REPRODUCIENDO && it.recuperadoRecientemente
        }
        composeRule.esperarTag(TAG_REPRODUCTOR_RECUPERADA)
        assertEquals(1, PuertaEstabilidad.esperasRegistradas)
        // Cinco esperas de backoff consumidas: cada fallo espera; el sexto
        // prepare es el que acierta y no cruza la puerta.
        assertEquals(5, PuertaReintentos.esperasRegistradas)

        // La estabilidad se completa: repone el presupuesto y retira el aviso.
        PuertaEstabilidad.liberar()
        composeRule.esperarTagAusente(TAG_REPRODUCTOR_RECUPERADA)

        // Un nuevo corte transitorio vuelve a tener presupuesto completo: el
        // reintento vuelve a ser 1, no un error definitivo por agotamiento.
        // El MP4 ya está en búfer y no vuelve a pedir la red por su cuenta, y
        // `prepare()` en READY es no-op; el fallo se dispara igual que en la
        // recuperación real: el error deja el motor en IDLE y `prepare()`
        // re-abre la fuente, así que aquí se pasa por `stop()` primero.
        fallar.set(true)
        composeRule.runOnUiThread {
            composeRule.viewModelReproductor().sesionActual()?.reproductor?.let { motor ->
                motor.stop()
                motor.prepare()
            }
        }
        val reintento =
            esperarSesion("reintento 1 con presupuesto repuesto") {
                it.estado == EstadoReproductor.REINTENTANDO && it.reintento == 1
            }
        assertEquals(6, reintento.reintentosMaximos)

        fallar.set(false)
        PuertaReintentos.liberar()
        esperarSesion("reproducción final") {
            it.estado == EstadoReproductor.REPRODUCIENDO || it.estado == EstadoReproductor.PAUSADO
        }
    }

    @Test
    fun un403NoSeReintentaYTerminaEnErrorDefinitivo() {
        servidor = servidorConFallos { ruta, _ -> if (ruta == FixturesF15.MP4) 403 else null }
        sembrarCanal(catalogo, FixturesF15.url(servidor, FixturesF15.MP4))

        composeRule.abrirPantallaReproductor()

        composeRule.esperarTag(TAG_REPRODUCTOR_ERROR)
        val texto = composeRule.textoDeTag(TAG_REPRODUCTOR_ERROR)
        assertTrue("El error debe tipar el 4xx: $texto", texto.contains("http_cliente"))
        assertEquals(0, PuertaReintentos.esperasRegistradas)
        assertEquals(0, PuertaEstabilidad.esperasRegistradas)
        // Una sola petición: un 403 no mejora con reintentos (S4).
        assertEquals(1, servidor.peticionesDe(FixturesF15.MP4))
    }

    private fun servidorConFallos(decidir: (ruta: String, peticion: Int) -> Int?): ServidorMedios =
        ServidorMedios(InstrumentationRegistry.getInstrumentation().context.assets, decidirFallo = decidir).also { it.iniciar() }

    private fun esperarSesion(
        descripcion: String,
        predicado: (EstadoSesion) -> Boolean,
    ): EstadoSesion =
        try {
            runBlocking {
                withTimeout(ESPERA_TEST_MS) {
                    composeRule.viewModelReproductor().sesionEstado.first(predicado)
                }
            }
        } catch (error: TimeoutCancellationException) {
            throw AssertionError(
                "No llegó $descripcion en $ESPERA_TEST_MS ms. estado=${composeRule.viewModelReproductor().sesionEstado.value}",
                error,
            )
        }
}
