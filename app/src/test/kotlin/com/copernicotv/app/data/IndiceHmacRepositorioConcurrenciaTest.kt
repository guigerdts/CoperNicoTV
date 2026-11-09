package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.hmac.FaseIndiceHmac
import com.copernicotv.core.hmac.ResultadoTransicion
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant
import java.util.concurrent.CyclicBarrier
import java.util.concurrent.TimeUnit

/**
 * Concurrencia del estado del índice HMAC (E2-T6, deuda de la revisión).
 *
 * Las operaciones se lanzan realmente en paralelo con una barrera: no son dos
 * llamadas secuenciales disfrazadas. Cada prueba fija una invariante que se
 * rompe si `IndiceHmacRepositorio` deja de serializar lectura, validación y
 * escritura bajo el mismo `Mutex`:
 *
 * - [`las transiciones concurrentes no pierden actualizaciones`] falla con
 *   escrituras ciegas que pisan el avance mayor con uno menor.
 * - [`las transiciones incompatibles concurrentes dejan un solo ganador`]
 *   falla si dos transiciones incompatibles leen el mismo estado y ambas
 *   escriben.
 * - [`la inicializacion concurrente deja una sola fila`] falla si varias
 *   corrutinas leen la fila ausente y cada una escribe la suya.
 * - [`el sello concurrente en una base virgen no simula una perdida`] reproduce
 *   la carrera real: una corrutina crea la fila, otra la lee antes de que
 *   exista la clave y la interpreta como pérdida.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndiceHmacRepositorioConcurrenciaTest {
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val base: Instant = Instant.parse("2026-01-01T00:00:00Z")

    @Test
    fun `las transiciones concurrentes no pierden actualizaciones`() =
        runBlocking {
            val db = abrir()
            try {
                val indice = IndiceHmacRepositorio(db, ClavesEnMemoria())
                indice.inicializarLegitimamente(base)
                indice.detectarPerdidaClave(base.plusSeconds(1))
                indice.iniciarReconstruccion(2, base.plusSeconds(2))

                val avances = (1L..11L).map { it * 8 } + 100L
                val resultados =
                    enParalelo(avances.size) { i ->
                        indice.registrarAvance(
                            filasTotales = 100,
                            filasReconstruidas = avances[i],
                            filasPendientes = 0,
                            ahora = base.plusSeconds(3 + i),
                        )
                    }

                assertTrue(
                    "al menos un avance debe aceptarse",
                    resultados.any { it is ResultadoTransicion.Ok },
                )
                val final = indice.estado(base.plusSeconds(100))
                assertEquals(
                    "el avance mayor no puede perderse ante escrituras concurrentes",
                    100L,
                    final.filasReconstruidas,
                )
                assertEquals(0L, final.filasPendientes)
                assertEquals(FaseIndiceHmac.OPERATIVO, final.fase)
                assertEquals("hmac:v2", final.versionActiva)
            } finally {
                db.close()
            }
        }

    @Test
    fun `las transiciones incompatibles concurrentes dejan un solo ganador`() =
        runBlocking {
            val db = abrir()
            try {
                val indice = IndiceHmacRepositorio(db, ClavesEnMemoria())
                indice.inicializarLegitimamente(base)
                indice.detectarPerdidaClave(base.plusSeconds(1))

                val resultados =
                    enParalelo(2) { i ->
                        if (i == 0) {
                            indice.iniciarReconstruccion(2, base.plusSeconds(2))
                        } else {
                            indice.claveOriginalRecuperada(0, base.plusSeconds(2))
                        }
                    }

                assertEquals(
                    "exactamente una transición incompatible puede aceptarse",
                    1,
                    resultados.count { it is ResultadoTransicion.Ok },
                )
                assertEquals(1, resultados.count { it is ResultadoTransicion.Rechazada })

                val final = indice.estado(base.plusSeconds(3))
                val ganoReconstruccion = final.fase == FaseIndiceHmac.RECUPERACION_EN_CURSO
                val ganoRecuperacion = final.fase == FaseIndiceHmac.OPERATIVO
                assertTrue(
                    "el estado final debe corresponder al ganador: ${final.fase}",
                    ganoReconstruccion || ganoRecuperacion,
                )
            } finally {
                db.close()
            }
        }

    @Test
    fun `la inicializacion concurrente deja una sola fila`() =
        runBlocking {
            val db = abrir()
            try {
                val indice = IndiceHmacRepositorio(db, ClavesEnMemoria())
                val estados =
                    enParalelo(12) { i ->
                        indice.estado(base.plusSeconds(i.toLong()))
                    }

                estados.forEach { estado ->
                    assertEquals(
                        "todas las corrutinas deben observar la misma fila inicial",
                        estados.first(),
                        estado,
                    )
                }
                assertEquals(1, db.estadoIndiceHmacDao().contar())
            } finally {
                db.close()
            }
        }

    @Test
    fun `el sello concurrente en una base virgen no simula una perdida`() =
        runBlocking {
            val db = abrir()
            try {
                val indice = IndiceHmacRepositorio(db, ClavesEnMemoria())
                val sellos =
                    enParalelo(12) { i ->
                        indice.selloHmac(base.plusSeconds(i.toLong()))
                    }

                val disponibles =
                    sellos.map { sello ->
                        requireNotNull(sello as? SelloIndiceHmac.Disponible) {
                            "la primera inicialización no puede bloquearse: $sello"
                        }
                    }
                assertTrue(disponibles.all { it.generacion == 1 })
                assertEquals(
                    "todas las corrutinas deben recibir la misma clave",
                    1,
                    disponibles.map { it.clave.encoded.toList() }.distinct().size,
                )
                disponibles.forEach {
                    assertEquals(
                        disponibles.first().clave.encoded.toList(),
                        it.clave.encoded.toList(),
                    )
                }
                val final = indice.estado(base.plusSeconds(100))
                assertEquals(FaseIndiceHmac.OPERATIVO, final.fase)
                assertNotNull(db.estadoIndiceHmacDao().actual())
            } finally {
                db.close()
            }
        }

    private fun abrir(): AppDatabase =
        Room
            .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()

    /**
     * Lanza [cantidad] corrutinas que se esperan en una barrera antes de entrar
     * a [bloque]: la concurrencia es real y no depende del azar del planificador.
     */
    private fun <T> enParalelo(
        cantidad: Int,
        bloque: suspend (Int) -> T,
    ): List<T> =
        runBlocking(Dispatchers.IO) {
            val barrera = CyclicBarrier(cantidad)
            (0 until cantidad)
                .map { indice ->
                    async(Dispatchers.IO) {
                        barrera.await(30, TimeUnit.SECONDS)
                        bloque(indice)
                    }
                }.awaitAll()
        }
}
