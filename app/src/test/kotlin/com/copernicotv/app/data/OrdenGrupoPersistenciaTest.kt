package com.copernicotv.app.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.core.identity.ClavePresentacion
import com.copernicotv.core.orden.DireccionOrden
import com.copernicotv.core.orden.ModoOrden
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
 * E3-T7: el `PagingSource` en memoria de los modos A/B/C ordena los Canales de
 * la Lista activa con la collation de `:core`, no con el orden de Room.
 *
 * La fixture reproduce el caso del emulador: grupos Z y A más un Canal sin
 * Grupo. El modo Grupo debe poner `Grupo A`, `Grupo Z` y `Sin grupo` al final,
 * con los Canales de cada Grupo ordenados por nombre.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class OrdenGrupoPersistenciaTest {
    private lateinit var db: AppDatabase
    private var listaId: Long = 0

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        runBlocking { sembrar() }
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `modo grupo ordena por grupo y deja sin grupo al final`() {
        runBlocking {
            val pagina = cargar(ModoOrden.GRUPO, DireccionOrden.ASCENDENTE)
            assertEquals(listOf("Canal A", "Canal C", "Canal B"), pagina.map { it.nombre })
            assertEquals(listOf("Grupo A", "Grupo Z", null), pagina.map { it.grupoNombre })
        }
    }

    @Test
    fun `modo grupo descendente invierte grupos y canales pero deja sin grupo al final`() {
        runBlocking {
            val pagina = cargar(ModoOrden.GRUPO, DireccionOrden.DESCENDENTE)
            assertEquals(listOf("Canal C", "Canal A", "Canal B"), pagina.map { it.nombre })
            assertEquals(listOf("Grupo Z", "Grupo A", null), pagina.map { it.grupoNombre })
        }
    }

    private suspend fun cargar(
        modo: ModoOrden,
        direccion: DireccionOrden,
    ): List<CanalBusqueda> {
        val fuente =
            CatalogoOrdenadoPagingSource(
                db = db,
                criterios = CriteriosBusqueda(modo = modo, direccion = direccion),
                texto = "",
                patron = "%",
            )
        val parametros =
            PagingSource.LoadParams.Refresh<Int>(
                key = 0,
                loadSize = 10,
                placeholdersEnabled = false,
            )
        val resultado = fuente.load(parametros)
        val pagina =
            resultado as? PagingSource.LoadResult.Page<Int, CanalBusqueda>
                ?: error("La carga debía ser exitosa")
        return pagina.data
    }

    private suspend fun sembrar() {
        val ahora = Instant.parse("2026-01-01T00:00:00Z")
        val fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente orden",
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
                    nombre = "Lista orden",
                    ubicacionCifrada = byteArrayOf(0x01),
                    ubicacionHmac = byteArrayOf(0x02),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                ),
            )
        val grupoZ =
            db.grupoDao().insertar(
                GrupoEntity(listaId = listaId, nombre = "Grupo Z"),
            )
        val grupoA =
            db.grupoDao().insertar(
                GrupoEntity(listaId = listaId, nombre = "Grupo A"),
            )
        val canalIds =
            db.canalDao().insertarTodos(
                listOf("Canal C", "Canal A", "Canal B").map { nombre ->
                    CanalEntity(
                        fuenteId = fuenteId,
                        nombre = nombre,
                        urlStreamCifrada = byteArrayOf(0x01),
                        urlStreamHmac = byteArrayOf(0x02),
                        claveCanonica = "canon:$nombre",
                        claveUnicidad = "canal:$nombre",
                        claveDerivable = true,
                        creadoEn = ahora,
                        actualizadoEn = ahora,
                    )
                },
            )
        val grupos = listOf(grupoZ, grupoA, null)
        db.aparienciaDao().insertarTodas(
            canalIds.mapIndexed { orden, canalId ->
                AparienciaEntity(
                    listaId = listaId,
                    canalId = canalId,
                    grupoId = grupos[orden],
                    orden = orden,
                    clavePresentacion = ClavePresentacion.clave(null, null),
                    repeticiones = 1,
                    creadoEn = ahora,
                    actualizadoEn = ahora,
                )
            },
        )
    }
}
