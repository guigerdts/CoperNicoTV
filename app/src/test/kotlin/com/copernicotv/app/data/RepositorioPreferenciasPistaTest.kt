package com.copernicotv.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.copernicotv.app.player.PreferenciaPista
import com.copernicotv.app.player.PreferenciasPista
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
 * E6-T3 / D3 / S44a: la preferencia de pista se guarda globalmente y sobrevive
 * a una instancia nueva del DataStore.
 */
class RepositorioPreferenciasPistaTest {
    private lateinit var archivo: File
    private lateinit var scope: CoroutineScope

    @Before
    fun preparar() {
        archivo = File.createTempFile("preferencias-pista", ".preferences_pb").apply { delete() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun limpiar() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        archivo.delete()
    }

    @Test
    fun `sin datos previos usa automatico para audio y subtitulos`() {
        runBlocking {
            val repositorio = crearRepositorio()

            assertEquals(PreferenciasPista(), repositorio.leer())
        }
    }

    @Test
    fun `guarda y lee la preferencia elegida`() {
        runBlocking {
            val repositorio = crearRepositorio()
            val preferencias =
                PreferenciasPista(
                    audio = PreferenciaPista.Pista("eng", "Inglés (latino)"),
                    subtitulos = PreferenciaPista.Pista("spa", "Español"),
                )

            repositorio.guardar(preferencias)

            assertEquals(preferencias, repositorio.leer())
        }
    }

    @Test
    fun `la preferencia sobrevive a una instancia nueva del DataStore`() {
        runBlocking {
            val preferencias =
                PreferenciasPista(
                    audio = PreferenciaPista.Pista("eng", "Inglés"),
                    subtitulos = PreferenciaPista.Desactivada,
                )
            crearRepositorio().guardar(preferencias)
        }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        runBlocking {
            assertEquals(
                PreferenciasPista(
                    audio = PreferenciaPista.Pista("eng", "Inglés"),
                    subtitulos = PreferenciaPista.Desactivada,
                ),
                crearRepositorio().leer(),
            )
        }
    }

    @Test
    fun `audio nunca queda desactivado`() {
        runBlocking {
            val repositorio = crearRepositorio()

            repositorio.guardar(
                PreferenciasPista(
                    audio = PreferenciaPista.Desactivada,
                    subtitulos = PreferenciaPista.Desactivada,
                ),
            )

            assertEquals(
                PreferenciasPista(
                    audio = PreferenciaPista.Automatica,
                    subtitulos = PreferenciaPista.Desactivada,
                ),
                repositorio.leer(),
            )
        }
    }

    private fun crearRepositorio() =
        RepositorioPreferenciasPistaDataStore(
            PreferenceDataStoreFactory.create(
                scope = scope,
                produceFile = { archivo },
            ),
        )
}
