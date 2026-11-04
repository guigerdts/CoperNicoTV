package com.copernicotv.app.data.actualizacion

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.ResumenActualizacion
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.security.GeneralSecurityException
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger

/**
 * DC-07 puntos 6 y 7: exclusión por Fuente, clasificación de errores y tope de
 * reintentos, probados sin WorkManager.
 *
 * El coordinador recibe la base real (Room en memoria) y un `ActualizadorLista`
 * de prueba, así que la prueba cubre la consulta de Listas y la agregación de
 * resultados, que es donde se decide si el trabajo se reintenta o se cierra.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CoordinadorActualizacionTest {
    private lateinit var db: AppDatabase
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val reclamo = ReclamoEnMemoria()
    private var fuenteId: Long = 0L
    private val listas = mutableListOf<Long>()

    @Before
    fun abrir() {
        contexto.deleteDatabase(NOMBRE_BD)
        db =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                .allowMainThreadQueries()
                .build()
        runBlocking { sembrar() }
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `un exito no deja reintentos y libera el reclamo`() {
        runBlocking {
            val coordinador = coordinador { listaId, _ -> exito(listaId) }

            val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.MANUAL, 0)

            assertTrue("Se esperaba Exito", resultado is ResultadoEjecucion.Exito)
            assertEquals("Una entrada por Lista", listas.size, (resultado as ResultadoEjecucion.Exito).resumenes.size)
            assertEquals(DecisionEjecucion.EXITO, decisionDe(resultado))
            assertFalse("El reclamo no puede quedar tomado", reclamo.tomado(fuenteId))
        }
    }

    @Test
    fun `un error transitorio reintenta mientras queden intentos`() {
        runBlocking {
            val coordinador = coordinador { listaId, _ -> fallo(listaId, ERROR_TRANSITORIO) }

            val primero = coordinador.ejecutar(fuenteId, CausasActualizacion.AUTOMATICA, 0)
            val ultimo = coordinador.ejecutar(fuenteId, CausasActualizacion.AUTOMATICA, 2)

            assertTrue("Con intentos disponibles se reintenta", primero is ResultadoEjecucion.Reintentar)
            assertTrue("Todavía queda un reintento", ultimo is ResultadoEjecucion.Reintentar)
            assertEquals(DecisionEjecucion.REINTENTAR, decisionDe(primero))
        }
    }

    @Test
    fun `un error transitorio se cierra al agotar los tres reintentos`() {
        runBlocking {
            val coordinador = coordinador { listaId, _ -> fallo(listaId, ERROR_TRANSITORIO) }

            val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.AUTOMATICA, 3)

            assertTrue("Sin intentos restantes es Fallo", resultado is ResultadoEjecucion.Fallo)
            assertEquals(ERROR_TRANSITORIO, (resultado as ResultadoEjecucion.Fallo).motivo)
            assertEquals(DecisionEjecucion.FALLAR, decisionDe(resultado))
        }
    }

    @Test
    fun `un error permanente no se reintenta`() {
        runBlocking {
            val coordinador = coordinador { listaId, _ -> fallo(listaId, "ubicacion_invalida") }

            val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.AUTOMATICA, 0)

            assertTrue("Un error permanente cierra el ciclo", resultado is ResultadoEjecucion.Fallo)
        }
    }

    @Test
    fun `las credenciales no disponibles cierran el ciclo sin reintentar`() {
        runBlocking {
            val coordinador =
                coordinador { listaId, _ ->
                    if (listaId == listas.first()) {
                        throw GeneralSecurityException("clave")
                    }
                    exito(listaId)
                }

            val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.AUTOMATICA, 0)

            assertTrue("Las credenciales no se resuelven reintentando", resultado is ResultadoEjecucion.Fallo)
            val resumenes = (resultado as ResultadoEjecucion.Fallo).resumenes
            assertEquals("Todas las Listas dejan su resumen", listas.size, resumenes.size)
            assertEquals("credenciales_no_disponibles", resumenes.first { it.listaId == listas.first() }.error)
        }
    }

    @Test
    fun `un fallo de una lista no cancela las demas`() {
        runBlocking {
            val coordinador =
                coordinador { listaId, _ ->
                    if (listaId == listas.first()) {
                        fallo(listaId, ERROR_TRANSITORIO)
                    } else {
                        exito(listaId)
                    }
                }

            val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.AUTOMATICA, 0)

            val resumenes = (resultado as ResultadoEjecucion.Reintentar).resumenes
            assertEquals("Cada Lista se procesó", listas.size, resumenes.size)
            assertEquals("fallida", resumenes.first { it.listaId == listas.first() }.resultado)
            assertEquals("completada", resumenes.first { it.listaId == listas.last() }.resultado)
        }
    }

    @Test
    fun `una fuente ya reclamada se coalesce y no se actualiza`() {
        runBlocking {
            val llamadas = AtomicInteger()
            reclamo.tomar(fuenteId)
            val coordinador =
                coordinador { listaId, _ ->
                    llamadas.incrementAndGet()
                    exito(listaId)
                }

            val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.MANUAL, 0)

            assertEquals(ResultadoEjecucion.Coalescido, resultado)
            assertEquals("No se actualizó ninguna Lista", 0, llamadas.get())
            assertEquals(DecisionEjecucion.EXITO, decisionDe(resultado))
            assertTrue("El reclamo ajeno no se libera", reclamo.tomado(fuenteId))
        }
    }

    @Test
    fun `una fuente sin listas cierra el ciclo con causa`() {
        runBlocking {
            db.listaDao().eliminar(listas.first())
            db.listaDao().eliminar(listas.last())
            val coordinador = coordinador { listaId, _ -> exito(listaId) }

            val resultado = coordinador.ejecutar(fuenteId, CausasActualizacion.AUTOMATICA, 0)

            assertTrue("Sin Listas no hay nada que reintentar", resultado is ResultadoEjecucion.Fallo)
            assertEquals("sin_listas", (resultado as ResultadoEjecucion.Fallo).motivo)
            assertFalse("El reclamo se libera", reclamo.tomado(fuenteId))
        }
    }

    private fun coordinador(actualizador: suspend (Long, String) -> ResumenActualizacion): CoordinadorActualizacion =
        CoordinadorActualizacion(
            db = db,
            actualizador = ActualizadorLista { listaId, causa -> actualizador(listaId, causa) },
            reclamo = reclamo,
        )

    private suspend fun sembrar() {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        repeat(2) { indice ->
            listas +=
                db.listaDao().insertar(
                    ListaEntity(
                        fuenteId = fuenteId,
                        nombre = "Lista $indice",
                        ubicacionCifrada = ByteArray(0),
                        ubicacionHmac = ByteArray(32),
                        esquema = "content",
                        orden = indice,
                        estado = "activa",
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    ),
                )
        }
    }

    private fun exito(listaId: Long): ResumenActualizacion = ResumenActualizacion(listaId = listaId, resultado = "completada", nuevas = 1)

    private fun fallo(
        listaId: Long,
        codigo: String,
    ): ResumenActualizacion = ResumenActualizacion(listaId = listaId, resultado = "fallida", error = codigo)

    private class ReclamoEnMemoria : ReclamoFuente {
        private val tomadas = mutableSetOf<Long>()

        override suspend fun reclamar(fuenteId: Long): Boolean = tomadas.add(fuenteId)

        override suspend fun liberar(fuenteId: Long) {
            tomadas.remove(fuenteId)
        }

        override suspend fun enCurso(): Map<Long, Instant> = tomadas.associateWith { Instant.now() }

        override suspend fun liberarHuerfano(fuenteId: Long) {
            tomadas.remove(fuenteId)
        }

        fun tomar(fuenteId: Long) {
            tomadas.add(fuenteId)
        }

        fun tomado(fuenteId: Long): Boolean = fuenteId in tomadas
    }

    private companion object {
        const val NOMBRE_BD = "coordinador-test.db"
        const val ERROR_TRANSITORIO = "archivo_inaccesible"
    }
}
