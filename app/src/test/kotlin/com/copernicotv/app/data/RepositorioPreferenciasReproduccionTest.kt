package com.copernicotv.app.data

import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.copernicotv.app.player.ModoAspecto
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * E7-T5 / S44b / S17b: el modo de aspecto y la visibilidad de los controles
 * secundarios fijos se guardan globalmente y sobreviven a una instancia nueva
 * del DataStore. Un valor ajeno al enum cae en `Ajustar` (S44c).
 */
class RepositorioPreferenciasReproduccionTest {
    private lateinit var archivo: File
    private lateinit var scope: CoroutineScope

    @Before
    fun preparar() {
        archivo = File.createTempFile("preferencias-reproduccion", ".preferences_pb").apply { delete() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun limpiar() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        archivo.delete()
    }

    @Test
    fun `sin datos previos usa Ajustar y secundarios visibles`() {
        runBlocking {
            assertEquals(PreferenciasReproduccion(), crearRepositorio().leer())
        }
    }

    @Test
    fun `guarda y lee el modo y la visibilidad`() {
        runBlocking {
            val repositorio = crearRepositorio()
            val preferencias =
                PreferenciasReproduccion(
                    modoAspecto = ModoAspecto.ZOOM,
                    controlesSecundariosVisibles = false,
                )

            repositorio.guardar(preferencias)

            assertEquals(preferencias, repositorio.leer())
        }
    }

    @Test
    fun `un nombre de modo ajeno al enum cae en Ajustar sin fallar`() {
        runBlocking {
            val dataStore = dataStoreEnArchivo()
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey("modo_aspecto")] = "RANCIO"
                prefs[booleanPreferencesKey("controles_secundarios")] = false
            }

            val leidas = RepositorioPreferenciasReproduccionDataStore(dataStore).leer()

            assertEquals(
                PreferenciasReproduccion(
                    modoAspecto = ModoAspecto.AJUSTAR,
                    controlesSecundariosVisibles = false,
                ),
                leidas,
            )
        }
    }

    @Test
    fun `sobrevive a una instancia nueva del DataStore`() {
        runBlocking {
            crearRepositorio().guardar(
                PreferenciasReproduccion(
                    modoAspecto = ModoAspecto.RELLENAR,
                    controlesSecundariosVisibles = false,
                ),
            )
        }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        runBlocking {
            assertEquals(
                PreferenciasReproduccion(
                    modoAspecto = ModoAspecto.RELLENAR,
                    controlesSecundariosVisibles = false,
                ),
                crearRepositorio().leer(),
            )
        }
    }

    private fun dataStoreEnArchivo(): DataStore<Preferences> =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { archivo },
        )

    private fun crearRepositorio() = RepositorioPreferenciasReproduccionDataStore(dataStoreEnArchivo())
}
