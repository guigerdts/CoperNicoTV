package com.copernicotv.app.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.ClavesEnMemoria
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
import com.copernicotv.core.orden.PreferenciaOrden
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

/**
 * E3-T6 / S39: CRUD de Lista y Canal, Favoritos y Ocultaciones sobre el esquema
 * v1, con persistencia real tras reabrir la base.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class CatalogoCrudPersistenciaTest {
    private lateinit var db: AppDatabase
    private val claves = ClavesEnMemoria()
    private val preferencias = PreferenciasOrdenEnMemoria()
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
    fun `crea lista y canal y sobrevive a reabrir la base`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves, preferencias)
            assertEquals(ResultadoAccion.Ok, repositorio.crearLista("Mi lista"))

            val listas = repositorio.listas().first()
            assertEquals(1, listas.size)
            assertEquals("Mi lista", listas.single().nombre)
            assertTrue(listas.single().activa)

            assertEquals(
                ResultadoAccion.Ok,
                repositorio.crearCanal("Canal creado", "https://example.com/live.m3u8"),
            )
            val fuente = db.fuenteDao().todas().single()
            val lista = db.listaDao().porFuente(fuente.id).single()
            val canal = db.canalDao().porFuente(fuente.id).single()
            assertEquals("Canal creado", canal.nombre)
            assertTrue(canal.urlStreamCifrada.isNotEmpty())
            assertEquals(1, db.aparienciaDao().contar(lista.id))
            assertEquals(lista.id, db.listaDao().activa()?.id)

            db.close()
            db = abrirBase()

            assertEquals("Canal creado", db.canalDao().porId(canal.id)?.nombre)
            assertEquals(1, db.aparienciaDao().contar(lista.id))
        }
    }

    @Test
    fun `renombra canal y alterna favorito y ocultacion`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves, preferencias)
            repositorio.crearLista("Lista")
            repositorio.crearCanal("Original", "https://example.com/a.m3u8")
            val canal = db.canalDao().porFuente(db.fuenteDao().todas().single().id).single()

            assertEquals(ResultadoAccion.Ok, repositorio.editarNombreCanal(canal.id, "Editado"))
            assertEquals("Editado", db.canalDao().porId(canal.id)?.nombre)

            assertEquals(ResultadoAccion.Ok, repositorio.alternarFavorito(canal.id))
            assertTrue(db.favoritoDao().existe(canal.id) > 0)
            assertEquals(ResultadoAccion.Ok, repositorio.alternarFavorito(canal.id))
            assertFalse(db.favoritoDao().existe(canal.id) > 0)

            assertEquals(ResultadoAccion.Ok, repositorio.alternarOculto(canal.id))
            assertTrue(db.ocultacionCanalDao().existe(canal.id) > 0)
            assertEquals(ResultadoAccion.Ok, repositorio.alternarOculto(canal.id))
            assertFalse(db.ocultacionCanalDao().existe(canal.id) > 0)
        }
    }

    @Test
    fun `selecciona la lista activa y elimina canal y lista`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves, preferencias)
            repositorio.crearLista("Primera")
            val primera = requireNotNull(db.listaDao().activa())
            repositorio.crearLista("Segunda")
            val segunda = requireNotNull(db.listaDao().activa())
            assertTrue(segunda.id != primera.id)

            assertEquals(ResultadoAccion.Ok, repositorio.seleccionarLista(primera.id))
            assertEquals(primera.id, db.listaDao().activa()?.id)

            repositorio.crearCanal("Canal", "https://example.com/b.m3u8")
            val canal = db.canalDao().porFuente(primera.fuenteId).single()
            assertEquals(ResultadoAccion.Ok, repositorio.eliminarCanal(canal.id))
            assertEquals(0, db.canalDao().contar())

            assertEquals(ResultadoAccion.Ok, repositorio.eliminarLista(primera.id))
            assertEquals(segunda.id, db.listaDao().activa()?.id)
            assertEquals(1, db.listaDao().contar())
        }
    }

    @Test
    fun `rechaza una url invalida sin crear filas`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves, preferencias)
            repositorio.crearLista("Lista")
            assertEquals(
                ResultadoAccion.Error("url_invalida"),
                repositorio.crearCanal("Canal", "no-es-una-url"),
            )
            assertEquals(0, db.canalDao().contar())
            assertEquals(0, db.aparienciaDao().todas().size)
        }
    }

    @Test
    fun `eliminar lista limpia su preferencia de orden`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves, preferencias)
            repositorio.crearLista("Primera")
            val primera = requireNotNull(db.listaDao().activa())
            repositorio.crearLista("Segunda")
            val segunda = requireNotNull(db.listaDao().activa())
            val preferenciaPrimera = PreferenciaOrden(ModoOrden.NOMBRE, DireccionOrden.DESCENDENTE)
            val preferenciaSegunda = PreferenciaOrden(ModoOrden.GRUPO, DireccionOrden.ASCENDENTE)
            preferencias.guardar(primera.id, preferenciaPrimera)
            preferencias.guardar(segunda.id, preferenciaSegunda)

            assertEquals(ResultadoAccion.Ok, repositorio.eliminarLista(primera.id))

            assertNull(preferencias.valor(primera.id))
            assertEquals(preferenciaSegunda, preferencias.valor(segunda.id))
        }
    }

    @Test
    fun `barre preferencias de listas que ya no existen`() {
        runBlocking {
            val repositorio = CatalogoCrudRepositorio(db, claves, preferencias)
            repositorio.crearLista("Vigente")
            val vigente = requireNotNull(db.listaDao().activa())
            val preferenciaVigente = PreferenciaOrden(ModoOrden.RECIENTES, DireccionOrden.DESCENDENTE)
            preferencias.guardar(vigente.id, preferenciaVigente)
            preferencias.guardar(999_999L, PreferenciaOrden(ModoOrden.NOMBRE))

            repositorio.sincronizarPreferenciasDeOrden()

            assertNull(preferencias.valor(999_999L))
            assertEquals(preferenciaVigente, preferencias.valor(vigente.id))
        }
    }

    private fun abrirBase(): AppDatabase =
        Room
            .databaseBuilder(contexto, AppDatabase::class.java, NOMBRE_BD)
            .allowMainThreadQueries()
            .build()

    private companion object {
        const val NOMBRE_BD = "catalogo-crud.db"
    }
}
