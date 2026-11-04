package com.copernicotv.app.data

import androidx.datastore.preferences.core.PreferenceDataStoreFactory
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import com.copernicotv.core.orden.PreferenciaOrden
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
 * E3-T7 / DC-08: la preferencia de orden por Lista vive en DataStore con la
 * clave `orden_lista:<id>`, se limpia al borrar la Lista y se barre al inicio.
 */
class PreferenciasOrdenDataStoreTest {
    private lateinit var archivo: File
    private lateinit var scope: CoroutineScope

    @Before
    fun preparar() {
        archivo = File.createTempFile("orden-catalogo", ".preferences_pb").apply { delete() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    }

    @After
    fun limpiar() {
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        archivo.delete()
    }

    @Test
    fun `guarda lee limpia y barre huerfanas por lista`() {
        runBlocking {
            val preferencias = PreferenciasOrdenDataStore(crearDataStore())
            val nombreDescendente = PreferenciaOrden(ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE)
            preferencias.guardar(7, nombreDescendente)
            preferencias.guardar(9, PreferenciaOrden(ModoOrden.GRUPO))

            assertEquals(nombreDescendente, preferencias.preferencia(7).first())
            assertEquals(PreferenciaOrden(ModoOrden.GRUPO), preferencias.preferencia(9).first())
            assertEquals(PreferenciaOrden(), preferencias.preferencia(99).first())

            preferencias.limpiar(7)
            assertEquals(PreferenciaOrden(), preferencias.preferencia(7).first())
            assertEquals(PreferenciaOrden(ModoOrden.GRUPO), preferencias.preferencia(9).first())

            preferencias.barrerHuerfanas(setOf(9))
            assertEquals(PreferenciaOrden(), preferencias.preferencia(7).first())
            assertEquals(PreferenciaOrden(ModoOrden.GRUPO), preferencias.preferencia(9).first())
        }
    }

    @Test
    fun `la preferencia sobrevive a una nueva instancia del DataStore`() {
        val preferencia = PreferenciaOrden(ModoOrden.RECIENTES, DireccionOrden.DESCENDENTE)
        runBlocking {
            val preferencias = PreferenciasOrdenDataStore(crearDataStore())
            preferencias.guardar(11, preferencia)
        }
        runBlocking { scope.coroutineContext[Job]?.cancelAndJoin() }
        scope = CoroutineScope(Dispatchers.IO + SupervisorJob())

        runBlocking {
            val preferencias = PreferenciasOrdenDataStore(crearDataStore())
            assertEquals(preferencia, preferencias.preferencia(11).first())
        }
    }

    private fun crearDataStore() =
        PreferenceDataStoreFactory.create(
            scope = scope,
            produceFile = { archivo },
        )
}
