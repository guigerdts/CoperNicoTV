package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * S47a/b/c: borrado independiente de Historial y Favoritos con conservación
 * cruzada.
 *
 * La base es un archivo real, no `inMemoryDatabaseBuilder`: cerrarla y volver
 * a abrirla es lo único que demuestra que el borrado persiste tras el
 * reinicio del proceso (S47c). Los conteos son `SELECT COUNT(*)` de cada
 * tabla, tal como exige la columna de pruebas de S47.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class S47BorradoPersistenciaTest {
    private lateinit var db: AppDatabase
    private val claves = ClavesEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()

    @Before
    fun abrir() {
        contexto.deleteDatabase(NOMBRE_BD)
        db = abrirBase()
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `borrar historial deja los favoritos intactos`() {
        runBlocking {
            val crud = sembrar()

            assertEquals(ResultadoAccion.Ok, crud.borrarHistorial())

            assertEquals(0, db.historialDao().contar())
            assertEquals(2, db.favoritoDao().contar())
        }
    }

    @Test
    fun `borrar favoritos deja el historial intacto`() {
        runBlocking {
            val crud = sembrar()

            assertEquals(ResultadoAccion.Ok, crud.borrarFavoritos())

            assertEquals(0, db.favoritoDao().contar())
            assertEquals(2, db.historialDao().contar())
        }
    }

    @Test
    fun `borrar ambos vacia ambas tablas y persiste tras reiniciar la base`() {
        runBlocking {
            val crud = sembrar()

            assertEquals(ResultadoAccion.Ok, crud.borrarHistorial())
            assertEquals(ResultadoAccion.Ok, crud.borrarFavoritos())
            assertEquals(0, db.historialDao().contar())
            assertEquals(0, db.favoritoDao().contar())

            // S47c: el borrado persiste tras el reinicio del proceso.
            db.close()
            db = abrirBase()

            assertEquals(0, db.historialDao().contar())
            assertEquals(0, db.favoritoDao().contar())
        }
    }

    @Test
    fun `la conservacion cruzada persiste tras reiniciar la base`() {
        runBlocking {
            val crud = sembrar()

            assertEquals(ResultadoAccion.Ok, crud.borrarHistorial())
            db.close()
            db = abrirBase()

            assertEquals(0, db.historialDao().contar())
            assertEquals(2, db.favoritoDao().contar())
        }
    }

    /**
     * Dos Canales en una Lista; favoritos en ambos y dos entradas de
     * Historial. El canal creado sí persiste, así que los conteos de semilla
     * fijan el estado inicial de cada prueba.
     */
    private fun sembrar(): CatalogoCrudRepositorio {
        val crud =
            CatalogoCrudRepositorio(db, claves, IndiceHmacRepositorio(db, claves), PreferenciasOrdenEnMemoria())
        runBlocking {
            assertEquals(ResultadoAccion.Ok, crud.crearLista("Lista de borrado"))
            assertEquals(ResultadoAccion.Ok, crud.crearCanal("Canal Uno", "https://example.com/uno.m3u8"))
            assertEquals(ResultadoAccion.Ok, crud.crearCanal("Canal Dos", "https://example.com/dos.m3u8"))
            val canales = db.canalDao().todos()
            assertEquals(2, canales.size)
            assertEquals(ResultadoAccion.Ok, crud.alternarFavorito(canales[0].id))
            assertEquals(ResultadoAccion.Ok, crud.alternarFavorito(canales[1].id))
            HistorialRepositorio(db).registrar(canales[0].id)
            HistorialRepositorio(db).registrar(canales[1].id)
            assertEquals(2, db.historialDao().contar())
            assertEquals(2, db.favoritoDao().contar())
        }
        return crud
    }

    private fun abrirBase(): AppDatabase =
        Room
            .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
            .allowMainThreadQueries()
            .build()

    private companion object {
        const val NOMBRE_BD = "s47-borrado.db"
    }
}
