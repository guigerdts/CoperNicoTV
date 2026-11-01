package com.copernicotv.app.data.actualizacion

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.longPreferencesKey
import com.copernicotv.core.actualizacion.IntervaloActualizacion
import com.copernicotv.core.actualizacion.MotivoRechazo
import com.copernicotv.core.actualizacion.ResultadoIntervalo
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
import java.io.File
import java.time.Instant

/**
 * DC-07 punto 2: el intervalo global y la programación por Fuente viven en
 * DataStore; DC-07 punto 6: el reclamo por Fuente es atómico y tiene vigencia.
 *
 * Se usa un DataStore de archivo temporal, igual que
 * `PreferenciasOrdenDataStoreTest`, para probar también la persistencia entre
 * instancias.
 */
class PreferenciasActualizacionTest {
    private lateinit var archivo: File
    private lateinit var scope: CoroutineScope

    @Before
    fun preparar() {
        archivo = File.createTempFile("actualizacion", ".preferences_pb").apply { delete() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun limpiar() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        archivo.delete()
    }

    @Test
    fun `sin preferencia el global es el valor por defecto`() {
        runBlocking {
            val preferencias = PreferenciasActualizacionDataStore(crearDataStore())

            assertEquals(IntervaloActualizacion.POR_DEFECTO, preferencias.intervaloGlobal().first())
        }
    }

    @Test
    fun `un intervalo valido se guarda y uno invalido no se aplica`() {
        runBlocking {
            val preferencias = PreferenciasActualizacionDataStore(crearDataStore())

            val aceptado = preferencias.guardarIntervaloGlobal(60)
            assertTrue(aceptado is ResultadoIntervalo.Aceptado)
            assertEquals(60L, preferencias.intervaloGlobal().first())

            val rechazado = preferencias.guardarIntervaloGlobal(IntervaloActualizacion.MINIMO - 1)
            assertEquals(MotivoRechazo.DEBAJO_DEL_MINIMO, (rechazado as ResultadoIntervalo.Rechazado).motivo)
            assertEquals("Un valor inválido no reemplaza al vigente", 60L, preferencias.intervaloGlobal().first())

            val excedido = preferencias.guardarIntervaloGlobal(IntervaloActualizacion.MAXIMO + 1)
            assertEquals(MotivoRechazo.SOBRE_EL_MAXIMO, (excedido as ResultadoIntervalo.Rechazado).motivo)
            assertEquals(60L, preferencias.intervaloGlobal().first())
        }
    }

    @Test
    fun `el intervalo global sobrevive a una nueva instancia`() {
        runBlocking {
            PreferenciasActualizacionDataStore(crearDataStore()).guardarIntervaloGlobal(120)
        }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        runBlocking {
            val preferencias = PreferenciasActualizacionDataStore(crearDataStore())

            assertEquals(120L, preferencias.intervaloGlobal().first())
        }
    }

    @Test
    fun `la programacion por fuente se registra y se olvida`() {
        runBlocking {
            val preferencias = PreferenciasActualizacionDataStore(crearDataStore())

            preferencias.registrarProgramada(3, 60)
            preferencias.registrarProgramada(4, 120)
            assertEquals(mapOf(3L to 60L, 4L to 120L), preferencias.programadas())

            preferencias.olvidarProgramada(3)
            assertEquals(mapOf(4L to 120L), preferencias.programadas())
        }
    }

    @Test
    fun `un reclamo solo se toma si la fuente esta libre`() {
        runBlocking {
            val reclamo = ReclamoFuenteDataStore(crearDataStore())

            assertTrue("La primera ejecución reclama", reclamo.reclamar(5))
            assertFalse("Una segunda ejecución no entra", reclamo.reclamar(5))
            assertTrue("La Fuente figura en curso", reclamo.enCurso().containsKey(5))

            reclamo.liberar(5)
            assertTrue("Liberada, se puede reclamar de nuevo", reclamo.reclamar(5))
        }
    }

    @Test
    fun `un reclamo vencido no bloquea para siempre`() {
        runBlocking {
            val dataStore = crearDataStore()
            // Se escribe la marca con el formato real de `en_curso:<id>` para
            // simular un proceso muerto a mitad de una ejecución.
            dataStore.edit { prefs ->
                prefs[longPreferencesKey("en_curso:8")] =
                    Instant.now().minusSeconds(VIGENCIA + 60).toEpochMilli()
            }
            val reclamo = ReclamoFuenteDataStore(dataStore)

            assertTrue("Un reclamo viejo se puede reclamar", reclamo.reclamar(8))
        }
    }

    private fun crearDataStore(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { archivo },
        )

    private companion object {
        /** Debe coincidir con la vigencia de `ReclamoFuenteDataStore`. */
        const val VIGENCIA = 30L * 60L
    }
}
