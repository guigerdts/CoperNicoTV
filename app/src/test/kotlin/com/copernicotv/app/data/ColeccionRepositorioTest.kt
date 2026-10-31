package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.ColeccionCanalEntity
import com.copernicotv.app.data.db.entities.ColeccionEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.identity.ClavePresentacion
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * E3-T5 / E3-T8 / S14b / S45a–S45c: Colecciones con orden propio,
 * independiente de `Apariencia.orden`, persistente tras cerrar y reabrir la
 * base con un repositorio nuevo.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ColeccionRepositorioTest {
    private lateinit var db: AppDatabase
    private val claves = ClavesEnMemoria()
    private val preferencias = PreferenciasOrdenEnMemoria()
    private val contexto: Context = ApplicationProvider.getApplicationContext()
    private var lista1: Long = 0L
    private var lista2: Long = 0L
    private var canal1: Long = 0L
    private var canal2: Long = 0L
    private var canal3: Long = 0L
    private var coleccion: Long = 0L

    @Before
    fun abrir() {
        contexto.deleteDatabase(NOMBRE_BD)
        db = abrirBase()
        runBlocking { sembrar() }
    }

    @After
    fun cerrar() {
        runCatching { db.close() }
        contexto.deleteDatabase(NOMBRE_BD)
    }

    @Test
    fun `crea coleccion y conserva su orden tras reabrir la base`() {
        runBlocking {
            val repositorio = ColeccionRepositorio(db)
            assertEquals(ResultadoAccion.Ok, repositorio.crear("Favoritos del living"))
            val nueva = repositorio.colecciones().first().first { it.nombre == "Favoritos del living" }
            assertEquals(ResultadoAccion.Ok, repositorio.agregarCanal(nueva.id, canal1))
            assertEquals(ResultadoAccion.Ok, repositorio.agregarCanal(nueva.id, canal2))
            assertEquals(ResultadoAccion.Ok, repositorio.agregarCanal(nueva.id, canal3))
            assertEquals(listOf("C1", "C2", "C3"), nombresColeccion(nueva.id))

            val filaC1 = db.coleccionCanalDao().porColeccion(nueva.id).first { it.canalId == canal1 }
            assertEquals(ResultadoAccion.Ok, repositorio.moverFila(filaC1.id, 1))
            assertEquals(listOf("C2", "C1", "C3"), nombresColeccion(nueva.id))

            db.close()
            db = abrirBase()
            val reabierto = ColeccionRepositorio(db)
            assertEquals(listOf("C2", "C1", "C3"), reabierto.canalesDe(nueva.id).first().map { it.nombre })
            assertEquals("Favoritos del living", reabierto.colecciones().first().single().nombre)
        }
    }

    @Test
    fun `el orden de la coleccion no toca el orden de las listas y viceversa`() {
        runBlocking {
            val colecciones = ColeccionRepositorio(db)
            val crud = CatalogoCrudRepositorio(db, claves, preferencias)
            assertEquals(listOf("C1", "C2", "C3"), nombresLista(lista1))
            assertEquals(listOf("C3", "C2", "C1"), nombresLista(lista2))

            val filaC1 = db.coleccionCanalDao().porColeccion(coleccion).first { it.canalId == canal1 }
            assertEquals(ResultadoAccion.Ok, colecciones.moverFila(filaC1.id, 1))
            assertEquals(listOf("C2", "C1", "C3"), nombresColeccion(coleccion))
            assertEquals(listOf("C1", "C2", "C3"), nombresLista(lista1))
            assertEquals(listOf("C3", "C2", "C1"), nombresLista(lista2))

            assertEquals(ResultadoAccion.Ok, crud.moverCanal(canal1, 1))
            assertEquals(listOf("C2", "C1", "C3"), nombresLista(lista1))
            assertEquals(listOf("C2", "C1", "C3"), nombresColeccion(coleccion))

            db.close()
            db = abrirBase()
            assertEquals(listOf("C2", "C1", "C3"), nombresLista(lista1))
            assertEquals(listOf("C2", "C1", "C3"), nombresColeccion(coleccion))
        }
    }

    @Test
    fun `agregar el mismo canal dos veces no duplica la pertenencia`() {
        runBlocking {
            val repositorio = ColeccionRepositorio(db)
            val nuevaId = insertarColeccion("Sin duplicados")

            assertEquals(ResultadoAccion.Ok, repositorio.agregarCanal(nuevaId, canal1))
            assertEquals(ResultadoAccion.Ok, repositorio.agregarCanal(nuevaId, canal1))

            assertEquals(1, db.coleccionCanalDao().porColeccion(nuevaId).size)
            assertEquals(listOf("C1"), nombresColeccion(nuevaId))
        }
    }

    @Test
    fun `quitar una pertenencia reindexa sin tocar las demas`() {
        runBlocking {
            val repositorio = ColeccionRepositorio(db)
            val filaC2 = db.coleccionCanalDao().porColeccion(coleccion).first { it.canalId == canal2 }

            assertEquals(ResultadoAccion.Ok, repositorio.quitarFila(filaC2.id))

            assertEquals(listOf("C1", "C3"), nombresColeccion(coleccion))
            assertEquals(listOf(0, 1), db.coleccionCanalDao().porColeccion(coleccion).map { it.posicion })
        }
    }

    @Test
    fun `eliminar la coleccion borra sus pertenencias y conserva canales`() {
        runBlocking {
            val repositorio = ColeccionRepositorio(db)

            assertEquals(ResultadoAccion.Ok, repositorio.eliminar(coleccion))

            assertEquals(0, db.coleccionDao().contar())
            assertEquals(0, db.coleccionCanalDao().contar())
            assertNotNull(db.canalDao().porId(canal1))
        }
    }

    @Test
    fun `rechaza nombre vacio y movimiento invalido`() {
        runBlocking {
            val repositorio = ColeccionRepositorio(db)

            assertEquals(ResultadoAccion.Error("nombre_vacio"), repositorio.crear("   "))
            assertEquals(
                ResultadoAccion.Error("movimiento_invalido"),
                repositorio.moverFila(1L, 2),
            )
        }
    }

    private suspend fun sembrar() {
        val ahora = Instant.now()
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente colecciones",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        lista1 = insertarLista(fuenteId, "L1", 0, "activa", ahora)
        lista2 = insertarLista(fuenteId, "L2", 1, "inactiva", ahora)
        canal1 = insertarCanal(fuenteId, "C1", ahora)
        canal2 = insertarCanal(fuenteId, "C2", ahora)
        canal3 = insertarCanal(fuenteId, "C3", ahora)
        insertarApariencias(lista1, listOf(canal1, canal2, canal3), ahora)
        insertarApariencias(lista2, listOf(canal3, canal2, canal1), ahora)
        coleccion = insertarColeccion("Favoritos")
        listOf(canal1, canal2, canal3).forEachIndexed { posicion, canalId ->
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(
                    coleccionId = coleccion,
                    canalId = canalId,
                    posicion = posicion,
                ),
            )
        }
    }

    private suspend fun insertarColeccion(nombre: String): Long {
        val ahora = Instant.now()
        return db.coleccionDao().insertar(
            ColeccionEntity(nombre = nombre, creadoEn = ahora, actualizadoEn = ahora),
        )
    }

    private suspend fun insertarLista(
        fuenteId: Long,
        nombre: String,
        orden: Int,
        estado: String,
        ahora: Instant,
    ): Long =
        db.listaDao().insertar(
            ListaEntity(
                fuenteId = fuenteId,
                nombre = nombre,
                ubicacionCifrada = byteArrayOf(orden.toByte()),
                ubicacionHmac = byteArrayOf((orden + 1).toByte()),
                esquema = "content",
                orden = orden,
                estado = estado,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun insertarCanal(
        fuenteId: Long,
        nombre: String,
        ahora: Instant,
    ): Long =
        db.canalDao().insertar(
            CanalEntity(
                fuenteId = fuenteId,
                nombre = nombre,
                urlStreamCifrada = byteArrayOf(1),
                urlStreamHmac = byteArrayOf(2),
                claveDerivable = false,
                creadoEn = ahora,
                actualizadoEn = ahora,
            ),
        )

    private suspend fun insertarApariencias(
        listaId: Long,
        canales: List<Long>,
        ahora: Instant,
    ) {
        canales.forEachIndexed { orden, canalId ->
            db.aparienciaDao().insertar(
                AparienciaEntity(
                    listaId = listaId,
                    canalId = canalId,
                    orden = orden,
                    clavePresentacion = ClavePresentacion.clave(null, null),
                    repeticiones = 1,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        }
    }

    private suspend fun nombresLista(listaId: Long): List<String> =
        db.aparienciaDao().porLista(listaId).mapNotNull { apariencia ->
            db.canalDao().porId(apariencia.canalId)?.nombre
        }

    private suspend fun nombresColeccion(coleccionId: Long): List<String> =
        db.coleccionCanalDao().porColeccion(coleccionId).mapNotNull { fila ->
            db.canalDao().porId(fila.canalId)?.nombre
        }

    private fun abrirBase(): AppDatabase =
        Room
            .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
            .allowMainThreadQueries()
            .build()

    private companion object {
        const val NOMBRE_BD = "coleccion-repositorio.db"
    }
}
