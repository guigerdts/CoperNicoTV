package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.m3u.ImportadorM3U
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * E4-T8: los tres indicadores de S7 son independientes.
 *
 * S7b: el resultado registra éxito o fallo con causa. S7c: la disponibilidad
 * se mantiene separada y no cambia por el solo fallo de una Actualización.
 * S7d: la disponibilidad no se reutiliza como estado de actualización.
 *
 * Usa `runBlocking` y `allowMainThreadQueries()` como el resto de las pruebas
 * Room del módulo (`ActualizacionRepositorioTest`): la base vive en memoria de
 * prueba y el runner es Robolectric, no hay hilos de UI reales que bloquear.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class IndicadoresFuenteTest {
    private lateinit var db: AppDatabase
    private lateinit var actualizacion: ActualizacionRepositorio
    private lateinit var indicadores: IndicadoresFuenteRepositorio
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private var fuenteId: Long = 0L
    private var listaId: Long = 0L

    @Before
    fun abrir() {
        contexto.deleteDatabase(NOMBRE_BD)
        db =
            Room
                .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
                .allowMainThreadQueries()
                .build()
        actualizacion = ActualizacionRepositorio(db, claves, contexto)
        indicadores = IndicadoresFuenteRepositorioRoom(db)
        runBlocking { sembrarFuenteYLista() }
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `S7b un intento exitoso registra exito y conserva la disponibilidad`() {
        runBlocking {
            actualizacion.actualizarConImportado(listaId, ImportadorM3U.importar(TEXTO, claves.claveHmac))

            val indicador = indicadores.observar().first().single()

            assertEquals("Fuente", indicador.nombre)
            assertTrue("El intento completado es un éxito", indicador.actualizacion?.exito == true)
            assertNull("Un éxito no tiene causa de fallo", indicador.actualizacion?.causa)
            assertEquals(DisponibilidadFuente.DISPONIBLE, indicador.disponibilidad)
        }
    }

    @Test
    fun `S7c el fallo de actualizacion no cambia la disponibilidad`() {
        runBlocking {
            actualizacion.actualizarConImportado(listaId, ImportadorM3U.importar(TEXTO, claves.claveHmac))
            assertEquals(DisponibilidadFuente.DISPONIBLE, indicadores.observar().first().single().disponibilidad)

            // La ubicación cifrada de la Lista está corrupta: `leer` falla antes
            // de tocar la red o el catálogo. Se usa este fallo determinista
            // porque el ContentResolver de Robolectric no modela un documento
            // inaccesible: lanza `UnsupportedOperationException` en vez de una
            // `IOException` y el repositorio solo captura la segunda.
            val resumen = actualizacion.actualizarLista(listaId)
            assertEquals("fallida", resumen.resultado)
            assertEquals("ubicacion_invalida", resumen.error)

            val indicador = indicadores.observar().first().single()

            assertFalse("El fallo queda registrado como intento fallido", indicador.actualizacion?.exito == true)
            assertEquals("ubicacion_invalida", indicador.actualizacion?.causa)
            assertEquals("El fallo no altera la disponibilidad", DisponibilidadFuente.DISPONIBLE, indicador.disponibilidad)
            assertEquals("El catálogo persiste", 1, db.canalDao().contarPorFuente(fuenteId))
        }
    }

    @Test
    fun `S7d una fuente sin canales no esta disponible y no tiene intentos`() {
        runBlocking {
            val indicador = indicadores.observar().first().single()

            assertEquals(DisponibilidadFuente.SIN_CONTENIDO, indicador.disponibilidad)
            assertNull("Sin Actualización no hay resultado", indicador.actualizacion)
            assertFalse("Sin Actualización no está cargando", indicador.cargando)
        }
    }

    private suspend fun sembrarFuenteYLista() {
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
        listaId =
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "Lista",
                    // Sobre vacío: la ubicación cifrada no se puede descifrar.
                    // S7b no lee la ubicación (usa el camino con importado) y
                    // S7c necesita un fallo de lectura determinista.
                    ubicacionCifrada = ByteArray(0),
                    ubicacionHmac = ByteArray(32),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
    }

    private companion object {
        const val NOMBRE_BD = "indicadores-fuente.db"

        val TEXTO =
            """
            #EXTM3U
            #EXTINF:-1 tvg-id="uno" group-title="G",Uno
            https://example.com/uno
            """.trimIndent()
    }
}
