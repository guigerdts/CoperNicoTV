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
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * E3-T8 / S14a / S45a–S45c: el reordenamiento manual de Apariencias aísla por
 * Lista, reutiliza `Apariencia.orden`, persiste tras reabrir la base y no toca
 * el orden propio de las Colecciones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class ReordenamientoAparienciasTest {
    private lateinit var db: AppDatabase
    private val claves = ClavesEnMemoria()
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
    fun `reordena solo L1 y persiste tras reabrir sin tocar la coleccion`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves)
            assertEquals(
                listOf("C1", "C2", "C3"),
                nombres(lista1),
            )
            assertEquals(
                listOf("C3", "C2", "C1"),
                nombres(lista2),
            )
            val coleccionAntes = db.coleccionCanalDao().porColeccion(coleccion)

            assertEquals(ResultadoAccion.Ok, repositorio.moverCanal(canal1, 1))

            assertEquals(listOf("C2", "C1", "C3"), nombres(lista1))
            assertEquals(listOf("C3", "C2", "C1"), nombres(lista2))
            assertEquals(coleccionAntes, db.coleccionCanalDao().porColeccion(coleccion))

            db.close()
            db = abrirBase()

            assertEquals(listOf("C2", "C1", "C3"), nombres(lista1))
            assertEquals(listOf("C3", "C2", "C1"), nombres(lista2))
            assertEquals(coleccionAntes, db.coleccionCanalDao().porColeccion(coleccion))
            assertEquals(canal1, db.canalDao().porId(canal1)?.id)
            assertEquals(canal2, db.canalDao().porId(canal2)?.id)
            assertEquals(canal3, db.canalDao().porId(canal3)?.id)
        }
    }

    @Test
    fun `los extremos no se mueven fuera de la lista`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves)
            assertEquals(ResultadoAccion.Ok, repositorio.moverCanal(canal1, -1))
            assertEquals(ResultadoAccion.Ok, repositorio.moverCanal(canal3, 1))
            assertEquals(listOf("C1", "C2", "C3"), nombres(lista1))
            assertEquals(listOf("C3", "C2", "C1"), nombres(lista2))
        }
    }

    private suspend fun sembrar() {
        val ahora = Instant.now()
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente reordenamiento",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        lista1 =
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "L1",
                    ubicacionCifrada = byteArrayOf(1),
                    ubicacionHmac = byteArrayOf(2),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        lista2 =
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "L2",
                    ubicacionCifrada = byteArrayOf(3),
                    ubicacionHmac = byteArrayOf(4),
                    esquema = "content",
                    orden = 1,
                    estado = "inactiva",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        canal1 = insertarCanal(fuenteId, "C1", ahora)
        canal2 = insertarCanal(fuenteId, "C2", ahora)
        canal3 = insertarCanal(fuenteId, "C3", ahora)
        insertarApariencias(lista1, listOf(canal1, canal2, canal3), ahora)
        insertarApariencias(lista2, listOf(canal3, canal2, canal1), ahora)

        coleccion =
            db.coleccionDao().insertar(
                ColeccionEntity(nombre = "Favoritos", creadoEn = ahora, actualizadoEn = ahora),
            )
        listOf(canal2, canal1, canal3).forEachIndexed { posicion, canalId ->
            db.coleccionCanalDao().insertar(
                ColeccionCanalEntity(
                    coleccionId = coleccion,
                    canalId = canalId,
                    posicion = posicion,
                ),
            )
        }
    }

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

    private suspend fun nombres(listaId: Long): List<String> =
        db.aparienciaDao().porLista(listaId).mapNotNull { apariencia ->
            db.canalDao().porId(apariencia.canalId)?.nombre
        }

    private fun abrirBase(): AppDatabase =
        Room
            .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
            .allowMainThreadQueries()
            .build()

    private companion object {
        const val NOMBRE_BD = "reordenamiento-apariencias.db"
    }
}
