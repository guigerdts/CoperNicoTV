package com.copernicotv.app.data.actualizacion

import android.content.Context
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.Worker
import androidx.work.WorkerFactory
import androidx.work.WorkerParameters
import androidx.work.testing.SynchronousExecutor
import androidx.work.testing.WorkManagerTestInitHelper
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
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
import java.io.File
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Provider

/**
 * DC-07 puntos 1, 3, 4 y 5: forma de los trabajos, intervalo efectivo por
 * Fuente, actualización de la programación y limpieza de Fuentes eliminadas.
 *
 * Se usa el WorkManager de prueba con ejecutor síncrono. La lógica de la
 * Actualización se prueba en `CoordinadorActualizacionTest`; aquí se mira la
 * solicitud, que es lo que decide la política de DC-07, y el disparo real al
 * cumplirse el intervalo, con una fábrica de prueba que sustituye el
 * `@HiltWorker` porque esta prueba no levanta Hilt.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ProgramadorWorkManagerTest {
    private lateinit var db: AppDatabase
    private lateinit var preferencias: PreferenciasActualizacion
    private lateinit var reclamo: ReclamoFuente
    private lateinit var programador: ProgramadorWorkManager
    private lateinit var archivo: File
    private lateinit var scope: CoroutineScope
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private val fabrica = FabricaEspia()

    @Before
    fun preparar() {
        contexto.deleteDatabase(NOMBRE_BD)
        db =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                .allowMainThreadQueries()
                .build()
        archivo = File.createTempFile("actualizacion-wm", ".preferences_pb").apply { delete() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
        val dataStore = PreferenceDataStoreFactory.create(scope = scope, produceFile = { archivo })
        preferencias = PreferenciasActualizacionDataStore(dataStore)
        reclamo = ReclamoFuenteDataStore(dataStore)
        WorkManagerTestInitHelper.initializeTestWorkManager(
            contexto,
            Configuration.Builder()
                .setExecutor(SynchronousExecutor())
                .setWorkerFactory(fabrica)
                .build(),
        )
        programador =
            ProgramadorWorkManager(
                db = db,
                preferencias = preferencias,
                reclamo = reclamo,
                trabajo = Provider { WorkManager.getInstance(contexto) },
            )
    }

    @After
    fun limpiar() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        archivo.delete()
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `cada fuente se programa con su intervalo efectivo y sus restricciones`() {
        runBlocking {
            val global = crearFuente("Global", usarGlobal = true, propio = null)
            val propia = crearFuente("Propia", usarGlobal = false, propio = 60L)

            programador.reconciliar()

            val trabajoGlobal = periodico(global)
            assertEquals("Usa el global por defecto", 720L * 60_000L, trabajoGlobal.repeatIntervalMillis)
            assertEquals(NetworkType.CONNECTED, trabajoGlobal.constraints.requiredNetworkType)
            assertTrue("DC-07 punto 3: batería no baja", trabajoGlobal.constraints.requiresBatteryNotLow())
            assertTrue("Tag común", trabajoGlobal.tags.contains(ProgramadorWorkManager.TAG_ACTUALIZACION))
            assertTrue("Tag de Fuente", trabajoGlobal.tags.contains(ProgramadorWorkManager.tagFuente(global)))

            assertEquals("Usa el intervalo propio", 60L * 60_000L, periodico(propia).repeatIntervalMillis)
            assertEquals(mapOf(global to 720L, propia to 60L), preferencias.programadas())
        }
    }

    @Test
    fun `un cambio de intervalo actualiza la programacion`() {
        runBlocking {
            val fuente = crearFuente("Fuente", usarGlobal = true, propio = null)
            programador.reconciliar()
            assertEquals(720L * 60_000L, periodico(fuente).repeatIntervalMillis)

            preferencias.guardarIntervaloGlobal(60)
            programador.reconciliar()

            val despues = infos(ProgramadorWorkManager.nombrePeriodico(fuente))
            assertEquals("UPDATE no duplica el trabajo", 1, despues.size)
            assertEquals("El intervalo nuevo se aplica", 60L * 60_000L, despues.single().repeatIntervalMillis)
            assertEquals(60L, preferencias.programadas()[fuente])
        }
    }

    @Test
    fun `sin cambio de intervalo se conserva el trabajo`() {
        runBlocking {
            val fuente = crearFuente("Fuente", usarGlobal = true, propio = null)
            programador.reconciliar()
            val antes = periodico(fuente).id

            programador.reconciliar()

            assertEquals("KEEP no recrea el trabajo", antes, periodico(fuente).id)
        }
    }

    @Test
    fun `una fuente eliminada cancela su programacion`() {
        runBlocking {
            val fuente = crearFuente("Fuente", usarGlobal = true, propio = null)
            programador.reconciliar()
            assertTrue("Estaba programada", preferencias.programadas().containsKey(fuente))

            db.fuenteDao().eliminar(fuente)
            programador.reconciliar()

            assertFalse("Se olvidó la programación", preferencias.programadas().containsKey(fuente))
            assertTrue(
                "No queda trabajo abierto",
                infos(ProgramadorWorkManager.nombrePeriodico(fuente)).none { it.state in ESTADOS_ABIERTOS },
            )
        }
    }

    @Test
    fun `un intervalo rechazado no se programa`() {
        runBlocking {
            val fuente = crearFuente("Corta", usarGlobal = false, propio = 5L)

            programador.reconciliar()

            assertTrue(
                "No se programa un intervalo inválido",
                infos(ProgramadorWorkManager.nombrePeriodico(fuente)).isEmpty(),
            )
            assertFalse(preferencias.programadas().containsKey(fuente))
        }
    }

    @Test
    fun `la actualizacion manual usa un nombre propio y no se duplica`() {
        runBlocking {
            val fuente = crearFuente("Fuente", usarGlobal = true, propio = null)

            programador.solicitarFuente(fuente)
            programador.solicitarFuente(fuente)

            val manual = infos(ProgramadorWorkManager.nombreManual(fuente))
            assertEquals("KEEP no duplica la solicitud", 1, manual.size)
            assertEquals(WorkInfo.State.ENQUEUED, manual.single().state)
            assertTrue("La manual no reemplaza a la periódica", manual.single().tags.contains(ProgramadorWorkManager.tagFuente(fuente)))
            assertTrue(
                "La periódica no se creó",
                infos(ProgramadorWorkManager.nombrePeriodico(fuente)).isEmpty(),
            )
        }
    }

    @Test
    fun `la solicitud por lista resuelve su fuente`() {
        runBlocking {
            val fuente = crearFuente("Fuente", usarGlobal = true, propio = null)
            val lista =
                db.listaDao().insertar(
                    ListaEntity(
                        fuenteId = fuente,
                        nombre = "Lista",
                        ubicacionCifrada = ByteArray(0),
                        ubicacionHmac = ByteArray(32),
                        esquema = "content",
                        orden = 0,
                        estado = "activa",
                        creadoEn = Instant.parse("2026-01-01T00:00:00Z"),
                        actualizadoEn = Instant.parse("2026-01-01T00:00:00Z"),
                    ),
                )

            programador.solicitarLista(lista)

            assertEquals(1, infos(ProgramadorWorkManager.nombreManual(fuente)).size)
        }
    }

    @Test
    fun `el estado observado marca la fuente en curso`() {
        runBlocking {
            val fuente = crearFuente("Fuente", usarGlobal = true, propio = null)

            programador.solicitarFuente(fuente)

            val estado = programador.observar().first()[fuente]
            assertTrue("La Fuente figura en curso", estado?.enCurso == true)
            assertTrue("Sin ejecución no hay resumen", estado?.resumenes.isNullOrEmpty())
        }
    }

    @Test
    fun `S3b el trabajo periodico se dispara al cumplirse el intervalo configurado`() {
        runBlocking {
            val fuente = crearFuente("Fuente", usarGlobal = false, propio = 15L)
            programador.reconciliar()
            val trabajo = periodico(fuente)
            assertEquals(15L * 60_000L, trabajo.repeatIntervalMillis)

            val driver = checkNotNull(WorkManagerTestInitHelper.getTestDriver(contexto))
            driver.setAllConstraintsMet(trabajo.id)
            driver.setPeriodDelayMet(trabajo.id)

            assertEquals("WorkManager dispara el trabajo al cumplirse el intervalo", 1, fabrica.ejecuciones.get())
        }
    }

    private suspend fun crearFuente(
        nombre: String,
        usarGlobal: Boolean,
        propio: Long?,
    ): Long {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        return db.fuenteDao().insertar(
            FuenteEntity(
                nombre = nombre,
                estado = "activa",
                intervaloMinutos = propio,
                usarIntervaloGlobal = usarGlobal,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )
    }

    private fun periodico(fuenteId: Long): WorkInfo = infos(ProgramadorWorkManager.nombrePeriodico(fuenteId)).single()

    private fun infos(nombre: String): List<WorkInfo> = WorkManager.getInstance(contexto).getWorkInfosForUniqueWork(nombre).get()

    private val WorkInfo.repeatIntervalMillis: Long
        get() = checkNotNull(periodicityInfo) { "El trabajo no es periódico" }.repeatIntervalMillis

    private companion object {
        const val NOMBRE_BD = "programador-test.db"
        val ESTADOS_ABIERTOS =
            setOf(WorkInfo.State.ENQUEUED, WorkInfo.State.RUNNING, WorkInfo.State.BLOCKED)
    }
}

/**
 * Sustituye el `@HiltWorker` real: esta prueba no levanta Hilt y lo que
 * verifica es que WorkManager dispare el trabajo al cumplirse el intervalo, no
 * la lógica de la Actualización, que cubre `CoordinadorActualizacionTest`.
 */
private class FabricaEspia : WorkerFactory() {
    val ejecuciones = AtomicInteger(0)

    override fun createWorker(
        appContext: Context,
        workerClassName: String,
        workerParameters: WorkerParameters,
    ): ListenableWorker? {
        if (workerClassName != ActualizacionWorker::class.java.name) return null
        return object : Worker(appContext, workerParameters) {
            override fun doWork(): Result {
                ejecuciones.incrementAndGet()
                return Result.success()
            }
        }
    }
}
