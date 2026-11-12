package com.copernicotv.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import com.copernicotv.core.tema.TemaUi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import java.io.File

/**
 * E7-T4 / S43: la preferencia de tema vive en DataStore con la clave
 * `tema_ui`; el valor ausente o desconocido cae al oscuro (comportamiento
 * previo a E7-T4) y el alternado invierte el tema persistido.
 */
class PreferenciasTemaDataStoreTest {
    private lateinit var archivo: File
    private lateinit var scope: CoroutineScope

    @Before
    fun preparar() {
        archivo = File.createTempFile("tema-ui", ".preferences_pb").apply { delete() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun limpiar() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        archivo.delete()
    }

    @Test
    fun `el tema por defecto es oscuro y el alternado persiste el claro`() {
        runBlocking {
            val preferencias = PreferenciasTemaDataStore(crearDataStore())

            assertEquals(TemaUi.OSCURO, preferencias.tema().first())

            assertEquals(TemaUi.CLARO, preferencias.alternar())
            assertEquals(TemaUi.CLARO, preferencias.tema().first())

            assertEquals(TemaUi.OSCURO, preferencias.alternar())
            assertEquals(TemaUi.OSCURO, preferencias.tema().first())
        }
    }

    @Test
    fun `el tema sobrevive a una nueva instancia del DataStore`() {
        runBlocking {
            val preferencias = PreferenciasTemaDataStore(crearDataStore())
            preferencias.alternar()
        }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        runBlocking {
            val preferencias = PreferenciasTemaDataStore(crearDataStore())
            assertEquals(TemaUi.CLARO, preferencias.tema().first())
        }
    }

    @Test
    fun `un valor desconocido persistido cae al oscuro`() {
        runBlocking {
            val dataStore = crearDataStore()
            dataStore.edit { prefs ->
                prefs[stringPreferencesKey(CLAVE_TEMA)] = "sepia"
            }
            val preferencias = PreferenciasTemaDataStore(dataStore)
            assertEquals(TemaUi.OSCURO, preferencias.tema().first())
        }
    }

    private fun crearDataStore() =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { archivo },
        )
}
