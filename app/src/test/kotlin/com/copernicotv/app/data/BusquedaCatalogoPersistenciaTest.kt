package com.copernicotv.app.data

import android.content.Context
import androidx.paging.PagingSource
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.copernicotv.app.data.db.AppDatabase
import com.copernicotv.app.data.db.entities.AparienciaEntity
import com.copernicotv.app.data.db.entities.CanalEntity
import com.copernicotv.app.data.db.entities.FavoritoEntity
import com.copernicotv.app.data.db.entities.FuenteEntity
import com.copernicotv.app.data.db.entities.GrupoEntity
import com.copernicotv.app.data.db.entities.ListaEntity
import com.copernicotv.app.data.db.entities.OcultacionCanalEntity
import com.copernicotv.app.data.db.entities.OcultacionGrupoEntity
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.Instant

/**
 * Búsqueda paginada de E3-T1 sobre 10 000 Canales y Room real.
 *
 * Cubre S9: primera página con IDs esperados, sin duplicados, misma página en
 * dos consultas consecutivas, segunda página y combinación de los filtros Grupo
 * y Favorito. También cubre vacío, eliminación de criterios, texto literal,
 * mayúsculas ASCII y exclusión de Ocultaciones.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class BusquedaCatalogoPersistenciaTest {
    private lateinit var db: AppDatabase
    private lateinit var repositorio: BusquedaCatalogoRepositorio
    private lateinit var fuenteId: Long
    private lateinit var listaId: Long
    private lateinit var canalIds: List<Long>
    private lateinit var grupos: Map<Int, Long>

    @Before
    fun abrir() {
        val contexto = ApplicationProvider.getApplicationContext<Context>()
        db =
            Room
                .inMemoryDatabaseBuilder(contexto, AppDatabase::class.java)
                .allowMainThreadQueries()
                .build()
        repositorio = BusquedaCatalogoRepositorio(db)
        runBlocking { sembrar() }
    }

    @After
    fun cerrar() {
        db.close()
    }

    @Test
    fun `primera pagina y segunda pagina son estables y sin duplicados`() {
        runBlocking {
            val primera = cargar(texto = TEXTO, offset = 0)
            val esperadaPrimera = idsCanal42().take(TAMANO)
            assertEquals(TAMANO, primera.size)
            assertEquals(esperadaPrimera, primera.map { it.id })
            assertEquals(primera.size, primera.map { it.id }.distinct().size)

            val repetida = cargar(texto = TEXTO, offset = 0)
            assertEquals(primera.map { it.id }, repetida.map { it.id })

            val segunda = cargar(texto = TEXTO, offset = TAMANO)
            assertEquals(idsCanal42().drop(TAMANO), segunda.map { it.id })
        }
    }

    @Test
    fun `filtros de grupo y favorito se combinan con el texto`() {
        runBlocking {
            val grupoUno = requireNotNull(grupos[1])
            val soloGrupo = cargar(grupoId = grupoUno)
            assertEquals(TAMANO, soloGrupo.size)
            assertEquals(canalIds.take(TAMANO), soloGrupo.map { it.id })

            val soloFavoritos = cargar(soloFavoritos = true)
            assertEquals(FAVORITOS, soloFavoritos.size)
            assertEquals(idsFavoritos(), soloFavoritos.map { it.id })

            val combinado =
                cargar(
                    texto = TEXTO,
                    grupoId = grupoUno,
                    soloFavoritos = true,
                )
            assertEquals(listOf(canalIds[42 - 1]), combinado.map { it.id })

            val sinInterseccion =
                cargar(
                    texto = TEXTO,
                    grupoId = requireNotNull(grupos[100]),
                    soloFavoritos = true,
                )
            assertTrue(sinInterseccion.isEmpty())
        }
    }

    @Test
    fun `criterios vacios y eliminados recuperan el catalogo`() {
        runBlocking {
            val vacio = cargar(texto = "NoExiste")
            assertTrue(vacio.isEmpty())

            val todos = cargar()
            assertEquals(TAMANO, todos.size)
            assertEquals(canalIds.take(TAMANO), todos.map { it.id })
        }
    }

    @Test
    fun `el texto se trata como literal y sin distinguir mayusculas ascii`() {
        runBlocking {
            assertTrue(cargar(texto = "Canal 42%").isEmpty())
            assertTrue(cargar(texto = "Canal 42_").isEmpty())
            assertEquals(idsCanal42().take(TAMANO), cargar(texto = "canal 42").map { it.id })
        }
    }

    @Test
    fun `un canal con dos apariencias aparece una sola vez`() {
        runBlocking {
            db.aparienciaDao().insertar(
                apariencia(
                    canalId = canalIds[42 - 1],
                    grupoId = requireNotNull(grupos[1]),
                    orden = ENTRADAS + 1,
                    clave = "presentacion:v2:duplicada",
                ),
            )

            val pagina = cargar(texto = TEXTO, offset = 0)
            assertEquals(TAMANO, pagina.size)
            assertEquals(1, pagina.count { it.id == canalIds[42 - 1] })
        }
    }

    @Test
    fun `las ocultaciones excluyen canales y grupos del resultado`() {
        runBlocking {
            db.ocultacionCanalDao().insertar(
                OcultacionCanalEntity(canalId = canalIds[42 - 1], creadoEn = AHORA),
            )
            val sinCanal = cargar(texto = TEXTO, offset = 0)
            assertEquals(TAMANO, sinCanal.size)
            assertTrue(sinCanal.none { it.id == canalIds[42 - 1] })

            val grupoUno = requireNotNull(grupos[1])
            db.ocultacionGrupoDao().insertar(
                OcultacionGrupoEntity(grupoId = grupoUno, creadoEn = AHORA),
            )
            assertTrue(cargar(grupoId = grupoUno).isEmpty())
        }
    }

    @Test
    fun `los grupos visibles de la lista activa se ordenan por nombre`() {
        runBlocking {
            val visibles = repositorio.gruposDeListaActiva().first()
            assertEquals(GRUPOS, visibles.size)
            assertEquals(visibles.map { it.nombre }.sorted(), visibles.map { it.nombre })
        }
    }

    private suspend fun cargar(
        texto: String = "",
        grupoId: Long? = null,
        soloFavoritos: Boolean = false,
        offset: Int = 0,
    ): List<CanalBusqueda> {
        val source =
            db.canalDao().buscarEnListaActiva(
                texto = texto.trim(),
                patron = patron(texto),
                grupoId = grupoId,
                soloFavoritos = soloFavoritos,
            )
        val parametros =
            PagingSource.LoadParams.Refresh<Int>(
                key = offset,
                loadSize = TAMANO,
                placeholdersEnabled = false,
            )
        val resultado = source.load(parametros)
        assertTrue("La consulta debía devolver una página", resultado is PagingSource.LoadResult.Page)
        return (resultado as PagingSource.LoadResult.Page<Int, CanalBusqueda>).data
    }

    private fun patron(texto: String): String {
        val limpio = texto.trim()
        if (limpio.isEmpty()) return "%"
        val escapado = limpio.replace("!", "!!").replace("%", "!%").replace("_", "!_")
        return "%$escapado%"
    }

    private fun idsCanal42(): List<Long> = canalIds.filterIndexed { indice, _ -> (indice + 1) % 100 == 42 }

    private fun idsFavoritos(): List<Long> =
        canalIds.filterIndexed { indice, _ ->
            val numero = indice + 1
            numero % 100 == 42 && numero <= 942
        }

    private suspend fun sembrar() {
        fuenteId =
            db.fuenteDao().insertar(
                FuenteEntity(
                    nombre = "Fuente P1",
                    estado = "activa",
                    usarIntervaloGlobal = true,
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )
        listaId =
            db.listaDao().insertar(
                ListaEntity(
                    fuenteId = fuenteId,
                    nombre = "Lista activa",
                    ubicacionCifrada = byteArrayOf(0x01),
                    ubicacionHmac = byteArrayOf(0x02),
                    esquema = "content",
                    orden = 0,
                    estado = "activa",
                    creadoEn = AHORA,
                    actualizadoEn = AHORA,
                ),
            )

        val idsGrupos =
            db.grupoDao().insertarTodos(
                (1..GRUPOS).map { numero ->
                    GrupoEntity(listaId = listaId, nombre = "Grupo ${numero.toString().padStart(3, '0')}")
                },
            )
        grupos = idsGrupos.mapIndexed { indice, id -> (indice + 1) to id }.toMap()

        canalIds =
            db.canalDao().insertarTodos(
                (1..ENTRADAS).map { numero ->
                    CanalEntity(
                        fuenteId = fuenteId,
                        nombre = if (numero % 100 == 42) TEXTO else "Canal ${numero.toString().padStart(5, '0')}",
                        urlStreamCifrada = byteArrayOf(0x01),
                        urlStreamHmac = byteArrayOf(0x02),
                        claveCanonica = "canon:$numero",
                        claveUnicidad = "canal:$numero",
                        claveDerivable = true,
                        creadoEn = AHORA,
                        actualizadoEn = AHORA,
                    )
                },
            )

        db.aparienciaDao().insertarTodas(
            (1..ENTRADAS).map { numero ->
                apariencia(
                    canalId = canalIds[numero - 1],
                    grupoId = requireNotNull(grupos[((numero - 1) / 100) + 1]),
                    orden = numero,
                    clave = "presentacion:v2:$numero",
                )
            },
        )

        (42..942 step 100).forEach { numero ->
            db.favoritoDao().insertar(
                FavoritoEntity(canalId = canalIds[numero - 1], creadoEn = AHORA),
            )
        }
    }

    private fun apariencia(
        canalId: Long,
        grupoId: Long,
        orden: Int,
        clave: String,
    ): AparienciaEntity =
        AparienciaEntity(
            listaId = listaId,
            canalId = canalId,
            grupoId = grupoId,
            orden = orden,
            clavePresentacion = clave,
            repeticiones = 1,
            creadoEn = AHORA,
            actualizadoEn = AHORA,
        )

    private companion object {
        const val ENTRADAS = 10_000
        const val GRUPOS = 100
        const val TAMANO = 50
        const val FAVORITOS = 10
        const val TEXTO = "Canal 42"
        val AHORA: Instant = Instant.parse("2026-01-01T00:00:00Z")
    }
}
